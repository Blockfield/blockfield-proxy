package io.blockfield.proxy;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Control-plane v2 push consumer: the sole game-gate source (plan CP-S5; CP-C2 cutover removed the
 * U1 {@code GET /v1/gate} poller and the {@code gate.json} file gate).
 *
 * <p>It bootstraps from {@code GET /v2/agents/{id}/snapshot}, streams {@code GET
 * /v2/agents/{id}/events} (SSE) and confirms each frame with {@code POST /v2/agents/{id}/acks}.
 * Wire contract is frozen at control-plane origin/main {@code 6df1f95 + cf660e4}; this class
 * consumes it and never invents an alternative API.
 *
 * <p>Safety properties: fail closed before the first snapshot; past the lease the proxy keeps
 * serving the last verified snapshot (fail-static, cp#5) instead of closing, until a hard max age
 * (default 24 h, {@code BLOCKFIELD_CP_FAIL_STATIC_MAX_AGE_MS}) after which it closes again; a
 * stream with no frame or keep-alive for three keep-alive intervals is aborted and reconnected;
 * stale/forged/gapped or unparseable frames never move the gate (they request a re-snapshot
 * instead); non-executable and non-gate frames advance the cursor without changing admission; ACKs
 * are best-effort — a lost ACK replays on reconnect and the cursor/epoch guards make the replay
 * idempotent (a cursor that ran ahead of the server's durable cursor self-heals through a {@code
 * 409 snapshot_required} re-snapshot). A snapshot or frame that fails verification never moves the
 * gate and never extends service.
 */
final class ControlSubscriber {
    /** Admission lease: 60 s without a fresh snapshot/apply closes entry (fail closed). */
    static final long LEASE_MS = 60_000;

    private static final Duration RPC_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration STREAM_TIMEOUT = Duration.ofSeconds(75);
    private static final long RECONNECT_MS = 1_000;
    private static final long RECONNECT_MAX_MS = 30_000;
    private static final long WARN_MS = 60_000;

    /** The CP heartbeats a healthy stream every 10 s; three missed beats mean a dead stream. */
    static final long IDLE_MS = 30_000;

    /**
     * Fail-static bound: past this age a verified snapshot closes entry again. Default 24 h covers
     * a day-long CP outage or maintenance window while bounding stale admission (a revoked gate
     * stops serving at most a day after the CP went dark); daily operation already re-verifies far
     * more often. Tunable via {@code BLOCKFIELD_CP_FAIL_STATIC_MAX_AGE_MS}.
     */
    static final long FAIL_STATIC_MAX_AGE_MS = TimeUnit.HOURS.toMillis(24);

    /** The admission lease, on the monotonic clock so wall steps cannot extend it. */
    static final long LEASE_NS = TimeUnit.MILLISECONDS.toNanos(LEASE_MS);

    static final long IDLE_NS = TimeUnit.MILLISECONDS.toNanos(IDLE_MS);

    /** The fail-static bound, on the monotonic clock like the lease. */
    static final long FAIL_STATIC_MAX_AGE_NS =
            TimeUnit.MILLISECONDS.toNanos(FAIL_STATIC_MAX_AGE_MS);

    /** Serving mode for the last {@link #current()} call; transitions log once with a counter. */
    private enum Mode {
        BOOT,
        NORMAL,
        STATIC,
        EXPIRED
    }

    /** What {@link #offer} did with one stream envelope. */
    enum Verdict {
        /** Gate applied; receipts queued on the ordered ACK worker. */
        APPLIED,
        /** Gate unchanged; frame consumed and its receipts queued. */
        HELD,
        /** Caller must re-snapshot (epoch/generation/cursor/audience mismatch or gap). */
        RESYNC,
    }

    interface Clock {
        long now();
    }

    /** Sends one ACK body; prod POSTs it, tests record it. Failures are logged, never thrown. */
    interface AckSender {
        void send(String body) throws Exception;
    }

    private final String consumer;
    private final URI snapshotUri;
    private final URI eventsUri;
    private final URI acksUri;

    /** Swapped by {@link CredentialRenewer}; the next request uses the successor. */
    private volatile String token;

    private final Clock clock;
    private final LongSupplier monotonic;
    private final Consumer<String> warn;
    private final AckSender acks;

    /** ACK jobs waiting on the ordered worker; bounds memory under an ACK outage. */
    private static final int ACK_QUEUE_CAP = 256;

    private final Object lock = new Object();
    private String epoch;
    private String generation;

    /**
     * Bumped under {@code lock} on every cursor reset (resync, snapshot): the ACK worker drops jobs
     * queued in an older era, so a slow ACK cannot write the cursor after a reset.
     */
    private long resetEpoch;

    /** Set until a snapshot is applied; a resync sets it again. The loop snapshots on this. */
    private boolean needSnapshot = true;

    /** Last ACKed sequence: the visible cursor, also used for SSE resume. Lags applied. */
    private long cursor = -1;

    /** Last applied sequence: duplicate/gap checks use this, never the ACKed cursor. */
    private long applied = -1;

    private Gate gate = Gate.closed();

    /** Last verified game leg came from CP {@code maintenance} mode: closed entry is intended. */
    private boolean gameMaintenance;

    private volatile long freshAt = -LEASE_NS - 1;

    /**
     * Monotonic time of the last verified gate state (verified snapshot or applied frame);
     * keep-alives and held frames prove the CP is reachable but re-verify nothing, so they refresh
     * only the lease. Until the first verified snapshot entry stays closed.
     */
    private long verifiedAt = Long.MIN_VALUE;

    /** Hard bound on serving a stale verified snapshot past the lease. */
    private final long maxAgeNs;

    private Mode lastMode = Mode.BOOT;
    private long failStaticTransitions;
    private volatile long lastWarnMs = -WARN_MS;
    private final boolean live;
    private volatile boolean stopped;
    private volatile Thread streamThread;
    private volatile InputStream liveBody;
    private volatile long lastLineAt;

    /** One ordered FIFO worker: stream thread enqueues, worker POSTs then advances. */
    private final BlockingQueue<AckJob> ackQueue = new ArrayBlockingQueue<>(ACK_QUEUE_CAP);

    private final Thread ackThread;

    ControlSubscriber(
            String consumer,
            URI snapshotUri,
            URI eventsUri,
            URI acksUri,
            String token,
            Clock clock,
            Consumer<String> warn,
            AckSender acks,
            LongSupplier monotonic,
            boolean live,
            long maxAgeNs) {
        this.consumer = consumer;
        this.snapshotUri = snapshotUri;
        this.eventsUri = eventsUri;
        this.acksUri = acksUri;
        this.token = token;
        this.clock = clock;
        this.warn = warn;
        this.acks = acks;
        this.monotonic = monotonic;
        this.live = live;
        this.maxAgeNs = maxAgeNs;
        // Started last: the worker only touches final fields, the queue and the lock.
        Thread ackWorker = new Thread(this::drainAcks, "cp-v2-acks");
        ackWorker.setDaemon(true);
        this.ackThread = ackWorker;
        ackWorker.start();
    }

    /**
     * Normalized control-plane gate URI. A trailing slash must not turn the path into {@code
     * //v1/gate}, and a path prefix, query or fragment must not move it elsewhere: CP matches the
     * exact path /v1/gate, so anything else 404s every poll and closes entry from boot. Throws
     * IllegalArgumentException on malformed input.
     */
    static URI gateUri(String raw) {
        String base = raw.strip().replaceAll("/+$", "");
        URI parsed;
        try {
            parsed = new URI(base);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("invalid control-plane URL: " + base, e);
        }
        if (parsed.getScheme() == null || parsed.getHost() == null)
            throw new IllegalArgumentException("invalid control-plane URL: " + base);
        try {
            return new URI(
                    parsed.getScheme(),
                    null,
                    parsed.getHost(),
                    parsed.getPort(),
                    "/v1/gate",
                    null,
                    null);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("invalid control-plane URL: " + base, e);
        }
    }

    /**
     * Null when v2 is not configured (no consumer): the caller pins the game leg closed, never
     * falls back to a file. Non-null but never-live when half-configured: the game leg stays
     * closed. Never throws.
     */
    static ControlSubscriber fromEnv(Logger logger) {
        String url = System.getenv("BLOCKFIELD_CP_URL");
        String envToken = System.getenv("BLOCKFIELD_CP_TOKEN");
        CredentialRenewer renewer = null;
        if (url != null && !url.isBlank() && envToken != null && !envToken.isBlank()) {
            try {
                renewer = CredentialRenewer.http(url, statePath(), envToken, logger);
            } catch (IllegalArgumentException e) {
                logger.warn(
                        "control-plane URL invalid, credential renewal off: {}", e.getMessage());
            }
        }
        ControlSubscriber sub =
                fromParts(
                        url,
                        renewer != null ? renewer.token() : envToken,
                        System.getenv("BLOCKFIELD_CP_CONSUMER"),
                        logger,
                        System::currentTimeMillis,
                        logger::warn,
                        null,
                        System::nanoTime,
                        failStaticMaxAgeMs(
                                System.getenv("BLOCKFIELD_CP_FAIL_STATIC_MAX_AGE_MS"), logger));
        if (sub != null) sub.start();
        if (renewer != null) {
            if (sub != null) renewer.onToken(sub::setToken);
            renewer.start();
        }
        return sub;
    }

    /**
     * Default sits on a mount prod already has; override with BLOCKFIELD_CP_STATE_FILE (its
     * directory must exist on a persistent POSIX filesystem).
     */
    static java.nio.file.Path statePath() {
        String raw = System.getenv("BLOCKFIELD_CP_STATE_FILE");
        return java.nio.file.Path.of(
                raw == null || raw.isBlank() ? "/workshop-gate/.proxy-cp-credential.json" : raw);
    }

    void setToken(String next) {
        token = next;
        if (acks instanceof HttpAckSender http) http.token = next;
    }

    /**
     * Live bearer for CP calls sharing this consumer's service credential (renewal keeps it fresh).
     */
    String token() {
        return token;
    }

    /** Fail-safe bound parse: blank or invalid values fall back to the default, never unbounded. */
    static long failStaticMaxAgeMs(String raw, Logger logger) {
        if (raw == null || raw.isBlank()) return FAIL_STATIC_MAX_AGE_MS;
        try {
            long ms = Long.parseLong(raw.strip());
            if (ms <= 0) throw new NumberFormatException("non-positive max age");
            return ms;
        } catch (NumberFormatException e) {
            logger.warn(
                    "BLOCKFIELD_CP_FAIL_STATIC_MAX_AGE_MS invalid ({}), using default {} ms",
                    raw,
                    FAIL_STATIC_MAX_AGE_MS);
            return FAIL_STATIC_MAX_AGE_MS;
        }
    }

    /** Seam for tests: builds without starting the stream thread (null sender POSTs to the CP). */
    static ControlSubscriber fromParts(
            String url,
            String token,
            String consumer,
            Logger logger,
            Clock clock,
            Consumer<String> warn,
            AckSender sender) {
        return fromParts(url, token, consumer, logger, clock, warn, sender, System::nanoTime);
    }

    static ControlSubscriber fromParts(
            String url,
            String token,
            String consumer,
            Logger logger,
            Clock clock,
            Consumer<String> warn,
            AckSender sender,
            LongSupplier monotonic) {
        return fromParts(
                url,
                token,
                consumer,
                logger,
                clock,
                warn,
                sender,
                monotonic,
                FAIL_STATIC_MAX_AGE_MS);
    }

    /** Seam for tests: explicit fail-static bound in milliseconds. */
    static ControlSubscriber fromParts(
            String url,
            String token,
            String consumer,
            Logger logger,
            Clock clock,
            Consumer<String> warn,
            AckSender sender,
            LongSupplier monotonic,
            long maxAgeMs) {
        if (consumer == null || consumer.isBlank()) return null;
        AckSender unconfigured = sender != null ? sender : body -> {};
        if (!consumer.matches("[^\\s/]{1,128}")) {
            // A set but malformed id is a configuration error, not "v2 not configured":
            // pin game entry closed instead of falling back to the file.
            logger.warn("control-plane v2 consumer id invalid, game entry stays closed");
            return new ControlSubscriber(
                    consumer,
                    null,
                    null,
                    null,
                    "",
                    clock,
                    warn,
                    unconfigured,
                    monotonic,
                    false,
                    TimeUnit.MILLISECONDS.toNanos(maxAgeMs));
        }
        if (url == null || url.isBlank() || token == null || token.isBlank()) {
            logger.warn(
                    "control-plane v2 consumer configured without URL/token: game entry stays"
                            + " closed");
            return new ControlSubscriber(
                    consumer,
                    null,
                    null,
                    null,
                    "",
                    clock,
                    warn,
                    unconfigured,
                    monotonic,
                    false,
                    TimeUnit.MILLISECONDS.toNanos(maxAgeMs));
        }
        try {
            URI base = gateUri(url);
            String id = URLEncoder.encode(consumer, StandardCharsets.UTF_8);
            String prefix = "https://" + base.getAuthority();
            if (!base.getScheme().equals("https"))
                prefix = base.getScheme() + "://" + base.getAuthority();
            URI snapshotUri = URI.create(prefix + "/v2/agents/" + id + "/snapshot");
            URI eventsUri = URI.create(prefix + "/v2/agents/" + id + "/events");
            URI acksUri = URI.create(prefix + "/v2/agents/" + id + "/acks");
            AckSender acks = sender != null ? sender : new HttpAckSender(acksUri, token);
            return new ControlSubscriber(
                    consumer,
                    snapshotUri,
                    eventsUri,
                    acksUri,
                    token,
                    clock,
                    warn,
                    acks,
                    monotonic,
                    true,
                    TimeUnit.MILLISECONDS.toNanos(maxAgeMs));
        } catch (IllegalArgumentException e) {
            logger.warn(
                    "control-plane v2 URL invalid, game entry stays closed: {}", e.getMessage());
            return new ControlSubscriber(
                    consumer,
                    null,
                    null,
                    null,
                    "",
                    clock,
                    warn,
                    sender != null ? sender : body -> {},
                    monotonic,
                    false,
                    TimeUnit.MILLISECONDS.toNanos(maxAgeMs));
        }
    }

    void start() {
        if (!live) return;
        Thread thread = new Thread(this::loop, "cp-v2-stream");
        thread.setDaemon(true);
        streamThread = thread;
        thread.start();
    }

    /** Stops the stream thread and the ACK worker; pending ACKs are dropped. */
    void stop() {
        stopped = true;
        Thread thread = streamThread;
        if (thread != null) thread.interrupt();
        closeBody(liveBody);
        ackThread.interrupt();
    }

    /** Closing the body aborts a blocked read; a stale close is a no-op. */
    private static void closeBody(InputStream body) {
        if (body != null) {
            try {
                body.close();
            } catch (IOException ignored) {
                // The stream is going away anyway; loop() exits on the closed read.
            }
        }
    }

    /**
     * Current gate: the last verified snapshot within the lease, the same snapshot past the lease
     * while it is younger than the fail-static max age, closed before the first snapshot or past
     * the max age. Never an unverified or partial gate.
     *
     * <p>Fail-static transitions (entering, recovery, expiry) log once with a counter; steady
     * service, including steady fail-static, never logs again.
     */
    Gate current() {
        long now = monotonic.getAsLong();
        synchronized (lock) {
            Mode mode;
            if (now - freshAt <= LEASE_NS) mode = Mode.NORMAL;
            // Fail-static serves only with a valid cursor: while RESYNC_PENDING the
            // lease is not extended, so entry closes after it as before fail-static.
            else if (applied >= 0 && verifiedAt != Long.MIN_VALUE && now - verifiedAt <= maxAgeNs)
                mode = Mode.STATIC;
            else mode = verifiedAt == Long.MIN_VALUE ? Mode.BOOT : Mode.EXPIRED;
            if (mode != lastMode) {
                reportTransition(lastMode, mode);
                lastMode = mode;
            }
            return mode == Mode.NORMAL || mode == Mode.STATIC ? gate : Gate.closed();
        }
    }

    /**
     * Log-scraped fail-static counter: how many times service entered, recovered from, or aged out
     * of fail-static (house pattern, cf. login-rejection counters in Authentication).
     */
    long failStaticTransitions() {
        synchronized (lock) {
            return failStaticTransitions;
        }
    }

    /** True while serving the last verified snapshot past the lease. */
    boolean failStaticActive() {
        synchronized (lock) {
            return lastMode == Mode.STATIC;
        }
    }

    /** Any CP contact (snapshot, frame, keep-alive) within the lease. */
    boolean linkFresh() {
        return monotonic.getAsLong() - freshAt <= LEASE_NS;
    }

    /**
     * The last verified game leg was an announced CP maintenance: a closed game gate then is
     * operator intent, not an outage, so the closed-gate owner alert stays silent. Set only by
     * verified game legs; workshop frames never touch it.
     */
    boolean maintenanceAnnounced() {
        synchronized (lock) {
            return gameMaintenance;
        }
    }

    /** One warn line per mode change into, out of, or past fail-static; boot stays silent. */
    private void reportTransition(Mode from, Mode to) {
        if (to == Mode.STATIC) {
            failStaticTransitions++;
            warn.accept(
                    "control-plane v2 fail-static active, serving last verified gate (transition "
                            + failStaticTransitions
                            + ")");
        } else if ((from == Mode.STATIC || from == Mode.EXPIRED) && to == Mode.NORMAL) {
            failStaticTransitions++;
            warn.accept(
                    "control-plane v2 recovered on verified gate (transition "
                            + failStaticTransitions
                            + ")");
        } else if ((from == Mode.STATIC || from == Mode.NORMAL) && to == Mode.EXPIRED) {
            failStaticTransitions++;
            warn.accept(
                    "control-plane v2 fail-static expired, entry closed (transition "
                            + failStaticTransitions
                            + ")");
        }
    }

    /** Last ACKed sequence: lags applied state during an ACK outage, never runs ahead. */
    long cursor() {
        synchronized (lock) {
            return cursor;
        }
    }

    /** Last applied sequence: what the gate already enforces, ACKs pending or not. */
    long applied() {
        synchronized (lock) {
            return applied;
        }
    }

    /**
     * Applies a {@code GET snapshot} body. Returns false (state untouched) on any foreign or
     * malformed document.
     */
    boolean applySnapshot(String body) {
        Snapshot parsed;
        try {
            parsed = parseSnapshot(body);
        } catch (RuntimeException e) {
            return false;
        }
        if (parsed == null) return false;
        long now = monotonic.getAsLong();
        synchronized (lock) {
            epoch = parsed.epoch;
            generation = parsed.generation;
            cursor = parsed.cursor;
            applied = parsed.cursor;
            gate = parsed.gate;
            gameMaintenance = parsed.gameMaintenance;
            needSnapshot = false;
            resetEpoch++;
            // Verified here: schema, audience, epoch/generation, cursor and gate all checked.
            verifiedAt = now;
        }
        freshAt = now;
        return true;
    }

    /**
     * Offers one {@code event: control} data envelope. Never throws; unparseable input requests a
     * re-snapshot, since the proxy was handed a change it cannot apply.
     */
    Verdict offer(String dataJson) {
        JsonObject envelope;
        try {
            envelope = JsonParser.parseString(dataJson).getAsJsonObject();
        } catch (RuntimeException e) {
            return Verdict.RESYNC;
        }
        String envEpoch = text(envelope, "epoch");
        String audience = text(envelope, "audience");
        String envGeneration = text(envelope, "generation");
        Long sequence = lng(envelope, "sequence");
        Boolean executable = bool(envelope, "executable");
        if (envelope.get("schema") == null
                || !envelope.get("schema").isJsonPrimitive()
                || !envelope.get("schema").getAsJsonPrimitive().isNumber()
                || !envelope.get("schema").getAsString().equals("2")
                || !isUuid(envEpoch)
                || !isUuid(envGeneration)
                || sequence == null
                || sequence < 1
                || executable == null
                || audience == null
                || !audience.equals(consumer)) {
            return Verdict.RESYNC;
        }
        long era;
        synchronized (lock) {
            era = resetEpoch;
            if (epoch == null || !epoch.equals(envEpoch)) return Verdict.RESYNC;
            if (sequence <= applied) {
                // Duplicate delivery on a live stream: the CP is reachable, so the lease holds.
                freshAt = monotonic.getAsLong();
                return Verdict.HELD;
            }
            if (sequence > applied + 1) return Verdict.RESYNC;
            if (!generation.equals(envGeneration)) return Verdict.RESYNC;
        }
        String payloadHash = text(envelope, "payloadHash");
        // Revision only labels the ACK: a missing, fractional, or out-of-long one is null.
        Long revision = integral(envelope, "revision");
        if (!executable || envelope.get("reason") != null && !envelope.get("reason").isJsonNull()) {
            freshAt = monotonic.getAsLong();
            markApplied(sequence);
            enqueueAcks(
                    new AckJob(
                            envEpoch,
                            sequence,
                            envGeneration,
                            revision,
                            payloadHash,
                            List.of(new AckState("received", "ok")),
                            era));
            return Verdict.HELD;
        }
        if (payloadHash == null || !payloadHash.matches("[0-9a-f]{64}")) {
            // The change the CP sent cannot be verified or ACKed: re-snapshot instead of
            // skipping it, or replay would never bring it back.
            return Verdict.RESYNC;
        }
        if (expired(envelope)) {
            freshAt = monotonic.getAsLong();
            markApplied(sequence);
            enqueueAcks(
                    new AckJob(
                            envEpoch,
                            sequence,
                            envGeneration,
                            revision,
                            payloadHash,
                            List.of(new AckState("rejected", "ok")),
                            era));
            return Verdict.HELD;
        }
        TargetedGate update;
        try {
            update = gateFromPayload(envelope.get("payload"));
        } catch (RuntimeException e) {
            // offer() never throws: a frame the proxy cannot parse re-snapshots.
            return Verdict.RESYNC;
        }
        if (update == null) {
            freshAt = monotonic.getAsLong();
            markApplied(sequence);
            enqueueAcks(
                    new AckJob(
                            envEpoch,
                            sequence,
                            envGeneration,
                            revision,
                            payloadHash,
                            List.of(new AckState("rejected", "ok")),
                            era));
            return Verdict.HELD;
        }
        // State first, on this thread: a stalled ACK endpoint must never delay the gate
        // or the next frame. Receipts and the visible cursor follow on the worker.
        long now = monotonic.getAsLong();
        synchronized (lock) {
            gate = gate.withLeg(update.target(), update.leg());
            if ("game".equals(update.target()))
                gameMaintenance = "maintenance".equals(update.mode());
            applied = sequence;
            // Verified here: epoch, sequence continuity, generation, payload hash and gate.
            verifiedAt = now;
        }
        freshAt = now;
        enqueueAcks(
                new AckJob(
                        envEpoch,
                        sequence,
                        envGeneration,
                        revision,
                        payloadHash,
                        List.of(
                                new AckState("received", "ok"),
                                new AckState("validated", "ok"),
                                new AckState("applied", "ok")),
                        era));
        return Verdict.APPLIED;
    }

    private void markApplied(long sequence) {
        synchronized (lock) {
            applied = sequence;
        }
    }

    // The worker advances the visible cursor after its receipts are POSTed, so the
    // cursor still implies the receipts were sent but may lag applied state. A job queued
    // before a reset (resync, snapshot) must not write the cursor afterwards, and the cursor
    // never moves backwards.
    private void advance(long sequence, long era) {
        synchronized (lock) {
            if (era == resetEpoch && sequence > cursor) cursor = sequence;
        }
    }

    private record AckState(String name, String result) {}

    private record AckJob(
            String epoch,
            long sequence,
            String generation,
            Long revision,
            String payloadHash,
            List<AckState> states,
            long resetEpoch) {}

    /** Ordered ACK worker: POSTs each frame's receipts, then advances the visible cursor. */
    private void drainAcks() {
        try {
            while (!stopped) {
                AckJob job = ackQueue.take();
                if (stopped) return;
                for (AckState state : job.states()) {
                    String body =
                            ackBody(
                                    job.epoch(),
                                    job.sequence(),
                                    job.generation(),
                                    job.revision(),
                                    job.payloadHash(),
                                    state.name(),
                                    state.result());
                    if (body != null) sendBody(body);
                }
                advance(job.sequence(), job.resetEpoch());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // Never blocks the stream thread: a stalled ACK endpoint must never delay the next
    // frame. Bounded: a full queue sheds the newest receipts (the frame's state stays
    // applied); the resume cursor then lags behind applied, never runs ahead of it.
    // shortcut: shed receipts are dropped, not retried; when upgrading, re-enqueue
    // un-ACKed frames on replay or use cumulative ACKs.
    private void enqueueAcks(AckJob job) {
        if (stopped) return;
        if (!ackQueue.offer(job)) {
            warnOnce(
                    "control-plane v2 ack queue full, shedding receipts for sequence "
                            + job.sequence());
        }
    }

    /** Builds one ACK body on the worker just before POSTing, so actualAt is send time. */
    private String ackBody(
            String envEpoch,
            long sequence,
            String envGeneration,
            Long revision,
            String payloadHash,
            String state,
            String result) {
        if (payloadHash == null) return null;
        return "{\"epoch\":\""
                + envEpoch
                + "\",\"sequence\":"
                + sequence
                + ",\"generation\":\""
                + envGeneration
                + "\",\"revision\":"
                + (revision == null ? "null" : revision)
                + ",\"payloadHash\":\""
                + payloadHash
                + "\",\"state\":\""
                + state
                + "\",\"actualAt\":\""
                + Instant.ofEpochMilli(clock.now()).toString()
                + "\",\"result\":\""
                + result
                + "\"}";
    }

    private void sendBody(String body) {
        try {
            acks.send(body);
        } catch (Exception e) {
            warnOnce("control-plane v2 ack failed, stream replay will recover: " + e);
        }
    }

    private boolean expired(JsonObject envelope) {
        String at = text(envelope, "expiresAt");
        if (at == null) return true;
        try {
            return !Instant.parse(at).isAfter(Instant.ofEpochMilli(clock.now()));
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** One gate leg with the target it applies to; {@code mode} is the CP game mode or null. */
    private record TargetedGate(String target, Gate leg, String mode) {}

    /** Gate legs only; anything else (actions, other namespaces, other targets) is not ours. */
    private static TargetedGate gateFromPayload(JsonElement payload) {
        if (payload == null || !payload.isJsonObject()) return null;
        JsonObject obj = payload.getAsJsonObject();
        if (!"settings".equals(text(obj, "kind"))) return null;
        if (!"gate".equals(text(obj, "namespace"))) return null;
        String target = text(obj, "target");
        JsonElement value = obj.get("value");
        if (value == null || !value.isJsonObject()) return null;
        JsonObject v = value.getAsJsonObject();
        // The game leg follows v2; a workshop leg must never drive game entry, and vice versa.
        if ("*".equals(target) || "game".equals(target)) {
            String mode = text(v, "mode");
            return new TargetedGate("game", Gate.fromCp(mode, text(v, "message")), mode);
        }
        // The workshop leg drives workshop entry on the same lease as the game leg.
        if ("workshop".equals(target))
            return new TargetedGate("workshop", Gate.fromWorkshop(v), null);
        return null;
    }

    private record Snapshot(
            String epoch, String generation, long cursor, Gate gate, boolean gameMaintenance) {}

    private Snapshot parseSnapshot(String body) {
        JsonObject json = JsonParser.parseString(body).getAsJsonObject();
        if (json.get("schema") == null
                || !json.get("schema").isJsonPrimitive()
                || !json.get("schema").getAsJsonPrimitive().isNumber()
                || !json.get("schema").getAsString().equals("2")) return null;
        String audience = text(json, "audience");
        String snapEpoch = text(json, "epoch");
        String snapGeneration = text(json, "generation");
        Long snapCursor = lng(json, "cursor");
        if (!consumer.equals(audience)
                || !isUuid(snapEpoch)
                || !isUuid(snapGeneration)
                || snapCursor == null
                || snapCursor < 0
                || json.get("settings") == null
                || !json.get("settings").isJsonArray()) return null;
        Gate game = null;
        int gameMatches = 0;
        String gameMode = null;
        Gate workshop = null;
        int workshopMatches = 0;
        for (JsonElement el : json.getAsJsonArray("settings")) {
            if (!el.isJsonObject()) continue;
            JsonObject entry = el.getAsJsonObject();
            if (!"gate".equals(text(entry, "namespace"))) continue;
            JsonElement value = entry.get("value");
            if (value == null || !value.isJsonObject()) continue;
            JsonObject v = value.getAsJsonObject();
            String target = text(entry, "target");
            if ("*".equals(target) || "game".equals(target)) {
                gameMode = text(v, "mode");
                game = Gate.fromCp(gameMode, text(v, "message"));
                gameMatches++;
            } else if ("workshop".equals(target)) {
                workshop = Gate.fromWorkshop(v);
                workshopMatches++;
            }
        }
        // Ambiguous game legs pin the whole gate closed instead of depending on array order.
        // A missing workshop leg reads closed; several of them fail only that leg closed, so a
        // duplicated workshop row can never close game entry.
        return new Snapshot(
                snapEpoch,
                snapGeneration,
                snapCursor,
                Gate.dual(
                        gameMatches == 1 ? game : Gate.closed(),
                        workshopMatches <= 1 ? workshop : Gate.closed()),
                gameMatches == 1 && "maintenance".equals(gameMode));
    }

    private static String text(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        return el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()
                ? el.getAsString()
                : null;
    }

    private static Long lng(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        try {
            return el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber()
                    ? el.getAsLong()
                    : null;
        } catch (NumberFormatException | UnsupportedOperationException e) {
            return null;
        }
    }

    /**
     * Integral JSON numbers only. Gson coerces fractional numbers to long without throwing, so
     * match the raw form first: anything but plain digits degrades to null instead of a silently
     * wrong ACK receipt.
     */
    private static Long integral(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        if (el == null || !el.isJsonPrimitive() || !el.getAsJsonPrimitive().isNumber()) return null;
        if (!el.getAsString().matches("-?\\d+")) return null;
        try {
            return Long.parseLong(el.getAsString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Boolean bool(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        return el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isBoolean()
                ? el.getAsBoolean()
                : null;
    }

    private static boolean isUuid(String value) {
        if (value == null) return false;
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Reconnect delay with backoff: a transient blip re-attaches in ~1 s so a gate change is
     * visible within about a second, while a persistent outage backs off to RECONNECT_MAX_MS
     * instead of hammering the CP. Pure for tests.
     */
    static long reconnectDelayMs(int consecutiveFailures) {
        long shift = Math.min(Math.max(consecutiveFailures, 0), 5);
        return Math.min(RECONNECT_MS << shift, RECONNECT_MAX_MS);
    }

    private void warnOnce(String message) {
        long now = clock.now();
        if (now - lastWarnMs >= WARN_MS) {
            lastWarnMs = now;
            warn.accept(message);
        }
    }

    private void loop() {
        int failures = 0;
        while (!stopped && !Thread.currentThread().isInterrupted()) {
            try {
                if (isResyncPending()) {
                    // RESYNC_PENDING (no valid cursor): the snapshot must succeed before
                    // re-attaching; a 409 keeps this state. Never stream here: with no
                    // cursor every frame would resync, so the gate could never apply
                    // while the snapshot is down.
                    try {
                        snapshot();
                    } catch (IOException e) {
                        failures++;
                        warnOnce(
                                "control-plane v2 snapshot failed while resync pending,"
                                        + " retrying: "
                                        + e);
                    }
                } else if (needSnapshot()) {
                    try {
                        snapshot();
                    } catch (IOException e) {
                        // Best-effort: with /snapshot down the events leg still carries
                        // verified frames, so re-attach instead of stalling. Stale frames
                        // verify against the held epoch/generation into the RESYNC path.
                        failures++;
                        warnOnce("control-plane v2 snapshot failed, staying on events: " + e);
                    }
                }
                if (!isResyncPending()) {
                    stream();
                    // A clean close re-verifies too: the gate may have changed while
                    // the stream was down, and fail-static must not hide that.
                    setNeedSnapshot();
                    failures = 0;
                }
            } catch (Resync e) {
                failures++;
                boolean fresh;
                synchronized (lock) {
                    fresh = applied >= 0;
                    cursor = -1;
                    applied = -1;
                    needSnapshot = true;
                    resetEpoch++;
                }
                // Log the CURSOR_VALID -> RESYNC_PENDING transition once; retries
                // while already pending stay silent.
                if (fresh)
                    warn.accept(
                            "control-plane v2 resync pending, entry closes after lease"
                                    + " until a snapshot succeeds");
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                // Transport failure: re-verify via snapshot first, so a reachable
                // snapshot sees the change; the snapshot itself is best-effort, so
                // fail-static applies only when the events leg is down too.
                failures++;
                setNeedSnapshot();
                warnOnce("control-plane v2 stream failed, keeping last gate: " + e);
            }
            if (stopped) return;
            try {
                // The first failure (failures == 1) waits the base 1 s, then doubles.
                Thread.sleep(reconnectDelayMs(failures - 1));
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private boolean needSnapshot() {
        synchronized (lock) {
            return needSnapshot;
        }
    }

    /**
     * RESYNC_PENDING: no valid cursor (applied is negative after a resync, a 409, or a generation
     * change). While pending the loop must not attach the stream and fail-static must not serve;
     * the snapshot is retried until one succeeds.
     */
    private boolean isResyncPending() {
        synchronized (lock) {
            return applied < 0;
        }
    }

    private void setNeedSnapshot() {
        synchronized (lock) {
            needSnapshot = true;
        }
    }

    private void snapshot() throws IOException, InterruptedException {
        HttpRequest request =
                HttpRequest.newBuilder(snapshotUri)
                        .timeout(RPC_TIMEOUT)
                        .header("Authorization", "Bearer " + token)
                        .GET()
                        .build();
        HttpResponse<String> response = http().send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 409) throw new Resync();
        if (response.statusCode() != 200)
            throw new IOException("CP status " + response.statusCode());
        if (!applySnapshot(response.body())) throw new IOException("rejected snapshot");
    }

    private void stream() throws IOException, InterruptedException {
        String id;
        long from;
        synchronized (lock) {
            id = epoch;
            from = cursor;
        }
        URI uri = URI.create(eventsUri + "?epoch=" + id + "&cursor=" + from);
        HttpRequest request =
                HttpRequest.newBuilder(uri)
                        .timeout(STREAM_TIMEOUT)
                        .header("Authorization", "Bearer " + token)
                        .header("Accept", "text/event-stream")
                        .header("Last-Event-ID", id + ":" + from)
                        .GET()
                        .build();
        HttpResponse<InputStream> response =
                http().send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() == 409) throw new Resync();
        if (response.statusCode() != 200)
            throw new IOException("CP status " + response.statusCode());
        String event = "";
        StringBuilder data = new StringBuilder();
        // Stream incrementally: the CP keeps this connection open, so waiting for the
        // whole body would delay every frame until the server drops the stream. A
        // watchdog bounds the blocking read itself: with no line for three keep-alive
        // intervals it closes the body, which unblocks readLine, and loop() reconnects.
        // stop() closes the body the same way, so no read outlives IDLE or stop.
        liveBody = response.body();
        lastLineAt = monotonic.getAsLong();
        Thread watcher = new Thread(() -> watchIdle(response.body()), "cp-v2-idle-watch");
        watcher.setDaemon(true);
        watcher.start();
        try (BufferedReader reader =
                new BufferedReader(
                        new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lastLineAt = monotonic.getAsLong();
                // Keep-alive comments carry no frame but prove the CP is reachable.
                if (line.startsWith(":")) {
                    freshAt = monotonic.getAsLong();
                    continue;
                }
                if (line.startsWith("event:")) {
                    event = line.substring(6).strip();
                } else if (line.startsWith("data:")) {
                    // Multi-line events join with a newline per the SSE spec.
                    if (data.length() > 0) data.append('\n');
                    data.append(line.substring(5).strip());
                } else if (line.isEmpty()) {
                    if ("resync".equals(event)) throw new Resync();
                    if ("control".equals(event)
                            && data.length() > 0
                            && offer(data.toString()) == Verdict.RESYNC) throw new Resync();
                    event = "";
                    data.setLength(0);
                }
            }
        } finally {
            liveBody = null;
            watcher.interrupt();
            try {
                watcher.join(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Closes a stream that went quiet past the idle bound, unblocking its read. One daemon thread
     * per stream; the owner joins it before returning, so it never outlives or closes another
     * stream's body.
     */
    private void watchIdle(InputStream body) {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                long remainingNs = lastLineAt + IDLE_NS - monotonic.getAsLong();
                if (remainingNs <= 0) {
                    if (body == liveBody) closeBody(body);
                    return;
                }
                Thread.sleep(Math.min(TimeUnit.NANOSECONDS.toMillis(remainingNs) + 1, 1_000));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private HttpClient http() {
        return newHttpClient();
    }

    /**
     * The control plane serves plain HTTP with no h2c support: an HTTP/2 client sends an {@code
     * Upgrade} header on every poll and the server logs "Unsupported upgrade request" each time.
     */
    static HttpClient newHttpClient() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(RPC_TIMEOUT)
                .build();
    }

    private static final class Resync extends RuntimeException {}

    /** Test seam: queued ACK bodies when constructed with a recording sender. */
    static List<String> recordedAcks(AckSender sender) {
        return sender instanceof Recording r ? r.bodies : List.of();
    }

    /** Production ACK sender: POSTs each receipt to {@code POST /v2/agents/{id}/acks}. */
    private static final class HttpAckSender implements AckSender {
        private final URI uri;
        private volatile String token;

        HttpAckSender(URI uri, String token) {
            this.uri = uri;
            this.token = token;
        }

        @Override
        public void send(String body) throws Exception {
            HttpRequest request =
                    HttpRequest.newBuilder(uri)
                            .timeout(RPC_TIMEOUT)
                            .header("Authorization", "Bearer " + token)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build();
            HttpResponse<String> response =
                    newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400)
                throw new IOException("CP ack status " + response.statusCode());
        }
    }

    static final class Recording implements AckSender {
        final List<String> bodies = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void send(String body) {
            bodies.add(body);
        }
    }
}

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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Control-plane v2 push consumer for the game gate leg (plan CP-S5).
 *
 * <p>Replaces {@link GatePoller} polling once {@code BLOCKFIELD_CP_CONSUMER} is set: it bootstraps
 * from {@code GET /v2/agents/{id}/snapshot}, streams {@code GET /v2/agents/{id}/events} (SSE) and
 * confirms each frame with {@code POST /v2/agents/{id}/acks}. Wire contract is frozen at
 * control-plane origin/main {@code 6df1f95 + cf660e4}; this class consumes it and never invents an
 * alternative API.
 *
 * <p>Safety properties: fail closed before the first snapshot and past the lease (same 60 s lease
 * as the U1 poller, on a monotonic clock so wall steps cannot extend it); a stream with no frame or
 * keep-alive for three keep-alive intervals is aborted and reconnected; stale/forged/gapped or
 * unparseable frames never move the gate (they request a re-snapshot instead); non-executable and
 * non-gate frames advance the cursor without changing admission; ACKs are best-effort — a lost ACK
 * replays on reconnect and the cursor/epoch guards make the replay idempotent (a cursor that ran
 * ahead of the server's durable cursor self-heals through a {@code 409 snapshot_required}
 * re-snapshot).
 */
final class ControlSubscriber {
    /** Frames past this age without a fresh snapshot/apply close entry, like the U1 lease. */
    static final long LEASE_MS = GatePoller.LEASE_MS;

    private static final Duration RPC_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration STREAM_TIMEOUT = Duration.ofSeconds(75);
    private static final long RECONNECT_MS = 5_000;
    private static final long WARN_MS = 60_000;

    /** The CP heartbeats a healthy stream every 10 s; three missed beats mean a dead stream. */
    static final long IDLE_MS = 30_000;

    /** The admission lease, on the monotonic clock so wall steps cannot extend it. */
    static final long LEASE_NS = TimeUnit.MILLISECONDS.toNanos(LEASE_MS);

    static final long IDLE_NS = TimeUnit.MILLISECONDS.toNanos(IDLE_MS);

    /** What {@link #offer} did with one stream envelope. */
    enum Verdict {
        /** Gate and cursor advanced; ACKs sent. */
        APPLIED,
        /** Cursor advanced, gate unchanged (duplicate, held or rejected frame). */
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
    private final String token;
    private final Clock clock;
    private final LongSupplier monotonic;
    private final Consumer<String> warn;
    private final AckSender acks;

    private final Object lock = new Object();
    private String epoch;
    private String generation;
    private long cursor = -1;
    private Gate gate = Gate.closed();
    private volatile long freshAt = -LEASE_NS - 1;
    private volatile long lastWarnMs = -WARN_MS;
    private final boolean live;
    private volatile boolean stopped;
    private volatile Thread streamThread;
    private volatile InputStream liveBody;
    private volatile long lastLineAt;

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
            boolean live) {
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
    }

    /**
     * Null when v2 is not configured (no consumer): the caller keeps the U1 poll/file path.
     * Non-null but never-live when half-configured: the game leg stays closed, never falls back to
     * the file for game entry. Never throws.
     */
    static ControlSubscriber fromEnv(Logger logger) {
        ControlSubscriber sub =
                fromParts(
                        System.getenv("BLOCKFIELD_CP_URL"),
                        System.getenv("BLOCKFIELD_CP_TOKEN"),
                        System.getenv("BLOCKFIELD_CP_CONSUMER"),
                        logger,
                        System::currentTimeMillis,
                        logger::warn,
                        null);
        if (sub != null) sub.start();
        return sub;
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
        if (consumer == null || consumer.isBlank()) return null;
        AckSender unconfigured = sender != null ? sender : body -> {};
        if (!consumer.matches("[^\\s/]{1,128}")) {
            // A set but malformed id is a configuration error, not "v2 not configured":
            // pin game entry closed instead of falling back to the file.
            logger.warn("control-plane v2 consumer id invalid, game entry stays closed");
            return new ControlSubscriber(
                    consumer, null, null, null, "", clock, warn, unconfigured, monotonic, false);
        }
        if (url == null || url.isBlank() || token == null || token.isBlank()) {
            logger.warn(
                    "control-plane v2 consumer configured without URL/token: game entry stays"
                            + " closed");
            return new ControlSubscriber(
                    consumer, null, null, null, "", clock, warn, unconfigured, monotonic, false);
        }
        try {
            URI base = GatePoller.gateUri(url);
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
                    true);
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
                    false);
        }
    }

    void start() {
        if (!live) return;
        Thread thread = new Thread(this::loop, "cp-v2-stream");
        thread.setDaemon(true);
        streamThread = thread;
        thread.start();
    }

    /** Stops the stream thread; closing the body unblocks a read immediately. */
    void stop() {
        stopped = true;
        Thread thread = streamThread;
        if (thread != null) thread.interrupt();
        closeBody(liveBody);
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

    /** Current game-leg gate: last verified snapshot, closed past the lease or before it. */
    Gate current() {
        if (monotonic.getAsLong() - freshAt > LEASE_NS) return Gate.closed();
        synchronized (lock) {
            return gate;
        }
    }

    long cursor() {
        synchronized (lock) {
            return cursor;
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
        synchronized (lock) {
            epoch = parsed.epoch;
            generation = parsed.generation;
            cursor = parsed.cursor;
            gate = parsed.gate;
        }
        freshAt = monotonic.getAsLong();
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
        synchronized (lock) {
            if (epoch == null || !epoch.equals(envEpoch)) return Verdict.RESYNC;
            if (sequence <= cursor) {
                // Duplicate delivery on a live stream: the CP is reachable, so the lease holds.
                freshAt = monotonic.getAsLong();
                return Verdict.HELD;
            }
            if (sequence > cursor + 1) return Verdict.RESYNC;
            if (!generation.equals(envGeneration)) return Verdict.RESYNC;
        }
        String payloadHash = text(envelope, "payloadHash");
        // Revision only labels the ACK: a missing, fractional, or out-of-long one is null.
        Long revision = integral(envelope, "revision");
        if (!executable || envelope.get("reason") != null && !envelope.get("reason").isJsonNull()) {
            freshAt = monotonic.getAsLong();
            ack(envEpoch, sequence, envGeneration, revision, payloadHash, "received", "ok");
            advance(sequence);
            return Verdict.HELD;
        }
        if (payloadHash == null || !payloadHash.matches("[0-9a-f]{64}")) {
            // The change the CP sent cannot be verified or ACKed: re-snapshot instead of
            // skipping it, or replay would never bring it back.
            return Verdict.RESYNC;
        }
        if (expired(envelope)) {
            freshAt = monotonic.getAsLong();
            ack(envEpoch, sequence, envGeneration, revision, payloadHash, "rejected", "ok");
            advance(sequence);
            return Verdict.HELD;
        }
        Gate next = gateFromPayload(envelope.get("payload"));
        if (next == null) {
            freshAt = monotonic.getAsLong();
            ack(envEpoch, sequence, envGeneration, revision, payloadHash, "rejected", "ok");
            advance(sequence);
            return Verdict.HELD;
        }
        // State first: a stalled ACK endpoint must never delay the gate.
        synchronized (lock) {
            gate = next;
        }
        freshAt = monotonic.getAsLong();
        ack(envEpoch, sequence, envGeneration, revision, payloadHash, "received", "ok");
        ack(envEpoch, sequence, envGeneration, revision, payloadHash, "validated", "ok");
        ack(envEpoch, sequence, envGeneration, revision, payloadHash, "applied", "ok");
        advance(sequence);
        return Verdict.APPLIED;
    }

    // Callers apply the frame's state before its ACKs and advance last: a slow ACK
    // endpoint must never delay the gate, and a visible cursor N still implies the
    // receipts for N were already sent. ack() never throws.
    private void advance(long sequence) {
        synchronized (lock) {
            cursor = sequence;
        }
    }

    private void ack(
            String envEpoch,
            long sequence,
            String envGeneration,
            Long revision,
            String payloadHash,
            String state,
            String result) {
        if (payloadHash == null) return;
        String body =
                "{\"epoch\":\""
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

    /** Gate payload only; anything else (actions, other namespaces) is not ours to apply. */
    private static Gate gateFromPayload(JsonElement payload) {
        if (payload == null || !payload.isJsonObject()) return null;
        JsonObject obj = payload.getAsJsonObject();
        if (!"settings".equals(text(obj, "kind"))) return null;
        if (!"gate".equals(text(obj, "namespace"))) return null;
        // Only the game leg follows v2: another target's gate must never drive game entry.
        String target = text(obj, "target");
        if (!"*".equals(target) && !"game".equals(target)) return null;
        JsonElement value = obj.get("value");
        if (value == null || !value.isJsonObject()) return null;
        return Gate.fromCp(
                text(value.getAsJsonObject(), "mode"), text(value.getAsJsonObject(), "message"));
    }

    private record Snapshot(String epoch, String generation, long cursor, Gate gate) {}

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
        Gate found = null;
        int matches = 0;
        for (JsonElement el : json.getAsJsonArray("settings")) {
            if (!el.isJsonObject()) continue;
            JsonObject entry = el.getAsJsonObject();
            if (!"gate".equals(text(entry, "namespace"))) continue;
            String target = text(entry, "target");
            if (!"*".equals(target) && !"game".equals(target)) continue;
            JsonElement value = entry.get("value");
            if (value == null || !value.isJsonObject()) continue;
            found =
                    Gate.fromCp(
                            text(value.getAsJsonObject(), "mode"),
                            text(value.getAsJsonObject(), "message"));
            matches++;
        }
        // Ambiguous game legs pin closed instead of depending on array order.
        return new Snapshot(
                snapEpoch, snapGeneration, snapCursor, matches == 1 ? found : Gate.closed());
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

    private void warnOnce(String message) {
        long now = clock.now();
        if (now - lastWarnMs >= WARN_MS) {
            lastWarnMs = now;
            warn.accept(message);
        }
    }

    private void loop() {
        while (!stopped && !Thread.currentThread().isInterrupted()) {
            try {
                if (cursor() < 0) snapshot();
                stream();
            } catch (Resync e) {
                synchronized (lock) {
                    cursor = -1;
                }
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                warnOnce("control-plane v2 stream failed, keeping last gate: " + e);
            }
            if (stopped) return;
            try {
                Thread.sleep(RECONNECT_MS);
            } catch (InterruptedException e) {
                return;
            }
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
        return HttpClient.newBuilder().connectTimeout(RPC_TIMEOUT).build();
    }

    private static final class Resync extends RuntimeException {}

    /** Test seam: queued ACK bodies when constructed with a recording sender. */
    static List<String> recordedAcks(AckSender sender) {
        return sender instanceof Recording r ? r.bodies : List.of();
    }

    /** Production ACK sender: POSTs each receipt to {@code POST /v2/agents/{id}/acks}. */
    private static final class HttpAckSender implements AckSender {
        private final URI uri;
        private final String token;

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
                    HttpClient.newBuilder()
                            .connectTimeout(RPC_TIMEOUT)
                            .build()
                            .send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400)
                throw new IOException("CP ack status " + response.statusCode());
        }
    }

    static final class Recording implements AckSender {
        final List<String> bodies = new ArrayList<>();

        @Override
        public void send(String body) {
            bodies.add(body);
        }
    }
}

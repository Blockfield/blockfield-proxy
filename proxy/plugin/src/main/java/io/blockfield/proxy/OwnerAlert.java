package io.blockfield.proxy;

import com.google.gson.JsonObject;

import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Owner page for a stuck-closed game entry or an unreachable control plane (coordination#52: entry
 * stayed fail-closed ~7 h 20 min while the CP crashlooped and nobody noticed).
 *
 * <p>Watches the gate every scheduler tick and POSTs once when (a) the game leg reads closed
 * outside an announced CP maintenance for longer than {@link #ALERT_AFTER_MS}, or (b) the CP link
 * goes unfresh for that long; a recovery POST follows when the alerting condition clears. Alert
 * delivery never throws and never blocks the tick: sends run on a daemon thread, a failed send is
 * retried at most once a minute while its condition persists.
 *
 * <p>Configured with a single secret, {@code BLOCKFIELD_OWNER_ALERT_URL}: unset or blank means
 * fully inert (no threads, no traffic, no state). The URL may embed its own token (Discord, Slack
 * and ntfy-compatible endpoints take the same JSON shape: {@code text}, {@code content} and {@code
 * message} carry the same text); raw Telegram Bot API needs a relay for {@code chat_id}.
 */
final class OwnerAlert {
    /** A condition must hold this long, continuously, before the first POST. */
    static final long ALERT_AFTER_MS = TimeUnit.MINUTES.toMillis(2);

    /** Minimum gap between POST attempts while a send keeps failing. */
    private static final long RETRY_MS = TimeUnit.MINUTES.toMillis(1);

    private static final Duration RPC_TIMEOUT = Duration.ofSeconds(5);

    /** What the owner is being paged about; NONE means nothing to page about. */
    enum Condition {
        NONE,
        GATE_CLOSED,
        LINK_DOWN
    }

    /** Sends one alert text; prod POSTs it, tests record it. Failures are retried, never thrown. */
    interface Sender {
        void send(String text) throws Exception;
    }

    private final String url;
    private final long alertAfterMs;
    private final Logger logger;
    private final Sender sender;
    private final Consumer<Runnable> dispatch;

    private final Object lock = new Object();
    private Condition pending = Condition.NONE;
    private long pendingSince;
    private Condition alerting = Condition.NONE;
    private String unsent;
    private long lastAttemptMs;

    /** Inert unless the webhook URL is set: no threads, no traffic, gate logic untouched. */
    static OwnerAlert fromEnv(Logger logger) {
        String url = System.getenv("BLOCKFIELD_OWNER_ALERT_URL");
        if (url == null || url.isBlank()) return new OwnerAlert("", logger);
        return new OwnerAlert(
                url.strip(),
                ALERT_AFTER_MS,
                logger,
                text -> post(url.strip(), text),
                task -> {
                    Thread thread = new Thread(task, "owner-alert");
                    thread.setDaemon(true);
                    thread.start();
                });
    }

    // Inert instance: tick returns before touching any state.
    private OwnerAlert(String url, Logger logger) {
        this(
                url,
                ALERT_AFTER_MS,
                logger,
                text -> {
                    throw new AssertionError("inert alert must never send");
                },
                Runnable::run);
    }

    /** Seam for tests: explicit threshold, recording sender, synchronous dispatch. */
    OwnerAlert(
            String url,
            long alertAfterMs,
            Logger logger,
            Sender sender,
            Consumer<Runnable> dispatch) {
        this.url = url;
        this.alertAfterMs = alertAfterMs;
        this.logger = logger;
        this.sender = sender;
        this.dispatch = dispatch;
    }

    /** Scheduler entry point: classifies the live gate and advances the state machine. */
    void tick(ControlSubscriber sub, Gate gate, long nowMs) {
        boolean fresh = sub != null && sub.linkFresh();
        boolean maintenance = sub != null && sub.maintenanceAnnounced();
        boolean closed = gate != null && "closed".equals(gate.forBackend("game").state());
        tick(fresh, maintenance, closed, nowMs);
    }

    /** Pure state machine over already-classified facts; the seam unit tests drive this. */
    void tick(boolean linkFresh, boolean maintenance, boolean gameClosed, long nowMs) {
        if (url.isEmpty()) return;
        Condition seen = !linkFresh ? Condition.LINK_DOWN : Condition.NONE;
        if (seen == Condition.NONE && gameClosed && !maintenance) seen = Condition.GATE_CLOSED;
        String due = null;
        synchronized (lock) {
            if (seen != pending) {
                pending = seen;
                pendingSince = nowMs;
            }
            if (alerting != Condition.NONE && alerting != pending) {
                // Condition changed under an active page: close the old incident first;
                // the new one debounces from when it started.
                Condition done = alerting;
                alerting = Condition.NONE;
                unsent = null;
                due = recoveredText(done);
            } else if (pending != Condition.NONE
                    && alerting == Condition.NONE
                    && nowMs - pendingSince >= alertAfterMs) {
                alerting = pending;
                due = alertText(pending);
            } else if (alerting != Condition.NONE
                    && unsent != null
                    && nowMs - lastAttemptMs >= RETRY_MS) {
                // Earlier POST failed: retry at most once a minute while it persists.
                due = unsent;
            }
            if (due != null) {
                unsent = due;
                lastAttemptMs = nowMs;
            }
        }
        if (due != null) deliver(due);
    }

    private void deliver(String text) {
        dispatch.accept(
                () -> {
                    try {
                        sender.send(text);
                        synchronized (lock) {
                            if (text.equals(unsent)) unsent = null;
                        }
                    } catch (Exception e) {
                        // The tick retries at most once a minute while the condition persists.
                        logger.warn("owner alert POST failed, retrying: {}", e.toString());
                    }
                });
    }

    private static String alertText(Condition condition) {
        return switch (condition) {
            case LINK_DOWN ->
                    "Blockfield proxy: control plane unreachable for 2+ min, entry fail-closed"
                            + " (or fail-static). Check CP /health and its DB.";
            case GATE_CLOSED ->
                    "Blockfield proxy: game entry closed for 2+ min outside maintenance."
                            + " Check the CP gate mode.";
            case NONE -> throw new AssertionError("no text for no condition");
        };
    }

    private static String recoveredText(Condition condition) {
        return switch (condition) {
            case LINK_DOWN -> "Blockfield proxy recovered: control plane reachable again.";
            case GATE_CLOSED -> "Blockfield proxy recovered: game entry open again.";
            case NONE -> throw new AssertionError("no text for no condition");
        };
    }

    private static void post(String url, String text) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("text", text);
        body.addProperty("content", text);
        body.addProperty("message", text);
        HttpRequest request =
                HttpRequest.newBuilder(URI.create(url))
                        .timeout(RPC_TIMEOUT)
                        .header("Content-Type", "application/json")
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        body.toString(), StandardCharsets.UTF_8))
                        .build();
        HttpResponse<String> response =
                ControlSubscriber.newHttpClient()
                        .send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400)
            throw new java.io.IOException("owner alert status " + response.statusCode());
    }
}

package io.blockfield.proxy;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.slf4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Renews this service's control-plane credential before it expires (coordination#33): ~7 days ahead
 * (a quarter of the lifetime for short test TTLs), persists the successor to a 0600 state file and
 * only then confirms, which revokes the old token. The state file wins over the env token while it
 * descends from that env token; a re-issued env token starts a new chain.
 */
final class CredentialRenewer {
    static final Duration MAX_LEAD = Duration.ofDays(7);
    static final long RETRY_MS = 30_000;

    /** A 401 this soon after a renewal is replication lag, not a rejected successor. */
    static final long LAG_GRACE_MS = Duration.ofMinutes(10).toMillis();

    private static final long MAX_SLEEP_MS = Duration.ofHours(1).toMillis();
    private static final Duration RPC_TIMEOUT = Duration.ofSeconds(10);

    record Reply(int status, String body) {}

    /** One CP call with the given bearer; prod uses HTTP, tests a fake. */
    interface Transport {
        Reply call(String method, String path, String token)
                throws IOException, InterruptedException;
    }

    private final Transport cp;
    private final Path state;
    private final String envToken;
    private final Logger log;
    private final LongSupplier clockMs;
    private volatile Consumer<String> onToken = t -> {};
    private volatile String token;
    private boolean confirmPending;
    private long renewedAtMs;
    private volatile boolean stopped;
    private volatile Thread thread;

    CredentialRenewer(Transport cp, Path state, String envToken, Logger log, LongSupplier clockMs) {
        this.cp = cp;
        this.state = state;
        this.envToken = envToken == null ? "" : envToken.strip();
        this.log = log;
        this.clockMs = clockMs;
        JsonObject persisted = load(state, this.envToken, log);
        this.token = persisted != null ? persisted.get("token").getAsString() : this.envToken;
        // A persisted successor may be unconfirmed (crash before confirm); confirm is idempotent.
        this.confirmPending = persisted != null;
        this.renewedAtMs =
                persisted != null && persisted.has("renewedAt")
                        ? persisted.get("renewedAt").getAsLong()
                        : 0;
    }

    static CredentialRenewer http(String baseUrl, Path state, String envToken, Logger log) {
        URI base = URI.create(baseUrl.strip().replaceAll("/+$", ""));
        HttpClient client = HttpClient.newBuilder().connectTimeout(RPC_TIMEOUT).build();
        Transport transport =
                (method, path, bearer) -> {
                    HttpRequest request =
                            HttpRequest.newBuilder(base.resolve(path))
                                    .timeout(RPC_TIMEOUT)
                                    .header("Authorization", "Bearer " + bearer)
                                    .method(method, HttpRequest.BodyPublishers.noBody())
                                    .build();
                    HttpResponse<String> response =
                            client.send(request, HttpResponse.BodyHandlers.ofString());
                    return new Reply(response.statusCode(), response.body());
                };
        return new CredentialRenewer(transport, state, envToken, log, System::currentTimeMillis);
    }

    String token() {
        return token;
    }

    void onToken(Consumer<String> listener) {
        onToken = listener;
    }

    void start() {
        Thread t =
                new Thread(
                        () -> {
                            while (!stopped) {
                                long delay;
                                try {
                                    delay = tick();
                                } catch (InterruptedException e) {
                                    return;
                                } catch (RuntimeException | IOException e) {
                                    log.warn(
                                            "CP credential renewal check failed: {}", e.toString());
                                    delay = RETRY_MS;
                                }
                                try {
                                    Thread.sleep(delay);
                                } catch (InterruptedException e) {
                                    return;
                                }
                            }
                        },
                        "cp-credential-renew");
        t.setDaemon(true);
        thread = t;
        t.start();
    }

    void stop() {
        stopped = true;
        Thread t = thread;
        if (t != null) t.interrupt();
    }

    /** One renewal step; returns the delay in ms before the next one. */
    long tick() throws IOException, InterruptedException {
        if (token.isEmpty()) return MAX_SLEEP_MS;
        if (confirmPending) {
            int status = cp.call("POST", "/v2/credentials/renew/confirm", token).status();
            // 404: nothing pending for this token; 401: the /v2/me check below falls back.
            if (status == 200 || status == 404 || status == 401) confirmPending = false;
            else return RETRY_MS;
        }
        Reply me = cp.call("GET", "/v2/me", token);
        // Within the grace a 401 is replication lag and the env token is already revoked; the
        // renewal time lives in the state file so a restart during the lag keeps the successor.
        if (me.status() == 401
                && clockMs.getAsLong() - renewedAtMs > LAG_GRACE_MS
                && !token.equals(envToken)
                && !envToken.isEmpty()) {
            log.warn("persisted CP credential rejected, falling back to the env token");
            // Else a restart would adopt the rejected successor again.
            Files.deleteIfExists(state);
            adopt(envToken);
            return 0;
        }
        if (me.status() != 200) {
            log.warn("CP credential check status {}", me.status());
            return RETRY_MS;
        }
        JsonObject body = JsonParser.parseString(me.body()).getAsJsonObject();
        // A CP without renewal support (no expiry in /v2/me): check back hourly.
        if (!body.has("issuedAt") || !body.has("expiresAt")) return MAX_SLEEP_MS;
        Instant issued = instant(body, "issuedAt");
        Instant expires = instant(body, "expiresAt");
        long now = clockMs.getAsLong();
        long renewAt = renewAt(issued, expires).toEpochMilli();
        if (now < renewAt) return Math.max(1_000, Math.min(MAX_SLEEP_MS, renewAt - now));
        Reply renewed = cp.call("POST", "/v2/credentials/renew", token);
        if (renewed.status() != 201) {
            log.warn("CP credential renew status {}", renewed.status());
            return RETRY_MS;
        }
        String next =
                JsonParser.parseString(renewed.body()).getAsJsonObject().get("token").getAsString();
        // Persist before confirm: confirm revokes the old token, so a successor that only lives
        // in memory would be lost on restart. A failed write retries renew, which replays it.
        persist(state, next, envToken, now);
        renewedAtMs = now;
        adopt(next);
        confirmPending = true;
        log.info(
                "CP credential renewed, valid until {}",
                instant(JsonParser.parseString(renewed.body()).getAsJsonObject(), "expiresAt"));
        return 0;
    }

    private void adopt(String next) {
        token = next;
        onToken.accept(next);
    }

    static Instant renewAt(Instant issued, Instant expires) {
        Duration lead = Duration.between(issued, expires).dividedBy(4);
        // A skewed issuedAt after expiresAt gives a negative lead: renew at expiry, not after it.
        if (lead.isNegative()) lead = Duration.ZERO;
        if (lead.compareTo(MAX_LEAD) > 0) lead = MAX_LEAD;
        return expires.minus(lead);
    }

    private static Instant instant(JsonObject body, String key) {
        return OffsetDateTime.parse(body.get(key).getAsString()).toInstant();
    }

    static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The persisted state, or null when absent, unreadable or from another env token. */
    static JsonObject load(Path state, String envToken, Logger log) {
        if (state == null || !Files.isRegularFile(state)) return null;
        try {
            JsonObject json = JsonParser.parseString(Files.readString(state)).getAsJsonObject();
            String token = json.get("token").getAsString();
            if (!json.get("base").getAsString().equals(sha256(envToken))) {
                log.info("CP credential state file predates the env token, using env");
                return null;
            }
            return token.isBlank() ? null : json;
        } catch (IOException | RuntimeException e) {
            log.warn("CP credential state file unreadable, using env: {}", e.toString());
            return null;
        }
    }

    static void persist(Path state, String token, String envToken, long renewedAtMs)
            throws IOException {
        JsonObject json = new JsonObject();
        json.addProperty("token", token);
        json.addProperty("base", sha256(envToken));
        json.addProperty("renewedAt", renewedAtMs);
        Path dir = state.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path temp =
                Files.createTempFile(
                        dir,
                        ".cp-credential",
                        ".tmp",
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rw-------")));
        try {
            Files.writeString(temp, json.toString());
            try {
                Files.move(
                        temp,
                        state,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, state, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}

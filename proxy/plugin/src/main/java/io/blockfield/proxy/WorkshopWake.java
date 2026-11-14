package io.blockfield.proxy;

import com.google.gson.JsonObject;

import org.slf4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Cold-Workshop wake over the control plane: {@code POST /v2/workshop/wake} with the proxy's
 * service credential (scopes {@code workshop.act} + {@code workshop.wake}), the connecting member
 * as actor, and a fresh idempotency key per connect attempt. The controller verifies the actor
 * against the Workshop ACL; the proxy pre-checks membership locally to avoid pointless calls. Null
 * when not fully configured (no URL, credential, or controller generation): the proxy then shows
 * the waking message with no POST, never a file fallback.
 */
final class WorkshopWake {
    /** Bounded so a slow CP never holds the scheduler thread past the connect budget. */
    static final Duration RPC_TIMEOUT = Duration.ofSeconds(5);

    static final int EXPIRES_IN_SECONDS = 30;

    /** One wake POST; prod POSTs it, tests record it. Failures are reported, never thrown. */
    interface Transport {
        int post(String body) throws IOException, InterruptedException;
    }

    private final URI uri;
    private final Supplier<String> token;
    private final String consumer;
    private final String generation;
    private final String environment;
    private final Transport transport;
    private final Logger logger;

    WorkshopWake(
            URI uri,
            Supplier<String> token,
            String consumer,
            String generation,
            String environment,
            Transport transport,
            Logger logger) {
        this.uri = uri;
        this.token = token;
        this.consumer = consumer;
        this.generation = generation;
        this.environment = environment;
        this.transport = transport;
        this.logger = logger;
    }

    /**
     * Null when wake is not fully configured: no CP URL, no controller generation, or an invalid
     * URL. The proxy then shows the waking message with no POST. Never throws.
     */
    static WorkshopWake fromEnv(Logger logger, Supplier<String> token) {
        String url = System.getenv("BLOCKFIELD_CP_URL");
        String consumer = consumerFromEnv();
        String generation = generationFromEnv();
        if (generation == null) {
            logger.warn(
                    "workshop wake off: BLOCKFIELD_CP_WORKSHOP_CONSUMER_GENERATION is not a UUID");
            return null;
        }
        if (url == null || url.isBlank() || token == null) return null;
        URI uri;
        try {
            uri = wakeUri(url);
        } catch (IllegalArgumentException e) {
            logger.warn("workshop wake off: {}", e.getMessage());
            return null;
        }
        if (consumer == null) {
            logger.warn("workshop wake off: consumer id invalid");
            return null;
        }
        return new WorkshopWake(
                uri, token, consumer, generation, environmentFromEnv(), http(uri, token), logger);
    }

    void send(String idempotencyKey, String draslId, String name) {
        String body = body(idempotencyKey, environment, consumer, generation, draslId, name);
        String bearer = token.get();
        if (bearer == null || bearer.isBlank()) {
            logger.warn("Workshop wake skipped: no control-plane credential");
            return;
        }
        try {
            int status = transport.post(body);
            if (status / 100 != 2)
                logger.warn("Workshop wake rejected: control-plane status {}", status);
        } catch (Exception e) {
            logger.warn("Workshop wake failed", e);
        }
    }

    /** Wake request body: the CP-validated fields, nothing else (contract section 2, kind wake). */
    static String body(
            String idempotencyKey,
            String environment,
            String consumer,
            String generation,
            String draslId,
            String name) {
        JsonObject actor = new JsonObject();
        actor.addProperty("draslId", draslId);
        actor.addProperty("name", name);
        JsonObject request = new JsonObject();
        request.addProperty("idempotencyKey", idempotencyKey);
        request.addProperty("environment", environment);
        request.addProperty("backend", "game");
        request.addProperty("consumerId", consumer);
        request.addProperty("generation", generation);
        request.addProperty("target", "*");
        request.add("actor", actor);
        request.addProperty("expiresInSeconds", EXPIRES_IN_SECONDS);
        return request.toString();
    }

    /**
     * Normalized control-plane wake URI. A trailing slash must not double the path, and a path
     * prefix, query or fragment must not move it elsewhere (same rule as the gate URI). Throws
     * IllegalArgumentException on malformed input.
     */
    static URI wakeUri(String raw) {
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
                    "/v2/workshop/wake",
                    null,
                    null);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("invalid control-plane URL: " + base, e);
        }
    }

    private static Transport http(URI uri, Supplier<String> token) {
        return body -> {
            HttpRequest request =
                    HttpRequest.newBuilder(uri)
                            .timeout(RPC_TIMEOUT)
                            .header("Authorization", "Bearer " + token.get())
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build();
            // The request timeout stops at response headers; bound the whole
            // exchange, body included, and abort it so no scheduler task stalls.
            CompletableFuture<HttpResponse<Void>> exchange =
                    ControlSubscriber.newHttpClient()
                            .sendAsync(request, HttpResponse.BodyHandlers.discarding());
            try {
                return exchange.get(RPC_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).statusCode();
            } catch (TimeoutException e) {
                exchange.cancel(true);
                throw new IOException("control-plane wake timed out", e);
            } catch (ExecutionException e) {
                throw new IOException("control-plane wake failed", e.getCause());
            }
        };
    }

    private static String consumerFromEnv() {
        String raw = System.getenv("BLOCKFIELD_CP_WORKSHOP_CONSUMER_ID");
        String consumer = raw == null || raw.isBlank() ? "workshop" : raw.strip();
        return consumer.matches("[^\\s/]{1,128}") ? consumer : null;
    }

    private static String generationFromEnv() {
        String raw = System.getenv("BLOCKFIELD_CP_WORKSHOP_CONSUMER_GENERATION");
        if (raw == null || raw.isBlank()) return null;
        try {
            return UUID.fromString(raw.strip()).toString();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String environmentFromEnv() {
        String raw = System.getenv("BLOCKFIELD_CP_ENV");
        String environment = raw == null || raw.isBlank() ? "prod" : raw.strip();
        return environment.length() > 64 ? environment.substring(0, 64) : environment;
    }
}

package io.blockfield.proxy;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Вход из control-plane (U1): опрашивает {@code GET /v1/gate} в фоне и держит in-memory проекцию.
 * Без свежего ответа дольше лизы (60 с) вход закрыт (fail closed); старые сессии остаются. Без
 * BLOCKFIELD_CP_URL прокси работает по gate-файлу, как раньше.
 */
final class GatePoller {
    static final long POLL_MS = 5_000;
    static final long LEASE_MS = 60_000;
    static final long TIMEOUT_MS = 2_000;

    interface Fetcher {
        String fetch() throws Exception;
    }

    interface Clock {
        long now();
    }

    private final Fetcher fetcher;
    private final Clock clock;
    private volatile Gate gate = Gate.closed();
    private volatile long freshAt = 0;

    GatePoller(Fetcher fetcher, Clock clock) {
        this.fetcher = fetcher;
        this.clock = clock;
    }

    /** Null, когда CP не настроен: поведение по файлу не меняется. */
    static GatePoller fromEnv() {
        String url = System.getenv("BLOCKFIELD_CP_URL");
        String token = System.getenv("BLOCKFIELD_CP_TOKEN");
        if (url == null || url.isBlank() || token == null || token.isBlank()) return null;
        HttpClient http =
                HttpClient.newBuilder().connectTimeout(Duration.ofMillis(TIMEOUT_MS)).build();
        HttpRequest request =
                HttpRequest.newBuilder(URI.create(url.strip() + "/v1/gate"))
                        .timeout(Duration.ofMillis(TIMEOUT_MS))
                        .header("Authorization", "Bearer " + token)
                        .GET()
                        .build();
        GatePoller poller =
                new GatePoller(
                        () -> {
                            HttpResponse<String> response =
                                    http.send(request, HttpResponse.BodyHandlers.ofString());
                            if (response.statusCode() != 200)
                                throw new IOException("CP status " + response.statusCode());
                            return response.body();
                        },
                        System::currentTimeMillis);
        Thread thread = new Thread(poller::loop, "cp-gate-poll");
        thread.setDaemon(true);
        thread.start();
        return poller;
    }

    void loop() {
        while (!Thread.currentThread().isInterrupted()) {
            pollOnce();
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** Один опрос; возвращает зафиксированную проекцию. Шов для тестов (без потоков). */
    Gate pollOnce() {
        try {
            Gate next = parse(fetcher.fetch());
            if (next != null) {
                gate = next;
                freshAt = clock.now();
            }
        } catch (Exception ignored) {
            // Держим последнюю проекцию: вход закроет лиза, а не один сбой.
        }
        return current();
    }

    Gate current() {
        return clock.now() - freshAt > LEASE_MS ? Gate.closed() : gate;
    }

    /** Null на битом/незнакомом ответе: проекция не двигается до свежего ответа. */
    static Gate parse(String body) {
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            if (json.get("schema") == null
                    || !json.get("schema").isJsonPrimitive()
                    || !json.get("schema").getAsJsonPrimitive().isNumber()
                    || !json.get("schema").getAsString().equals("1")) return null;
            if (json.get("mode") == null || !json.get("mode").isJsonPrimitive()) return null;
            String mode = json.get("mode").getAsString();
            String message =
                    json.get("message") != null && json.get("message").isJsonPrimitive()
                            ? json.get("message").getAsString()
                            : "";
            if (!mode.equals("open") && !mode.equals("draining") && !mode.equals("maintenance"))
                return null;
            return Gate.fromCp(mode, message);
        } catch (RuntimeException e) {
            return null;
        }
    }
}

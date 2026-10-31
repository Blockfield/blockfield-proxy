package io.blockfield.proxy;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.slf4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * Вход из control-plane (U1): опрашивает {@code GET /v1/gate} в фоне и держит in-memory проекцию.
 * Без свежего ответа дольше лизы (60 с) вход закрыт (fail closed); старые сессии остаются. Без
 * BLOCKFIELD_CP_URL прокси работает по gate-файлу, как раньше.
 */
final class GatePoller {
    static final long POLL_MS = 5_000;
    static final long LEASE_MS = 60_000;
    static final long TIMEOUT_MS = 2_000;
    static final long WARN_MS = 60_000;

    interface Fetcher {
        String fetch() throws Exception;
    }

    interface Clock {
        long now();
    }

    private final Fetcher fetcher;
    private final Clock clock;
    private final Consumer<String> warn;
    private volatile Gate gate = Gate.closed();
    private volatile long freshAt = 0;
    private volatile long lastWarnMs = 0;

    GatePoller(Fetcher fetcher, Clock clock) {
        this(fetcher, clock, message -> {});
    }

    GatePoller(Fetcher fetcher, Clock clock, Consumer<String> warn) {
        this.fetcher = fetcher;
        this.clock = clock;
        this.warn = warn;
    }

    /**
     * Normalized control-plane gate URI. A trailing slash (or whitespace) must not turn the poll
     * path into {@code //v1/gate}; throws IllegalArgumentException on malformed input.
     */
    static URI gateUri(String raw) {
        String base = raw.strip().replaceAll("/+$", "");
        return URI.create(base + "/v1/gate");
    }

    /**
     * Null, когда CP не настроен: поведение по файлу не меняется. Никогда не бросает: битый URL
     * даёт опросник, который всегда закрыт (fail closed), а не роняет весь плагин.
     */
    static GatePoller fromEnv(Logger logger) {
        return fromParts(
                System.getenv("BLOCKFIELD_CP_URL"), System.getenv("BLOCKFIELD_CP_TOKEN"), logger);
    }

    /** Шов для тестов: та же логика без чтения окружения. */
    static GatePoller fromParts(String url, String token, Logger logger) {
        if (url == null || url.isBlank() || token == null || token.isBlank()) return null;
        Fetcher fetcher;
        try {
            HttpRequest request =
                    HttpRequest.newBuilder(gateUri(url))
                            .timeout(Duration.ofMillis(TIMEOUT_MS))
                            .header("Authorization", "Bearer " + token)
                            .GET()
                            .build();
            HttpClient http =
                    HttpClient.newBuilder().connectTimeout(Duration.ofMillis(TIMEOUT_MS)).build();
            fetcher =
                    () -> {
                        HttpResponse<String> response =
                                http.send(request, HttpResponse.BodyHandlers.ofString());
                        if (response.statusCode() != 200)
                            throw new IOException("CP status " + response.statusCode());
                        return response.body();
                    };
        } catch (IllegalArgumentException e) {
            // Typo в URL: вход закрыт и громко, плагин жив и gate-хуки на месте.
            fetcher =
                    () -> {
                        throw new IOException("invalid BLOCKFIELD_CP_URL: " + e.getMessage());
                    };
        }
        GatePoller poller = new GatePoller(fetcher, System::currentTimeMillis, logger::warn);
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
        } catch (Exception e) {
            // Держим последнюю проекцию: вход закроет лиза, а не один сбой. Первый сбой и
            // дальше не чаще раза в минуту — в лог, иначе инциденты CP не видны.
            long now = clock.now();
            if (now - lastWarnMs >= WARN_MS) {
                lastWarnMs = now;
                warn.accept("control-plane poll failed, keeping the last gate: " + e);
            }
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

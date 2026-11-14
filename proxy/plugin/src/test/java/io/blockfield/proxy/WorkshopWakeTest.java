package io.blockfield.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Workshop wake request: URI normalization, body shape, and failure containment. */
class WorkshopWakeTest {
    @Test
    void wakeUriIgnoresTrailingSlashAndPrefix() {
        assertEquals(
                URI.create("https://cp.internal:8443/v2/workshop/wake"),
                WorkshopWake.wakeUri("https://cp.internal:8443/prefix/?query#frag"));
        assertEquals(
                URI.create("http://cp.internal/v2/workshop/wake"),
                WorkshopWake.wakeUri("http://cp.internal/"));
    }

    @Test
    void wakeUriRejectsMalformedInput() {
        assertThrows(IllegalArgumentException.class, () -> WorkshopWake.wakeUri("not a url"));
        assertThrows(IllegalArgumentException.class, () -> WorkshopWake.wakeUri(""));
    }

    @Test
    void bodyHasExactlyTheContractFields() {
        JsonObject request =
                JsonParser.parseString(
                                WorkshopWake.body(
                                        "7f3a2a1e-9c4e-4b6e-9e6e-2e2e2e2e2e2e",
                                        "prod",
                                        "workshop",
                                        "123e4567-e89b-12d3-a456-426614174000",
                                        "abcdef0123456789abcdef0123456789",
                                        "Builder_1"))
                        .getAsJsonObject();
        assertEquals(
                Set.of(
                        "idempotencyKey",
                        "environment",
                        "backend",
                        "consumerId",
                        "generation",
                        "target",
                        "actor",
                        "expiresInSeconds"),
                request.keySet());
        assertEquals("prod", request.get("environment").getAsString());
        assertEquals("game", request.get("backend").getAsString());
        assertEquals(30, request.get("expiresInSeconds").getAsInt());
    }

    @Test
    void failedPostNeverThrows() {
        List<String> posts = new ArrayList<>();
        var wake =
                new WorkshopWake(
                        URI.create("https://cp.invalid/v2/workshop/wake"),
                        () -> "token",
                        "workshop",
                        "123e4567-e89b-12d3-a456-426614174000",
                        "prod",
                        body -> {
                            posts.add(body);
                            throw new java.io.IOException("down");
                        },
                        LoggerFactory.getLogger("test"));
        wake.send(
                "7f3a2a1e-9c4e-4b6e-9e6e-2e2e2e2e2e2e",
                "abcdef0123456789abcdef0123456789",
                "Builder");
        assertEquals(1, posts.size(), "the attempt still goes out once");
    }
}

package io.blockfield.proxy;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

class AuthenticationTest {
    @Test
    void draslChecksExactIdentityAndInvalidatesPasswordLoginToken() throws Exception {
        var status = new AtomicReference<>(200);
        var response =
                new AtomicReference<>(
                        "{\"name\":\"RigPeer\",\"id\":\"74b2f4aeceb14b2dabbfc5f41d0a8846\"}");
        var posted = new AtomicReference<String>();
        var path = new AtomicReference<String>();
        var query = new AtomicReference<String>();
        var invalidated = new CompletableFuture<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    String request = exchange.getRequestURI().getPath();
                    String body =
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8);
                    if (request.equals("/auth/invalidate")) {
                        invalidated.complete(body);
                    } else {
                        path.set(request);
                        query.set(exchange.getRequestURI().getRawQuery());
                        posted.set(body);
                    }
                    byte[] data = response.get().getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(status.get(), data.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(data);
                    }
                    exchange.close();
                });
        server.start();
        try {
            Authentication authentication =
                    new Authentication(
                            this,
                            null,
                            LoggerFactory.getLogger("test"),
                            new LoginForwarding(),
                            "http://127.0.0.1:" + server.getAddress().getPort(),
                            null);
            assertEquals(
                    "74b2f4aeceb14b2dabbfc5f41d0a8846",
                    authentication.hasJoined("RigPeer", "challenge").get(5, TimeUnit.SECONDS));
            assertEquals("/session/minecraft/hasJoined", path.get());
            assertEquals("username=RigPeer&serverId=challenge", query.get());
            assertNull(
                    authentication.profileId("rigpeer").get(5, TimeUnit.SECONDS),
                    "profile name is case-sensitive");
            status.set(403);
            assertNull(authentication.hasJoined("RigPeer", "challenge").get(5, TimeUnit.SECONDS));
            assertEquals(
                    "Неверный пароль.",
                    authentication
                            .authenticate("RigPeer", "fixture-password")
                            .get(5, TimeUnit.SECONDS));

            status.set(200);
            response.set(
                    "{\"accessToken\":\"fixture-access\",\"clientToken\":\"fixture-client\",\"selectedProfile\":{\"name\":\"RigPeer\"}}");
            assertNull(
                    authentication
                            .authenticate("RigPeer", "fixture-password")
                            .get(5, TimeUnit.SECONDS));
            assertEquals("/auth/authenticate", path.get());
            var login = JsonParser.parseString(posted.get()).getAsJsonObject();
            assertEquals("RigPeer", login.get("username").getAsString());
            assertEquals("fixture-password", login.get("password").getAsString());
            var revoked =
                    JsonParser.parseString(invalidated.get(5, TimeUnit.SECONDS)).getAsJsonObject();
            assertEquals("fixture-access", revoked.get("accessToken").getAsString());
            assertEquals("fixture-client", revoked.get("clientToken").getAsString());
            assertTrue(
                    authentication
                            .authenticate("rigpeer", "fixture-password")
                            .get(5, TimeUnit.SECONDS)
                            .contains("RigPeer"));

            status.set(201);
            response.set("{}");
            assertNull(
                    authentication
                            .register("RigPeer", "fixture-password")
                            .get(5, TimeUnit.SECONDS));
            assertEquals("/drasl/api/v3/users", path.get());
            assertEquals(
                    "RigPeer",
                    JsonParser.parseString(posted.get())
                            .getAsJsonObject()
                            .get("playerName")
                            .getAsString());
            status.set(409);
            response.set("{\"message\":\"already registered\"}");
            assertTrue(
                    authentication
                            .register("RigPeer", "fixture-password")
                            .get(5, TimeUnit.SECONDS)
                            .contains("already registered"));
            response.set("not json");
            assertTrue(
                    authentication
                            .register("RigPeer", "fixture-password")
                            .get(5, TimeUnit.SECONDS)
                            .contains("409"));
        } finally {
            server.stop(0);
        }
    }
}

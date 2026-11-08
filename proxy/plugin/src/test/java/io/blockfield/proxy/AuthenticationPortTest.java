package io.blockfield.proxy;

import static io.blockfield.proxy.GateEvictionTest.fake;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import com.velocitypowered.api.event.Continuation;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.player.GameProfileRequestEvent;
import com.velocitypowered.api.proxy.LoginPhaseConnection;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.util.GameProfile;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * PX74-C2: the inbound port is per-connection until the Drasl gate passes, never nickname-keyed.
 */
class AuthenticationPortTest {
    private static final String ACCOUNT = "74b2f4aeceb14b2dabbfc5f41d0a8846";
    private static final UUID ACCOUNT_UUID =
            UUID.fromString("74b2f4ae-ceb1-4b2d-abbf-c5f41d0a8846");
    private static final byte[] SECRET = Sessions.secret("fixture-forwarding-secret");

    @Test
    void unauthenticatedPreLoginsLeaveNoRoutingState() throws Exception {
        try (Fixture fixture = new Fixture()) {
            for (int i = 0; i < 5; i++) {
                String nick = "Ghost" + i + "x";
                fixture.preLogin(
                        nick,
                        new InetSocketAddress("127.0.0.1", 40000 + i),
                        true,
                        25566,
                        challenge -> new byte[] {0});
                assertNull(
                        fixture.authentication.inboundPort(nick),
                        "PreLogin must not publish routing state for " + nick);
            }
            fixture.authentication.expire();
            for (int i = 0; i < 5; i++) {
                assertNull(
                        fixture.authentication.inboundPort("Ghost" + i + "x"),
                        "abandoned PreLogins must leave no routing state after expiry");
            }
        }
    }

    @Test
    void secondConnectionCannotStealNicknamePortBeforeLoginRoutes() throws Exception {
        try (Fixture fixture = new Fixture()) {
            String nick = "WsMember";
            InetSocketAddress first = new InetSocketAddress("127.0.0.1", 43210);
            InetSocketAddress second = new InetSocketAddress("127.0.0.1", 43211);
            GameProfileRequestEvent profile =
                    fixture.preLogin(nick, first, true, 25567, challenge -> proof(nick, challenge));
            // Another connection reuses the nickname over TCP before the first login routes.
            fixture.preLogin(nick, second, false, 25565, challenge -> new byte[] {0});
            assertNull(
                    fixture.authentication.inboundPort(nick),
                    "a second PreLogin must not change the first connection's port");
            await(fixture.authentication.onProfile(profile));
            Player player = fixture.player(nick, ACCOUNT_UUID, first);
            await(fixture.authentication.onLogin(new LoginEvent(player)));
            assertTrue(fixture.authentication.authenticated(player));
            assertEquals(
                    Integer.valueOf(25567),
                    fixture.authentication.inboundPort(nick),
                    "the gated login publishes its own connection's port");
        }
    }

    private static byte[] proof(String nick, String challenge) {
        String[] session = Sessions.issue(SECRET, nick, ACCOUNT, System.currentTimeMillis() / 1000);
        return replyFor(session[0], session[1], challenge);
    }

    private static byte[] replyFor(String jwt, String key, String challenge) {
        String hmac =
                HexFormat.of().formatHex(Sessions.hmac(HexFormat.of().parseHex(key), challenge));
        return Authentication.concat(
                new byte[] {2},
                Authentication.concat(Authentication.utf(jwt), Authentication.utf(hmac)));
    }

    private static void await(EventTask task) throws Exception {
        if (task == null) return;
        var done = new CompletableFuture<Void>();
        task.execute(
                new Continuation() {
                    public void resume() {
                        done.complete(null);
                    }

                    public void resumeWithException(Throwable failure) {
                        done.completeExceptionally(failure);
                    }
                });
        done.get(10, TimeUnit.SECONDS);
    }

    private static final class Fixture implements AutoCloseable {
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final AtomicReference<String> name = new AtomicReference<>("?");
        final Authentication authentication;

        Fixture() throws Exception {
            server.createContext(
                    "/",
                    exchange -> {
                        String response =
                                "{\"name\":\"" + name.get() + "\",\"id\":\"" + ACCOUNT + "\"}";
                        byte[] body = response.getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, body.length);
                        try (var output = exchange.getResponseBody()) {
                            output.write(body);
                        }
                        exchange.close();
                    });
            server.start();
            authentication =
                    new Authentication(
                            this,
                            null,
                            LoggerFactory.getLogger("test"),
                            new LoginForwarding(),
                            "http://127.0.0.1:" + server.getAddress().getPort(),
                            SECRET);
        }

        GameProfileRequestEvent preLogin(
                String nick,
                InetSocketAddress remote,
                boolean raknet,
                int localPort,
                Function<String, byte[]> response) {
            name.set(nick);
            LoginPhaseConnection connection =
                    RaknetOnlyTest.overTransport(
                            LoginPhaseConnection.class,
                            fake(
                                    LoginPhaseConnection.class,
                                    (method, args) -> {
                                        if (method.equals("getRemoteAddress")) return remote;
                                        if (method.equals("sendLoginPluginMessage")) {
                                            byte[] reply = new byte[] {0};
                                            if (args[0].equals(Authentication.AUTH)) {
                                                ByteBuffer challenge =
                                                        ByteBuffer.wrap((byte[]) args[1]);
                                                int length = challenge.get();
                                                byte[] text = new byte[length];
                                                challenge.get(text);
                                                reply =
                                                        response.apply(
                                                                new String(
                                                                        text,
                                                                        StandardCharsets.UTF_8));
                                            }
                                            ((LoginPhaseConnection.MessageConsumer) args[2])
                                                    .onMessageResponse(reply);
                                        }
                                        return null;
                                    }),
                            raknet,
                            new InetSocketAddress("127.0.0.1", localPort));
            authentication.onPreLogin(new PreLoginEvent(connection, nick));
            return new GameProfileRequestEvent(
                    connection, GameProfile.forOfflinePlayer(nick), false);
        }

        Player player(String nick, UUID id, InetSocketAddress remote) {
            return fake(
                    Player.class,
                    (method, args) ->
                            switch (method) {
                                case "getUniqueId" -> id;
                                case "getUsername" -> nick;
                                case "getRemoteAddress" -> remote;
                                default -> null;
                            });
        }

        public void close() {
            server.stop(0);
        }
    }
}

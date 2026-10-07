package io.blockfield.proxy;

import static io.blockfield.proxy.GateEvictionTest.fake;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Continuation;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.player.GameProfileRequestEvent;
import com.velocitypowered.api.proxy.LoginPhaseConnection;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.Scheduler;
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

class AuthenticationIdentityTest {
    private static final String NAME = "RigPeer";
    private static final String ACCOUNT = "74b2f4aeceb14b2dabbfc5f41d0a8846";
    private static final byte[] SECRET = Sessions.secret("fixture-forwarding-secret");

    @Test
    void tokenAndRememberedProofSetTheForwardedUuidBeforeLogin() throws Exception {
        try (Fixture fixture = new Fixture()) {
            for (boolean remembered : new boolean[] {false, true}) {
                var login =
                        fixture.login(
                                remembered
                                        ? AuthenticationIdentityTest::proof
                                        : challenge -> new byte[] {1});
                assertEquals(ACCOUNT, login.profile().getGameProfile().getUndashedId());
                assertNotEquals(
                        GameProfile.forOfflinePlayer(NAME).getId(), login.player().getUniqueId());
                assertTrue(fixture.authentication.authenticated(login.player()));
                assertEquals(
                        ACCOUNT, fixture.authentication.profileId(login.player().getUniqueId()));
            }
        }
    }

    @Test
    void unverifiedOrInvalidIdentityNeverChangesOfflineUuidOrAuthenticates() throws Exception {
        try (Fixture fixture = new Fixture()) {
            for (Function<String, byte[]> response :
                    java.util.List.<Function<String, byte[]>>of(
                            challenge -> new byte[] {0}, challenge -> proof("other-challenge"))) {
                var login = fixture.login(response);
                assertEquals(
                        GameProfile.forOfflinePlayer(NAME).getId(), login.player().getUniqueId());
                assertFalse(fixture.authentication.authenticated(login.player()));
            }
            for (String invalid :
                    new String[] {
                        "{\"name\":\"rigpeer\",\"id\":\"" + ACCOUNT + "\"}",
                        "{\"name\":\"RigPeer\",\"id\":\"not-a-uuid\"}",
                        "{\"name\":\"RigPeer\",\"id\":\"--" + ACCOUNT + "\"}",
                        "{}"
                    }) {
                fixture.profile.set(invalid);
                for (Function<String, byte[]> response :
                        java.util.List.<Function<String, byte[]>>of(
                                AuthenticationIdentityTest::proof, challenge -> new byte[] {1})) {
                    var login = fixture.login(response);
                    assertEquals(
                            GameProfile.forOfflinePlayer(NAME).getId(),
                            login.player().getUniqueId());
                    assertFalse(fixture.authentication.authenticated(login.player()));
                }
            }
            fixture.profile.set("{\"name\":\"RigPeer\",\"id\":\"" + ACCOUNT + "\"}");
            var login = fixture.begin(challenge -> new byte[] {1});
            await(fixture.authentication.onProfile(login));
            Player tampered = fixture.player(GameProfile.forOfflinePlayer(NAME).getId());
            await(fixture.authentication.onLogin(new LoginEvent(tampered)));
            assertFalse(
                    fixture.authentication.authenticated(tampered),
                    "LoginEvent must require final UUID equality");
        }
    }

    @Test
    void rememberedProofCannotFollowNicknameToAReplacementAccount() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.profile.set(
                    "{\"name\":\"RigPeer\",\"id\":\"5288bba02d4a4c048ff6c7ab862e151c\"}");
            var login = fixture.login(AuthenticationIdentityTest::proof);
            assertEquals(GameProfile.forOfflinePlayer(NAME).getId(), login.player().getUniqueId());
            assertFalse(fixture.authentication.authenticated(login.player()));
            assertNull(fixture.authentication.profileId(login.player().getUniqueId()));
        }
    }

    @Test
    void passwordLoginSavesProofButCannotAdmitAnOfflineUuid() throws Exception {
        try (Fixture fixture = new Fixture()) {
            for (byte[] secret : new byte[][] {SECRET, null}) {
                var sent = new AtomicReference<byte[]>();
                var finished = new CompletableFuture<Void>();
                var admitted = new java.util.concurrent.atomic.AtomicBoolean();
                UUID offline = GameProfile.forOfflinePlayer(NAME).getId();
                Player player =
                        fake(
                                Player.class,
                                (method, args) ->
                                        switch (method) {
                                            case "getUniqueId" -> offline;
                                            case "getUsername" -> NAME;
                                            case "isActive" -> true;
                                            case "sendPluginMessage" -> {
                                                sent.set((byte[]) args[1]);
                                                yield true;
                                            }
                                            case "sendMessage" -> {
                                                finished.complete(null);
                                                yield null;
                                            }
                                            default -> null;
                                        });
                Authentication authentication =
                        new Authentication(
                                this,
                                null,
                                LoggerFactory.getLogger("test"),
                                new LoginForwarding(),
                                "http://127.0.0.1:" + fixture.server.getAddress().getPort(),
                                secret);
                var admit =
                        Authentication.class.getDeclaredMethod(
                                "admit",
                                Player.class,
                                String.class,
                                java.util.function.Consumer.class);
                admit.setAccessible(true);
                admit.invoke(
                        authentication,
                        player,
                        ACCOUNT,
                        (java.util.function.Consumer<Player>) ignored -> admitted.set(true));
                finished.get(5, TimeUnit.SECONDS);
                assertFalse(admitted.get());
                assertFalse(authentication.authenticated(player));
                assertNull(authentication.profileId(offline));
                if (secret == null) {
                    assertNull(sent.get(), "missing secret cannot issue a reconnect proof");
                } else {
                    ByteBuffer payload = ByteBuffer.wrap(sent.get());
                    String jwt = readUtf(payload);
                    String key = readUtf(payload);
                    assertFalse(payload.hasRemaining());
                    String challenge = "next-connection-challenge";
                    byte[] reply = replyFor(jwt, key, challenge);
                    assertEquals(
                            ACCOUNT,
                            Sessions.verify(
                                    SECRET,
                                    NAME,
                                    challenge,
                                    reply,
                                    System.currentTimeMillis() / 1000));
                    assertEquals(
                            ACCOUNT,
                            fixture.login(next -> replyFor(jwt, key, next))
                                    .profile()
                                    .getGameProfile()
                                    .getUndashedId());
                }
            }
        }
    }

    @Test
    void sameIdentityPasswordReloginAlsoRestoresRememberedSession() throws Exception {
        try (Fixture fixture = new Fixture()) {
            var sent = new AtomicReference<byte[]>();
            var admitted = new CompletableFuture<Void>();
            UUID id = UUID.fromString("74b2f4ae-ceb1-4b2d-abbf-c5f41d0a8846");
            Player player =
                    fake(
                            Player.class,
                            (method, args) ->
                                    switch (method) {
                                        case "getUniqueId" -> id;
                                        case "getUsername" -> NAME;
                                        case "isActive" -> true;
                                        case "sendPluginMessage" -> {
                                            sent.set((byte[]) args[1]);
                                            yield true;
                                        }
                                        default -> null;
                                    });
            var admit =
                    Authentication.class.getDeclaredMethod(
                            "admit", Player.class, String.class, java.util.function.Consumer.class);
            admit.setAccessible(true);
            admit.invoke(
                    fixture.authentication,
                    player,
                    ACCOUNT,
                    (java.util.function.Consumer<Player>) ignored -> admitted.complete(null));
            admitted.get(5, TimeUnit.SECONDS);
            assertTrue(fixture.authentication.authenticated(player));
            ByteBuffer payload = ByteBuffer.wrap(sent.get());
            String jwt = readUtf(payload), key = readUtf(payload);
            byte[] proof = replyFor(jwt, key, "new-challenge");
            assertEquals(
                    ACCOUNT,
                    Sessions.verify(
                            SECRET,
                            NAME,
                            "new-challenge",
                            proof,
                            System.currentTimeMillis() / 1000));
        }
    }

    @Test
    void passwordProvenUuidCannotFollowNicknameReassignment() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.profile.set(
                    "{\"name\":\"RigPeer\",\"id\":\"5288bba02d4a4c048ff6c7ab862e151c\"}");
            var packets = new java.util.ArrayList<byte[]>();
            var finished = new CompletableFuture<Void>();
            Player player = fixture.passwordPlayer(packets, finished);
            fixture.commands.get("login").execute(invocation(player, "fixture-password"));
            finished.get(5, TimeUnit.SECONDS);
            assertTrue(
                    packets.isEmpty(), "password UUID A cannot mint a session for lookup UUID B");
            assertFalse(fixture.authentication.authenticated(player));
            assertEquals(0, fixture.admissions.get());
        }
    }

    @Test
    void firstPasswordLoginCanLogoutBeforeReconnectWithoutAdmission() throws Exception {
        try (Fixture fixture = new Fixture()) {
            var packets = new java.util.ArrayList<byte[]>();
            var finished = new CompletableFuture<Void>();
            Player player = fixture.passwordPlayer(packets, finished);
            fixture.commands.get("login").execute(invocation(player, "fixture-password"));
            finished.get(5, TimeUnit.SECONDS);
            assertEquals(1, packets.size());
            assertFalse(fixture.authentication.authenticated(player));
            SimpleCommand logout = fixture.commands.get("logout");
            assertTrue(logout.hasPermission(invocation(player)));
            logout.execute(invocation(player));
            assertEquals(2, packets.size());
            assertArrayEquals(
                    Authentication.concat(Authentication.utf(""), Authentication.utf("")),
                    packets.get(1));
            assertFalse(fixture.authentication.authenticated(player));
            assertEquals(0, fixture.admissions.get());
            assertFalse(
                    fixture.commands
                            .get("login")
                            .hasPermission(invocation(player, "fixture-password")));
            fixture.disconnect.get().run();
            var reconnect = fixture.login(challenge -> new byte[] {0});
            assertFalse(fixture.authentication.authenticated(reconnect.player()));
            assertEquals(
                    GameProfile.forOfflinePlayer(NAME).getId(), reconnect.player().getUniqueId());
        }
    }

    private static SimpleCommand.Invocation invocation(Player player, String... arguments) {
        return fake(
                SimpleCommand.Invocation.class,
                (method, args) ->
                        switch (method) {
                            case "source" -> player;
                            case "arguments" -> arguments;
                            default -> null;
                        });
    }

    private static String readUtf(ByteBuffer input) {
        int size = 0;
        for (int shift = 0; ; shift += 7) {
            byte next = input.get();
            size |= (next & 127) << shift;
            if (next >= 0) break;
        }
        byte[] text = new byte[size];
        input.get(text);
        return new String(text, StandardCharsets.UTF_8);
    }

    private static byte[] replyFor(String jwt, String key, String challenge) {
        String hmac =
                HexFormat.of().formatHex(Sessions.hmac(HexFormat.of().parseHex(key), challenge));
        return Authentication.concat(
                new byte[] {2},
                Authentication.concat(Authentication.utf(jwt), Authentication.utf(hmac)));
    }

    private static byte[] proof(String challenge) {
        String[] session = Sessions.issue(SECRET, NAME, ACCOUNT, System.currentTimeMillis() / 1000);
        return replyFor(session[0], session[1], challenge);
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

    private record Login(GameProfileRequestEvent profile, Player player) {}

    private static final class Fixture implements AutoCloseable {
        final AtomicReference<String> profile =
                new AtomicReference<>("{\"name\":\"RigPeer\",\"id\":\"" + ACCOUNT + "\"}");
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final InetSocketAddress remote = new InetSocketAddress("127.0.0.1", 43210);
        final Authentication authentication;
        final java.util.Map<String, SimpleCommand> commands = new java.util.HashMap<>();
        final java.util.concurrent.atomic.AtomicInteger admissions =
                new java.util.concurrent.atomic.AtomicInteger();
        final AtomicReference<Runnable> disconnect = new AtomicReference<>();

        Fixture() throws Exception {
            server.createContext(
                    "/",
                    exchange -> {
                        String response =
                                exchange.getRequestURI().getPath().equals("/auth/authenticate")
                                        ? "{\"accessToken\":\"fixture-access\",\"clientToken\":\"fixture-client\",\"selectedProfile\":{\"name\":\"RigPeer\",\"id\":\""
                                                + ACCOUNT
                                                + "\"}}"
                                        : profile.get();
                        byte[] body = response.getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, body.length);
                        try (var output = exchange.getResponseBody()) {
                            output.write(body);
                        }
                        exchange.close();
                    });
            server.start();
            var commandName = new AtomicReference<String>();
            var builder = new AtomicReference<CommandMeta.Builder>();
            builder.set(
                    fake(
                            CommandMeta.Builder.class,
                            (method, args) ->
                                    method.equals("build")
                                            ? fake(CommandMeta.class, (m, a) -> null)
                                            : builder.get()));
            CommandManager manager =
                    fake(
                            CommandManager.class,
                            (method, args) -> {
                                if (method.equals("metaBuilder")) {
                                    commandName.set((String) args[0]);
                                    return builder.get();
                                }
                                if (method.equals("register"))
                                    commands.put(commandName.get(), (SimpleCommand) args[1]);
                                return null;
                            });
            var task = new AtomicReference<Scheduler.TaskBuilder>();
            task.set(
                    fake(
                            Scheduler.TaskBuilder.class,
                            (method, args) -> method.equals("delay") ? task.get() : null));
            Scheduler scheduler =
                    fake(
                            Scheduler.class,
                            (method, args) -> {
                                disconnect.set((Runnable) args[1]);
                                return task.get();
                            });
            ProxyServer proxy =
                    fake(
                            ProxyServer.class,
                            (method, args) ->
                                    switch (method) {
                                        case "getCommandManager" -> manager;
                                        case "getScheduler" -> scheduler;
                                        default -> null;
                                    });
            authentication =
                    new Authentication(
                            this,
                            proxy,
                            LoggerFactory.getLogger("test"),
                            new LoginForwarding(),
                            "http://127.0.0.1:" + server.getAddress().getPort(),
                            SECRET);
            authentication.registerCommands(ignored -> admissions.incrementAndGet());
        }

        Player passwordPlayer(java.util.List<byte[]> packets, CompletableFuture<Void> finished) {
            return fake(
                    Player.class,
                    (method, args) ->
                            switch (method) {
                                case "getUniqueId" -> GameProfile.forOfflinePlayer(NAME).getId();
                                case "getUsername" -> NAME;
                                case "getRemoteAddress" -> remote;
                                case "isActive" -> true;
                                case "sendPluginMessage" -> {
                                    packets.add((byte[]) args[1]);
                                    yield true;
                                }
                                case "sendMessage" -> {
                                    finished.complete(null);
                                    yield null;
                                }
                                default -> null;
                            });
        }

        GameProfileRequestEvent begin(Function<String, byte[]> response) {
            LoginPhaseConnection connection =
                    fake(
                            LoginPhaseConnection.class,
                            (method, args) -> {
                                if (method.equals("getRemoteAddress")) return remote;
                                if (method.equals("sendLoginPluginMessage")) {
                                    byte[] reply = new byte[] {0};
                                    if (args[0].equals(Authentication.AUTH)) {
                                        ByteBuffer challenge = ByteBuffer.wrap((byte[]) args[1]);
                                        int length = challenge.get();
                                        byte[] text = new byte[length];
                                        challenge.get(text);
                                        reply =
                                                response.apply(
                                                        new String(text, StandardCharsets.UTF_8));
                                    }
                                    ((LoginPhaseConnection.MessageConsumer) args[2])
                                            .onMessageResponse(reply);
                                }
                                return null;
                            });
            authentication.onPreLogin(new PreLoginEvent(connection, NAME));
            return new GameProfileRequestEvent(
                    connection, GameProfile.forOfflinePlayer(NAME), false);
        }

        Login login(Function<String, byte[]> response) throws Exception {
            GameProfileRequestEvent event = begin(response);
            await(authentication.onProfile(event));
            Player player = player(event.getGameProfile().getId());
            await(authentication.onLogin(new LoginEvent(player)));
            return new Login(event, player);
        }

        Player player(UUID id) {
            return fake(
                    Player.class,
                    (method, args) ->
                            switch (method) {
                                case "getUniqueId" -> id;
                                case "getUsername" -> NAME;
                                case "getRemoteAddress" -> remote;
                                default -> null;
                            });
        }

        public void close() {
            server.stop(0);
        }
    }
}

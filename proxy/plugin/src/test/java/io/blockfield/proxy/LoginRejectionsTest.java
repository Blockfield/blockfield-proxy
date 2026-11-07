package io.blockfield.proxy;

import static io.blockfield.proxy.GateEvictionTest.fake;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.proxy.LoginPhaseConnection;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** proxy#74 U4: every rejected login is counted by reason, without changing any deny path. */
class LoginRejectionsTest {
    private static final String NAME = "RigPeer";
    private static final String EXPECTED = "74b2f4aeceb14b2dabbfc5f41d0a8846";
    private static final String OTHER = "e10438e4797b49718cfecec6a8fa171b";
    private static final InetSocketAddress REMOTE = new InetSocketAddress("127.0.0.1", 43210);

    @Test
    void rejectedLoginsCountedByReason() throws Exception {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    String path = exchange.getRequestURI().getPath();
                    boolean auth = path.equals("/auth/authenticate");
                    String body =
                            auth
                                    ? "{\"error\":\"invalid\"}"
                                    : "{\"name\":\"" + NAME + "\",\"id\":\"" + OTHER + "\"}";
                    byte[] data = body.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(auth ? 403 : 200, data.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(data);
                    }
                    exchange.close();
                });
        server.start();
        try {
            var commands = new HashMap<String, SimpleCommand>();
            Authentication authentication =
                    new Authentication(
                            this,
                            proxy(commands),
                            LoggerFactory.getLogger("test"),
                            new LoginForwarding(),
                            "http://127.0.0.1:" + server.getAddress().getPort(),
                            secret);
            authentication.registerCommands(ignored -> {});
            assertTrue(authentication.rejections().isEmpty());

            var mismatch = fake(LoginPhaseConnection.class, (method, args) -> null);
            var mismatchEvent = new PreLoginEvent(mismatch, NAME);
            authentication.onPreLogin(mismatchEvent);
            assertFalse(mismatchEvent.getResult().isAllowed());

            var tcp =
                    RaknetOnlyTest.overTcp(
                            LoginPhaseConnection.class,
                            fake(
                                    LoginPhaseConnection.class,
                                    (method, args) ->
                                            method.equals("getRemoteAddress") ? REMOTE : null));
            var tcpEvent = new PreLoginEvent(tcp, NAME);
            authentication.onPreLogin(tcpEvent);
            assertFalse(tcpEvent.getResult().isAllowed());

            var raknet =
                    RaknetOnlyTest.overRaknetOnPort(
                            LoginPhaseConnection.class,
                            fake(
                                    LoginPhaseConnection.class,
                                    (method, args) ->
                                            method.equals("getRemoteAddress") ? REMOTE : null),
                            25566);
            var nickEvent = new PreLoginEvent(raknet, "ab!");
            authentication.onPreLogin(nickEvent);
            assertFalse(nickEvent.getResult().isAllowed());

            String[] session =
                    Sessions.issue(secret, NAME, EXPECTED, System.currentTimeMillis() / 1000);
            var remembered =
                    RaknetOnlyTest.overRaknetOnPort(
                            LoginPhaseConnection.class,
                            fake(
                                    LoginPhaseConnection.class,
                                    (method, args) -> {
                                        if (method.equals("getRemoteAddress")) return REMOTE;
                                        if (method.equals("sendLoginPluginMessage")
                                                && args[0].equals(Authentication.AUTH)) {
                                            // Real client proof: hmac(key, serverId) for the
                                            // serverId in this login challenge.
                                            ByteBuffer challenge =
                                                    ByteBuffer.wrap((byte[]) args[1]);
                                            int length = challenge.get();
                                            byte[] text = new byte[length];
                                            challenge.get(text);
                                            String serverId =
                                                    new String(text, StandardCharsets.UTF_8);
                                            byte[] proof =
                                                    Authentication.utf(
                                                            HexFormat.of()
                                                                    .formatHex(
                                                                            Sessions.hmac(
                                                                                    HexFormat.of()
                                                                                            .parseHex(
                                                                                                    session[
                                                                                                            1]),
                                                                                    serverId)));
                                            byte[] reply =
                                                    Authentication.concat(
                                                            new byte[] {2},
                                                            Authentication.concat(
                                                                    Authentication.utf(session[0]),
                                                                    proof));
                                            ((LoginPhaseConnection.MessageConsumer) args[2])
                                                    .onMessageResponse(reply);
                                        }
                                        return null;
                                    }),
                            25566);
            // Valid token for EXPECTED while Drasl reports OTHER: parked in the lobby, counted.
            authentication.onPreLogin(new PreLoginEvent(remembered, NAME));
            long deadline = System.currentTimeMillis() + 5000;
            while (!authentication.rejections().containsKey(Authentication.REJECT_SESSION)
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }

            var events = new LinkedBlockingQueue<String>();
            Player player =
                    fake(
                            Player.class,
                            (method, args) ->
                                    switch (method) {
                                        case "getUniqueId" -> new UUID(0, 1);
                                        case "getUsername" -> NAME;
                                        case "getRemoteAddress" -> REMOTE;
                                        case "isActive" -> true;
                                        case "sendMessage" -> {
                                            events.add("message");
                                            yield null;
                                        }
                                        case "disconnect" -> {
                                            events.add("disconnect");
                                            yield null;
                                        }
                                        default -> null;
                                    });
            SimpleCommand login = commands.get("login");
            assertNotNull(login);
            for (int i = 0; i < 16; i++) {
                login.execute(invocation(player));
                assertNotNull(events.poll(5, TimeUnit.SECONDS), "auth reply " + i);
            }

            assertEquals(
                    Map.of(
                            Authentication.REJECT_MISMATCH, 1,
                            Authentication.REJECT_TCP, 1,
                            Authentication.REJECT_NICK, 1,
                            Authentication.REJECT_SESSION, 1,
                            Authentication.REJECT_AUTH, 15,
                            Authentication.REJECT_RATE_LIMIT, 1),
                    authentication.rejections());
        } finally {
            server.stop(0);
        }
    }

    private static SimpleCommand.Invocation invocation(Player player) {
        return fake(
                SimpleCommand.Invocation.class,
                (method, args) ->
                        switch (method) {
                            case "source" -> player;
                            case "arguments" -> new String[] {"wrong"};
                            default -> null;
                        });
    }

    private static ProxyServer proxy(Map<String, SimpleCommand> commands) {
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
                            if (method.equals("register")) {
                                commands.put(commandName.get(), (SimpleCommand) args[1]);
                            }
                            return null;
                        });
        return fake(
                ProxyServer.class,
                (method, args) -> method.equals("getCommandManager") ? manager : null);
    }
}

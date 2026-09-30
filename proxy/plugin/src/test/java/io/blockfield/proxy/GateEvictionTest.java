package io.blockfield.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

/**
 * Stand acceptance of blockfield-releases#8 (2026-09-24): the site's status query pinged the proxy
 * right after the controller closed the gate; onPing reloaded gate.json first, tick() then saw no
 * change and the builder stayed on the workshop backend while the controller was in ERROR.
 */
class GateEvictionTest {
    private static final String DRASL = "6727890496a93613a33463780ae01220";

    @Test
    void gateReloadedOutsideTickStillEvicts() throws Exception {
        AtomicInteger evictions = new AtomicInteger();
        RegisteredServer lobby = server("lobby"),
                workshop = server("workshop"),
                game = server("game");
        UUID id = UUID.randomUUID();
        ServerConnection onWorkshop =
                fake(
                        ServerConnection.class,
                        (method, args) ->
                                switch (method) {
                                    case "getServer" -> workshop;
                                    case "getServerInfo" -> workshop.getServerInfo();
                                    default -> null;
                                });
        ConnectionRequestBuilder request =
                fake(
                        ConnectionRequestBuilder.class,
                        (method, args) -> {
                            if (method.equals("fireAndForget")) evictions.incrementAndGet();
                            return null;
                        });
        Player builder =
                fake(
                        Player.class,
                        (method, args) ->
                                switch (method) {
                                    case "getUniqueId" -> id;
                                    case "getUsername" -> "RigPeer";
                                    case "getCurrentServer" -> Optional.of(onWorkshop);
                                    case "createConnectionRequest" ->
                                            args[0] == lobby ? request : null;
                                    default -> null;
                                });
        Map<String, RegisteredServer> servers =
                Map.of("lobby", lobby, "workshop", workshop, "game", game);
        ProxyServer proxy =
                fake(
                        ProxyServer.class,
                        (method, args) ->
                                switch (method) {
                                    case "getServer" ->
                                            Optional.ofNullable(servers.get((String) args[0]));
                                    case "getAllPlayers" -> List.of(builder);
                                    default -> null;
                                });
        Authentication authentication =
                new Authentication(
                        this, proxy, LoggerFactory.getLogger("test"), new LoginForwarding());
        BackendRouting plugin =
                new BackendRouting(this, proxy, LoggerFactory.getLogger("test"), authentication);
        this.<Set<UUID>>field(authentication, "authed").add(id);
        this.<Map<UUID, String>>field(authentication, "draslIds").put(id, DRASL);
        Method tick = BackendRouting.class.getDeclaredMethod("tick");
        tick.setAccessible(true);

        setGate(
                plugin,
                new Gate("builders", "workshop", "msg", Set.of(new Gate.Member(DRASL, "RigPeer"))));
        tick.invoke(plugin);
        assertEquals(0, evictions.get(), "an allowed builder stays");

        // onPing/onPreConnect reload the file between two ticks: the gate field already holds the
        // closed gate.
        setGate(plugin, new Gate("closed", "game", "msg", Set.of()));
        tick.invoke(plugin);
        assertEquals(
                1,
                evictions.get(),
                "the closed gate evicts once even though tick did not reload it");
        tick.invoke(plugin);
        assertEquals(1, evictions.get(), "no repeated eviction for the same gate");

        setGate(plugin, Gate.OPEN);
        var admitted = new ServerPreConnectEvent(builder, game, lobby);
        plugin.onPreConnect(admitted);
        assertEquals(
                true, admitted.getResult().isAllowed(), "authenticated player enters public game");
        authentication.onDisconnect(
                new DisconnectEvent(builder, DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN));
        assertEquals(false, authentication.authenticated(builder));
        assertEquals(null, authentication.profileId(id));
        var denied = new ServerPreConnectEvent(builder, game, lobby);
        plugin.onPreConnect(denied);
        assertEquals(false, denied.getResult().isAllowed(), "disconnect revokes backend admission");
        var parked = new ServerPreConnectEvent(builder, lobby, game);
        plugin.onPreConnect(parked);
        assertEquals(
                true,
                parked.getResult().isAllowed(),
                "unauthenticated player may still enter lobby");
    }

    private static RegisteredServer server(String name) {
        ServerInfo info = new ServerInfo(name, InetSocketAddress.createUnresolved("127.0.0.1", 1));
        return fake(
                RegisteredServer.class,
                (method, args) ->
                        switch (method) {
                            case "getServerInfo" -> info;
                            case "getPlayersConnected" -> List.of();
                            case "ping" -> new CompletableFuture<>();
                            default -> null;
                        });
    }

    private static void setGate(BackendRouting plugin, Gate gate) throws Exception {
        Field field = BackendRouting.class.getDeclaredField("gate");
        field.setAccessible(true);
        field.set(plugin, gate);
    }

    @SuppressWarnings("unchecked")
    private <T> T field(Authentication plugin, String name) throws Exception {
        Field field = Authentication.class.getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(plugin);
    }

    @SuppressWarnings("unchecked")
    static <T> T fake(Class<T> type, BiFunction<String, Object[], Object> answer) {
        return (T)
                java.lang.reflect.Proxy.newProxyInstance(
                        type.getClassLoader(),
                        new Class<?>[] {type},
                        (self, method, args) -> {
                            Object result = answer.apply(method.getName(), args);
                            if (result != null) return result;
                            return switch (method.getName()) {
                                case "hashCode" -> System.identityHashCode(self);
                                case "equals" -> self == args[0];
                                case "toString" -> type.getSimpleName();
                                default ->
                                        method.getReturnType() == Optional.class
                                                ? Optional.empty()
                                                : method.getReturnType() == boolean.class
                                                        ? false
                                                        : null;
                            };
                        });
    }
}

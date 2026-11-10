package io.blockfield.proxy;

import static io.blockfield.proxy.GateEvictionTest.fake;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.InboundConnection;
import com.velocitypowered.api.proxy.LoginPhaseConnection;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.api.proxy.server.ServerPing;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

class BackendRoutingTest {
    private static final String DRASL = "6727890496a93613a33463780ae01220";

    @Test
    void hostNormalizationAndConfiguration() throws Exception {
        assertEquals("workshop.invalid", BackendRouting.normalizeHost("WORKSHOP.invalid."));
        assertEquals(
                BackendRouting.normalizeHost("[2001:db8::1]"),
                BackendRouting.normalizeHost("2001:0DB8:0:0:0:0:0:1"));
        for (String invalid :
                List.of(
                        "raknet;workshop.invalid",
                        "workshop.invalid:25566",
                        "https://workshop.invalid")) {
            // P0-1: опечатка не роняет плагин (и gate-хуки), мастерская отключается.
            var fixture = new Fixture(invalid);
            Player player = fixture.player(UUID.randomUUID(), "workshop.invalid", "Builder");
            fixture.authenticate(player);
            fixture.gate("builders", "workshop", "Builder");
            assertFalse(
                    fixture.connect(player, "workshop").getResult().isAllowed(),
                    "invalid workshop host disables Workshop entry, like an empty one");
        }
        var fixture = new Fixture("[2001:db8::1]");
        Player player = fixture.player(UUID.randomUUID(), "2001:DB8:0:0:0:0:0:1", "Builder");
        fixture.authenticate(player);
        fixture.gate("builders", "workshop", "Builder");
        assertTrue(fixture.connect(player, "workshop").getResult().isAllowed());
    }

    @Test
    void builderGameAddressNeverEntersWorkshopAndWorkshopNeverEntersGame() throws Exception {
        var fixture = new Fixture("workshop.invalid");
        Player game = fixture.player(UUID.randomUUID(), "game.invalid", "Builder");
        Player workshop = fixture.player(UUID.randomUUID(), "WORKSHOP.invalid.", "Builder");
        fixture.authenticate(game);
        fixture.authenticate(workshop);
        fixture.gate("builders", "workshop", "Builder");
        assertEquals(fixture.servers.get("lobby"), fixture.choose(game));
        assertFalse(fixture.connect(game, "workshop").getResult().isAllowed());
        fixture.routing.admit(game);
        fixture.routing.tick();
        fixture.pings.get("workshop").complete(null);
        assertEquals(List.of(), fixture.requests.get(game.getUniqueId()));
        assertEquals(List.of("workshop"), fixture.requests.get(workshop.getUniqueId()));

        fixture.gate("public", "game", null);
        assertEquals(fixture.servers.get("lobby"), fixture.choose(workshop));
        assertFalse(fixture.connect(workshop, "game").getResult().isAllowed());
        assertTrue(fixture.connect(game, "game").getResult().isAllowed());
        assertFalse(fixture.connect(game, "workshop").getResult().isAllowed());
    }

    @Test
    void workshopHostGrantsNoRoleAndEmptyConfigurationDisablesWorkshop() throws Exception {
        var fixture = new Fixture("workshop.invalid");
        Player builder = fixture.player(UUID.randomUUID(), "workshop.invalid", "Builder");
        Player other = fixture.player(UUID.randomUUID(), "workshop.invalid", "Other");
        fixture.authenticate(builder);
        fixture.authenticate(other);
        for (String state : List.of("builders", "testers")) {
            fixture.gate(state, "workshop", "Builder");
            assertTrue(fixture.connect(builder, "workshop").getResult().isAllowed());
            assertFalse(fixture.connect(other, "workshop").getResult().isAllowed());
        }
        fixture.gate("closed", "workshop", "Builder");
        assertFalse(fixture.connect(builder, "workshop").getResult().isAllowed());
        var unset = new Fixture("");
        Player legacy = unset.player(UUID.randomUUID(), "workshop.invalid", "Builder");
        unset.authenticate(legacy);
        unset.gate("builders", "workshop", "Builder");
        assertFalse(unset.connect(legacy, "workshop").getResult().isAllowed());
    }

    @Test
    void workshopPackContractGatesWorkshopLegOnly() throws Exception {
        String workshopPack = "a".repeat(64);
        String gamePack = "b".repeat(64);
        var fixture =
                new Fixture("workshop.invalid", BackendRouting.DEFAULT_WORKSHOP_PORT, workshopPack);
        Player builder = fixture.player(UUID.randomUUID(), "workshop.invalid", "Builder");
        Player gamer = fixture.player(UUID.randomUUID(), "game.invalid", "Gamer");
        fixture.authenticate(builder);
        fixture.authenticate(gamer);
        fixture.dualGate("public", "builders", "Builder");
        // No contract reply: workshop leg denied with the pack message, game leg open.
        assertFalse(fixture.connect(builder, "workshop").getResult().isAllowed());
        assertTrue(fixture.connect(gamer, "game").getResult().isAllowed());
        // Game-only pack on the workshop leg: denied, with a clear message.
        fixture.forwarding.rememberContract(
                builder.getUniqueId(), Authentication.utf(gamePack + ":9"));
        fixture.forwarding.rememberContract(
                gamer.getUniqueId(), Authentication.utf(gamePack + ":9"));
        assertFalse(fixture.connect(builder, "workshop").getResult().isAllowed());
        assertTrue(
                fixture.connect(gamer, "game").getResult().isAllowed(),
                "game leg ignores the Workshop pack gate");
        assertEquals(
                Component.text(BackendRouting.WORKSHOP_PACK_MESSAGE, NamedTextColor.GOLD),
                fixture.messages.get(builder.getUniqueId()).getLast());
        // Workshop pack on the workshop leg: admitted.
        fixture.forwarding.rememberContract(
                builder.getUniqueId(), Authentication.utf(workshopPack + ":9"));
        assertTrue(fixture.connect(builder, "workshop").getResult().isAllowed());
        assertEquals(fixture.servers.get("workshop"), fixture.choose(builder));
    }

    @Test
    void workshopPortSelectsWorkshopProfile() throws Exception {
        var fixture = new Fixture("workshop.invalid");
        // Game-looking vhost, Workshop inbound port: the port decides, not the vhost.
        Player workshop = fixture.player(UUID.randomUUID(), "game.invalid", "WsBuilder");
        fixture.authentication.noteInboundPort("WsBuilder", 25567);
        fixture.authenticate(workshop);
        fixture.gate("builders", "workshop", "WsBuilder");
        assertEquals(fixture.servers.get("workshop"), fixture.choose(workshop));
        assertTrue(fixture.connect(workshop, "workshop").getResult().isAllowed());
        assertFalse(fixture.connect(workshop, "game").getResult().isAllowed());
    }

    @Test
    void gamePortIgnoresWorkshopVhost() throws Exception {
        var fixture = new Fixture("workshop.invalid");
        // Even a workshop vhost (crafted hostname) on the Game port stays on Game.
        Player game = fixture.player(UUID.randomUUID(), "workshop.invalid", "Crafty");
        fixture.authentication.noteInboundPort("Crafty", 25566);
        fixture.authenticate(game);
        fixture.dualGate("public", "builders", "Crafty");
        assertEquals(fixture.servers.get("game"), fixture.choose(game));
        assertFalse(fixture.connect(game, "workshop").getResult().isAllowed());
    }

    @Test
    void unknownPortKeepsLegacyVhostRule() throws Exception {
        var fixture = new Fixture("workshop.invalid");
        // No recorded port (e.g. direct backend wiring in tests): vhost decides.
        Player legacy = fixture.player(UUID.randomUUID(), "workshop.invalid", "Legacy");
        fixture.authenticate(legacy);
        fixture.gate("builders", "workshop", "Legacy");
        assertEquals(fixture.servers.get("workshop"), fixture.choose(legacy));
    }

    @Test
    void disabledWorkshopPortRoutesToGame() throws Exception {
        var fixture = new Fixture("workshop.invalid", -1);
        Player game = fixture.player(UUID.randomUUID(), "game.invalid", "NoWs");
        fixture.authentication.noteInboundPort("NoWs", 25567);
        fixture.authenticate(game);
        fixture.dualGate("public", "closed", null);
        assertEquals(fixture.servers.get("game"), fixture.choose(game));
    }

    @Test
    void preLoginPublishesNoRoutingStateBeforeTheDraslGate() throws Exception {
        var fixture = new Fixture("workshop.invalid");
        var raknetEvent =
                new PreLoginEvent(
                        RaknetOnlyTest.overRaknetOnPort(
                                LoginPhaseConnection.class, remote("1.2.3.4"), 25567),
                        "PortNick");
        fixture.authentication.onPreLogin(raknetEvent);
        assertTrue(raknetEvent.getResult().isAllowed());
        assertNull(
                fixture.authentication.inboundPort("PortNick"),
                "PreLogin must not publish nickname-keyed routing state");
        // TCP logs in too; its listener port is never the Workshop port, so it routes to Game
        // once the Drasl gate publishes it at LoginEvent (simulated here by noteInboundPort).
        var tcpEvent =
                new PreLoginEvent(
                        RaknetOnlyTest.overTransport(
                                LoginPhaseConnection.class,
                                remote("5.6.7.8"),
                                false,
                                new InetSocketAddress("127.0.0.1", 25565)),
                        "TcpNick");
        fixture.authentication.onPreLogin(tcpEvent);
        assertTrue(tcpEvent.getResult().isAllowed());
        assertNull(
                fixture.authentication.inboundPort("TcpNick"),
                "PreLogin must not publish nickname-keyed routing state");
        fixture.authentication.noteInboundPort("TcpNick", 25565);
        Player tcp = fixture.player(UUID.randomUUID(), "workshop.invalid", "TcpNick");
        fixture.authenticate(tcp);
        fixture.dualGate("public", "builders", "TcpNick");
        assertEquals(fixture.servers.get("game"), fixture.choose(tcp));
        // Internals mismatch still fails closed.
        var badEvent =
                new PreLoginEvent(
                        GateEvictionTest.fake(LoginPhaseConnection.class, (m, a) -> null),
                        "BadNick");
        fixture.authentication.onPreLogin(badEvent);
        assertFalse(badEvent.getResult().isAllowed());
    }

    private static LoginPhaseConnection remote(String host) {
        return GateEvictionTest.fake(
                LoginPhaseConnection.class,
                (method, args) ->
                        method.equals("getRemoteAddress")
                                ? InetSocketAddress.createUnresolved(host, 1000)
                                : null);
    }

    @Test
    void readinessRestartAndReconnectKeepTheSelectedProfile() throws Exception {
        var fixture = new Fixture("workshop.invalid");
        UUID id = UUID.randomUUID();
        Player workshop = fixture.player(id, "workshop.invalid", "Builder");
        fixture.authenticate(workshop);
        fixture.gate("builders", "workshop", "Builder");
        fixture.routing.admit(workshop);
        fixture.routing.tick();
        assertEquals(
                List.of(),
                fixture.requests.get(id),
                "a backend that has not answered ping is not entered");
        fixture.gate("public", "game", null);
        fixture.pings.get("workshop").complete(null);
        assertFalse(
                fixture.connect(workshop, "workshop").getResult().isAllowed(),
                "preconnect rechecks a gate changed while ping was pending");
        fixture.current.put(id, fixture.servers.get("workshop"));
        var kicked =
                new KickedFromServerEvent(
                        workshop,
                        fixture.servers.get("workshop"),
                        null,
                        false,
                        KickedFromServerEvent.DisconnectPlayer.create(Component.empty()));
        fixture.routing.onKicked(kicked);
        assertEquals(
                fixture.servers.get("lobby"),
                assertInstanceOf(KickedFromServerEvent.RedirectPlayer.class, kicked.getResult())
                        .getServer());
        fixture.routing.tick();
        assertTrue(
                fixture.requests.get(id).contains("lobby"),
                "backend transition parks the workshop profile");
        fixture.pings.get("game").complete(null);
        assertFalse(fixture.requests.get(id).contains("game"));

        fixture.authentication.onDisconnect(
                new DisconnectEvent(workshop, DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN));
        Player reconnect = fixture.player(id, "game.invalid", "Builder");
        assertFalse(
                fixture.connect(reconnect, "game").getResult().isAllowed(),
                "reconnect must authenticate again");
        fixture.authenticate(reconnect);
        assertEquals(fixture.servers.get("game"), fixture.choose(reconnect));
        fixture.gate("builders", "workshop", "Builder");
        assertEquals(
                fixture.servers.get("lobby"),
                fixture.choose(reconnect),
                "switching the gate does not switch the reconnected profile");
    }

    @Test
    void drainingMembershipStillRequiresGameAddress() throws Exception {
        var fixture = new Fixture("workshop.invalid");
        Player game = fixture.player(UUID.randomUUID(), "game.invalid", "Player");
        Player workshop = fixture.player(UUID.randomUUID(), "workshop.invalid", "Builder");
        fixture.authenticate(game);
        fixture.authenticate(workshop);
        fixture.gate("draining", "game", null);
        assertFalse(fixture.connect(game, "game").getResult().isAllowed());
        fixture.current.put(game.getUniqueId(), fixture.servers.get("game"));
        fixture.current.put(workshop.getUniqueId(), fixture.servers.get("game"));
        assertTrue(fixture.connect(game, "game").getResult().isAllowed());
        assertFalse(fixture.connect(workshop, "game").getResult().isAllowed());
    }

    @Test
    void simultaneousProfilesReconnectAndWorkshopClosureLeavesGameAlone() throws Exception {
        var fixture = new Fixture("workshop.invalid");
        Player game = fixture.player(UUID.randomUUID(), "game.invalid", "Player");
        Player builder = fixture.player(UUID.randomUUID(), "workshop.invalid", "Builder");
        Player outsider = fixture.player(UUID.randomUUID(), "workshop.invalid", "Other");
        for (Player player : List.of(game, builder, outsider)) fixture.authenticate(player);
        fixture.dualGate("builders", "Builder");
        assertEquals(fixture.servers.get("game"), fixture.choose(game));
        assertEquals(fixture.servers.get("workshop"), fixture.choose(builder));
        assertEquals(fixture.servers.get("lobby"), fixture.choose(outsider));
        assertFalse(fixture.connect(builder, "game").getResult().isAllowed());
        assertFalse(fixture.connect(game, "workshop").getResult().isAllowed());
        fixture.routing.tick();
        fixture.pings.get("game").complete(null);
        fixture.pings.get("workshop").complete(null);
        assertEquals(List.of("game"), fixture.requests.get(game.getUniqueId()));
        assertEquals(List.of("workshop"), fixture.requests.get(builder.getUniqueId()));
        assertEquals(List.of(), fixture.requests.get(outsider.getUniqueId()));
        fixture.current.put(game.getUniqueId(), fixture.servers.get("game"));
        fixture.current.put(builder.getUniqueId(), fixture.servers.get("workshop"));
        fixture.dualGate("closed", "Builder");
        fixture.routing.tick();
        assertEquals(List.of("game"), fixture.requests.get(game.getUniqueId()));
        assertEquals(List.of("workshop", "lobby"), fixture.requests.get(builder.getUniqueId()));
        assertTrue(fixture.connect(game, "game").getResult().isAllowed());
        assertFalse(fixture.connect(builder, "workshop").getResult().isAllowed());
    }

    @Test
    void workshopAclRevocationDoesNotEvictGameAndPendingPingCannotReadmit() throws Exception {
        var fixture = new Fixture("workshop.invalid");
        Player game = fixture.player(UUID.randomUUID(), "game.invalid", "Player");
        Player builder = fixture.player(UUID.randomUUID(), "workshop.invalid", "Builder");
        fixture.authenticate(game);
        fixture.authenticate(builder);
        fixture.dualGate("testers", "Builder");
        fixture.current.put(game.getUniqueId(), fixture.servers.get("game"));
        fixture.routing.admit(builder);
        fixture.routing.tick();
        fixture.dualGate("testers", null);
        fixture.pings.get("workshop").complete(null);
        assertEquals(List.of(), fixture.requests.get(builder.getUniqueId()));
        fixture.current.put(builder.getUniqueId(), fixture.servers.get("workshop"));
        fixture.routing.tick();
        assertEquals(List.of("lobby"), fixture.requests.get(builder.getUniqueId()));
        assertEquals(List.of(), fixture.requests.get(game.getUniqueId()));
        assertTrue(fixture.connect(game, "game").getResult().isAllowed());
    }

    @Test
    void disconnectDuringReadinessRevokesWorkshopAdmission() throws Exception {
        var fixture = new Fixture("workshop.invalid");
        Player builder = fixture.player(UUID.randomUUID(), "workshop.invalid", "Builder");
        fixture.authenticate(builder);
        fixture.dualGate("builders", "Builder");
        fixture.routing.admit(builder);
        fixture.routing.tick();
        fixture.authentication.onDisconnect(
                new DisconnectEvent(builder, DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN));
        fixture.pings.get("workshop").complete(null);
        assertEquals(List.of(), fixture.requests.get(builder.getUniqueId()));
        assertFalse(fixture.connect(builder, "workshop").getResult().isAllowed());
    }

    @Test
    void gameClosureKeepsWorkshopMembersOnTheirBackend() throws Exception {
        var fixture = new Fixture("workshop.invalid");
        Player game = fixture.player(UUID.randomUUID(), "game.invalid", "Player");
        Player builder = fixture.player(UUID.randomUUID(), "workshop.invalid", "Builder");
        fixture.authenticate(game);
        fixture.authenticate(builder);
        fixture.current.put(game.getUniqueId(), fixture.servers.get("game"));
        fixture.current.put(builder.getUniqueId(), fixture.servers.get("workshop"));
        fixture.dualGate("closed", "builders", "Builder");
        fixture.routing.tick();
        assertEquals(List.of("lobby"), fixture.requests.get(game.getUniqueId()));
        assertEquals(List.of(), fixture.requests.get(builder.getUniqueId()));
        assertTrue(fixture.connect(builder, "workshop").getResult().isAllowed());
    }

    @Test
    void pingDescriptionUsesTheRequestedProfileGate() throws Exception {
        var fixture = new Fixture("workshop.invalid");
        fixture.dualGate("builders", "Builder");
        Component gameMessage = Component.text("Game MOTD");
        ServerPing original =
                ServerPing.builder()
                        .version(new ServerPing.Version(767, "1.21.1"))
                        .description(gameMessage)
                        .build();
        var gamePing = new ProxyPingEvent(connection("game.invalid"), original);
        fixture.routing.onPing(gamePing);
        assertEquals(gameMessage, gamePing.getPing().getDescriptionComponent());
        var workshopPing = new ProxyPingEvent(connection("WORKSHOP.invalid."), original);
        fixture.routing.onPing(workshopPing);
        assertEquals(
                Component.text(
                        "Workshop gate", net.kyori.adventure.text.format.NamedTextColor.GOLD),
                workshopPing.getPing().getDescriptionComponent());
    }

    @Test
    void halfConfiguredV2PinsGameClosed() throws Exception {
        var fixture = new Fixture("workshop.invalid");
        ControlSubscriber v2 =
                ControlSubscriber.fromParts(
                        "",
                        "",
                        "proxy-1",
                        LoggerFactory.getLogger("test"),
                        System::currentTimeMillis,
                        message -> {},
                        null);
        assertNotNull(v2, "a consumer without URL/token pins the game leg closed");
        Field plane = BackendRouting.class.getDeclaredField("controlPlane");
        plane.setAccessible(true);
        plane.set(fixture.routing, v2);
        var reload = BackendRouting.class.getDeclaredMethod("reloadGate");
        reload.setAccessible(true);
        reload.invoke(fixture.routing);
        Field gate = BackendRouting.class.getDeclaredField("gate");
        gate.setAccessible(true);
        assertEquals(
                "closed",
                ((Gate) gate.get(fixture.routing)).forBackend("game").state(),
                "the v2 consumer owns game entry; without it entry stays closed");
    }

    private static InboundConnection connection(String host) {
        return fake(
                InboundConnection.class,
                (method, args) ->
                        method.equals("getVirtualHost")
                                ? Optional.of(InetSocketAddress.createUnresolved(host, 25565))
                                : null);
    }

    @Test
    void deployingGameShowsDeployInProgress() {
        Gate deploying = new Gate("closed", "game", "", Set.of(), true);
        assertEquals(
                BackendRouting.DEPLOYING_GAME,
                BackendRouting.displayText(deploying, "game", "Игровой сервер недоступен."));
        Gate custom = new Gate("closed", "game", "Скоро вернёмся", Set.of(), true);
        assertEquals(
                "Скоро вернёмся",
                BackendRouting.displayText(custom, "game", "Игровой сервер недоступен."));
        Gate workshop = new Gate("closed", "workshop", "Техработы", Set.of());
        assertEquals(
                "Техработы",
                BackendRouting.displayText(workshop, "workshop", "Мастерская сейчас недоступна."));
    }

    private static final class Fixture {
        final Map<String, RegisteredServer> servers = new HashMap<>();
        final Map<String, CompletableFuture<ServerPing>> pings = new HashMap<>();
        final List<Player> players = new ArrayList<>();
        final Map<UUID, RegisteredServer> current = new HashMap<>();
        final Map<UUID, List<String>> requests = new HashMap<>();
        final Map<UUID, List<Component>> messages = new HashMap<>();
        final LoginForwarding forwarding;
        final Authentication authentication;
        final BackendRouting routing;

        Fixture(String host) {
            this(host, BackendRouting.DEFAULT_WORKSHOP_PORT);
        }

        Fixture(String host, int port) {
            this(host, port, "");
        }

        Fixture(String host, int port, String workshopContract) {
            for (String name : List.of("lobby", "game", "workshop")) {
                pings.put(name, new CompletableFuture<>());
                servers.put(
                        name,
                        fake(
                                RegisteredServer.class,
                                (method, args) ->
                                        switch (method) {
                                            case "getServerInfo" ->
                                                    new ServerInfo(
                                                            name,
                                                            InetSocketAddress.createUnresolved(
                                                                    "127.0.0.1", 1));
                                            case "getPlayersConnected" -> playersOn(name);
                                            case "ping" -> pings.get(name);
                                            default -> null;
                                        }));
            }
            ProxyServer proxy =
                    fake(
                            ProxyServer.class,
                            (method, args) ->
                                    switch (method) {
                                        case "getServer" ->
                                                Optional.ofNullable(servers.get((String) args[0]));
                                        case "getAllPlayers" -> players;
                                        default -> null;
                                    });
            forwarding = new LoginForwarding();
            authentication =
                    new Authentication(this, proxy, LoggerFactory.getLogger("test"), forwarding);
            routing =
                    new BackendRouting(
                            this,
                            proxy,
                            LoggerFactory.getLogger("test"),
                            authentication,
                            forwarding,
                            host,
                            port,
                            null,
                            workshopContract);
        }

        Player player(UUID id, String host, String name) {
            current.put(id, servers.get("lobby"));
            requests.put(id, new ArrayList<>());
            messages.put(id, new ArrayList<>());
            players.removeIf(p -> p.getUniqueId().equals(id));
            ServerConnection connection =
                    fake(
                            ServerConnection.class,
                            (method, args) ->
                                    switch (method) {
                                        case "getServer" -> current.get(id);
                                        case "getServerInfo" -> current.get(id).getServerInfo();
                                        default -> null;
                                    });
            Player player =
                    fake(
                            Player.class,
                            (method, args) ->
                                    switch (method) {
                                        case "getUniqueId" -> id;
                                        case "getUsername" -> name;
                                        case "getVirtualHost" ->
                                                Optional.of(
                                                        InetSocketAddress.createUnresolved(
                                                                host, 25566));
                                        case "getCurrentServer" -> Optional.of(connection);
                                        case "createConnectionRequest" ->
                                                request(id, (RegisteredServer) args[0]);
                                        case "sendMessage" -> {
                                            messages.get(id).add((Component) args[0]);
                                            yield null;
                                        }
                                        default -> null;
                                    });
            players.add(player);
            return player;
        }

        private List<Player> playersOn(String name) {
            return players.stream()
                    .filter(player -> current.get(player.getUniqueId()) == servers.get(name))
                    .toList();
        }

        private ConnectionRequestBuilder request(UUID id, RegisteredServer server) {
            return fake(
                    ConnectionRequestBuilder.class,
                    (method, args) -> {
                        if (method.equals("fireAndForget"))
                            requests.get(id).add(server.getServerInfo().getName());
                        return null;
                    });
        }

        void authenticate(Player player) throws Exception {
            this.<Set<UUID>>field(authentication, "authed").add(player.getUniqueId());
            this.<Map<UUID, String>>field(authentication, "draslIds")
                    .put(player.getUniqueId(), DRASL);
        }

        void gate(String state, String backend, String name) throws Exception {
            gate(
                    new Gate(
                            state,
                            backend,
                            "Gate message",
                            name == null ? Set.of() : Set.of(new Gate.Member(DRASL, name))));
        }

        void dualGate(String workshopState, String name) throws Exception {
            dualGate("public", workshopState, name);
        }

        void dualGate(String gameState, String workshopState, String name) throws Exception {
            gate(GateTest.dual(gameState, workshopState, DRASL, name));
        }

        void gate(Gate gate) throws Exception {
            Field field = BackendRouting.class.getDeclaredField("gate");
            field.setAccessible(true);
            field.set(routing, gate);
        }

        RegisteredServer choose(Player player) {
            var event = new PlayerChooseInitialServerEvent(player, servers.get("game"));
            routing.onChooseServer(event);
            return event.getInitialServer().orElseThrow();
        }

        ServerPreConnectEvent connect(Player player, String backend) {
            var event =
                    new ServerPreConnectEvent(player, servers.get(backend), servers.get("lobby"));
            routing.onPreConnect(event);
            return event;
        }

        @SuppressWarnings("unchecked")
        private <T> T field(Object instance, String name) throws Exception {
            Field field = instance.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return (T) field.get(instance);
        }
    }
}

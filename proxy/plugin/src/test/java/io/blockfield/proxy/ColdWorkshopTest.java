package io.blockfield.proxy;

import static io.blockfield.proxy.GateEvictionTest.fake;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.api.scheduler.ScheduledTask;
import com.velocitypowered.api.scheduler.Scheduler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Cold Workshop (contract section 4): lobby hold, waking message, and the wake inbox. */
class ColdWorkshopTest {
    private static final String DRASL = "6727890496a93613a33463780ae01220";

    @Test
    void sleepingGateParsesButNeverAdmits() {
        Gate gate =
                Gate.parse(
                        GateTest.dual(
                                "{\"game\":"
                                        + GateTest.entry("public", "", "")
                                        + ",\"workshop\":"
                                        + GateTest.entry("sleeping", "Спит", member("Builder"))
                                        + "}"));
        Gate workshop = gate.forBackend("workshop");
        assertTrue(workshop.sleeping());
        assertTrue(workshop.waking());
        assertFalse(gate.allows("workshop", DRASL, "Builder", false));
        assertTrue(workshop.member(DRASL, "Builder"));
        assertFalse(workshop.member(DRASL, "Impostor"));
    }

    @Test
    void sleepingMemberGetsMessageAndOneWakeRequest(@TempDir Path inbox) throws Exception {
        var fixture = new Fixture("workshop.invalid", inbox);
        Player builder = fixture.player(UUID.randomUUID(), "workshop.invalid", "Builder");
        fixture.authenticate(builder);
        fixture.gate(SleepingGate());
        fixture.routing.admit(builder);
        assertTrue(
                fixture.messages.get(builder.getUniqueId()).stream()
                        .anyMatch(text -> text.contains("просыпается")),
                "sleeping workshop shows the waking message");
        List<Path> wakes = fixture.wakes();
        assertEquals(1, wakes.size(), "one wake request per sleeping member");
        JsonObject envelope =
                JsonParser.parseString(Files.readString(wakes.get(0))).getAsJsonObject();
        assertEquals(Set.of("schema", "id", "kind", "actor", "createdAt"), envelope.keySet());
        assertEquals(1, envelope.get("schema").getAsInt());
        assertEquals("wake", envelope.get("kind").getAsString());
        assertEquals(
                wakes.get(0).getFileName().toString(),
                envelope.get("id").getAsString() + ".json",
                "file name matches id");
        UUID.fromString(envelope.get("id").getAsString());
        JsonObject actor = envelope.getAsJsonObject("actor");
        assertEquals(Set.of("draslId", "name"), actor.keySet());
        assertEquals(DRASL, actor.get("draslId").getAsString());
        assertEquals("Builder", actor.get("name").getAsString());
        assertTrue(envelope.get("createdAt").getAsLong() > 0);
    }

    @Test
    void wakeRateLimitedToOnePerMinutePerProxy(@TempDir Path inbox) throws Exception {
        var fixture = new Fixture("workshop.invalid", inbox);
        Player first = fixture.player(UUID.randomUUID(), "workshop.invalid", "Builder");
        Player second = fixture.player(UUID.randomUUID(), "workshop.invalid", "Builder");
        fixture.authenticate(first);
        fixture.gate(SleepingGate());
        fixture.routing.admit(first);
        fixture.connect(first, "workshop");
        fixture.routing.tick();
        fixture.authenticate(second);
        fixture.routing.admit(second);
        fixture.routing.tick();
        assertEquals(1, fixture.wakes().size(), "one inbox file per 60 s per proxy");
        assertEquals(
                0,
                Files.list(inbox)
                        .filter(path -> path.getFileName().toString().startsWith("."))
                        .count(),
                "no temp files left behind");
    }

    @Test
    void unsetInboxDirShowsMessageOnly() throws Exception {
        var fixture = new Fixture("workshop.invalid", null);
        Player builder = fixture.player(UUID.randomUUID(), "workshop.invalid", "Builder");
        fixture.authenticate(builder);
        fixture.gate(SleepingGate());
        fixture.routing.admit(builder);
        assertTrue(
                fixture.messages.get(builder.getUniqueId()).stream()
                        .anyMatch(text -> text.contains("просыпается")));
        var event = fixture.connect(builder, "workshop");
        assertFalse(event.getResult().isAllowed(), "sleeping workshop never connects");
    }

    @Test
    void malformedGateKeepsExistingBehaviour(@TempDir Path inbox) throws Exception {
        var fixture = new Fixture("workshop.invalid", inbox);
        Player builder = fixture.player(UUID.randomUUID(), "workshop.invalid", "Builder");
        fixture.authenticate(builder);
        fixture.gate(Gate.parse("{"));
        var denied = fixture.connect(builder, "workshop");
        assertFalse(denied.getResult().isAllowed());
        assertTrue(
                fixture.messages.get(builder.getUniqueId()).stream()
                        .anyMatch(text -> text.contains(Gate.DEFAULT_MESSAGE)),
                "malformed gate falls back to the existing closed message");
        assertEquals(List.of(), fixture.wakes(), "malformed gate never wakes");
        fixture.gate(
                Gate.parse(
                        GateTest.dual(
                                "{\"game\":"
                                        + GateTest.entry("public", "", "")
                                        + ",\"workshop\":"
                                        + GateTest.entry("unknown", "", "")
                                        + "}")));
        assertFalse(fixture.connect(builder, "workshop").getResult().isAllowed());
        assertEquals(List.of(), fixture.wakes(), "unknown workshop state never wakes");
    }

    @Test
    void startingShowsMessageButSendsNoWake(@TempDir Path inbox) throws Exception {
        var fixture = new Fixture("workshop.invalid", inbox);
        Player builder = fixture.player(UUID.randomUUID(), "workshop.invalid", "Builder");
        fixture.authenticate(builder);
        for (String state : List.of("STARTING", "starting")) {
            fixture.gate(
                    new Gate(
                            "public",
                            "game",
                            "",
                            Set.of(),
                            Map.of(
                                    "game", new Gate("public", "game", "", Set.of()),
                                    "workshop",
                                            new Gate(
                                                    state,
                                                    "workshop",
                                                    "Запуск",
                                                    Set.of(new Gate.Member(DRASL, "Builder"))))));
            fixture.routing.admit(builder);
            assertFalse(
                    fixture.connect(builder, "workshop").getResult().isAllowed(),
                    state + " never connects");
        }
        assertTrue(
                fixture.messages.get(builder.getUniqueId()).stream()
                        .anyMatch(text -> text.contains("просыпается")),
                "STARTING shows the waking message");
        assertEquals(List.of(), fixture.wakes(), "STARTING sends no wake request");
    }

    @Test
    void outsiderGetsNoWake(@TempDir Path inbox) throws Exception {
        var fixture = new Fixture("workshop.invalid", inbox);
        Player outsider = fixture.player(UUID.randomUUID(), "workshop.invalid", "Other");
        fixture.authenticate(outsider);
        fixture.gate(SleepingGate());
        assertFalse(fixture.connect(outsider, "workshop").getResult().isAllowed());
        fixture.routing.admit(outsider);
        assertEquals(List.of(), fixture.wakes(), "strangers never wake the workshop");
    }

    @Test
    void gameUnaffectedBySleepingWorkshop(@TempDir Path inbox) throws Exception {
        var fixture = new Fixture("workshop.invalid", inbox);
        Player gamer = fixture.player(UUID.randomUUID(), "game.invalid", "Player");
        fixture.authenticate(gamer);
        fixture.gate(SleepingGate());
        assertEquals(fixture.servers.get("game"), fixture.choose(gamer));
        assertTrue(fixture.connect(gamer, "game").getResult().isAllowed());
        assertEquals(List.of(), fixture.wakes(), "game traffic never wakes the workshop");
    }

    private static String member(String name) {
        return "{\"draslId\":\"" + DRASL + "\",\"name\":\"" + name + "\"}";
    }

    private static Gate SleepingGate() {
        return new Gate(
                "public",
                "game",
                "",
                Set.of(),
                Map.of(
                        "game", new Gate("public", "game", "", Set.of()),
                        "workshop",
                                new Gate(
                                        "sleeping",
                                        "workshop",
                                        "Спит",
                                        Set.of(new Gate.Member(DRASL, "Builder")))));
    }

    private static final class Fixture {
        final Map<String, RegisteredServer> servers = new HashMap<>();
        final List<Player> players = new ArrayList<>();
        final Map<UUID, RegisteredServer> current = new HashMap<>();
        final Map<UUID, List<String>> requests = new HashMap<>();
        final Map<UUID, List<String>> messages = new HashMap<>();
        final Authentication authentication;
        final BackendRouting routing;
        final Path inbox;

        Fixture(String host, Path inbox) {
            this.inbox = inbox;
            for (String name : List.of("lobby", "game", "workshop")) {
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
                                                                    "[IP_ADDRESS]", 1));
                                            case "getPlayersConnected" -> playersOn(name);
                                            case "ping" -> new CompletableFuture<>();
                                            default -> null;
                                        }));
            }
            Scheduler scheduler =
                    fake(
                            Scheduler.class,
                            (method, args) -> {
                                if (!method.equals("buildTask")) return null;
                                Runnable task = (Runnable) args[1];
                                return fake(
                                        Scheduler.TaskBuilder.class,
                                        (nested, nestedArgs) -> {
                                            if (!nested.equals("schedule")) return null;
                                            task.run();
                                            return fake(
                                                    ScheduledTask.class,
                                                    (ignored, ignoredArgs) -> null);
                                        });
                            });
            ProxyServer proxy =
                    fake(
                            ProxyServer.class,
                            (method, args) ->
                                    switch (method) {
                                        case "getServer" ->
                                                Optional.ofNullable(servers.get((String) args[0]));
                                        case "getAllPlayers" -> players;
                                        case "getScheduler" -> scheduler;
                                        default -> null;
                                    });
            authentication =
                    new Authentication(
                            this, proxy, LoggerFactory.getLogger("test"), new LoginForwarding());
            routing =
                    new BackendRouting(
                            this,
                            proxy,
                            LoggerFactory.getLogger("test"),
                            authentication,
                            host,
                            inbox);
        }

        private List<Player> playersOn(String name) {
            return players.stream()
                    .filter(player -> current.get(player.getUniqueId()) == servers.get(name))
                    .toList();
        }

        Player player(UUID id, String host, String name) {
            current.put(id, servers.get("lobby"));
            requests.put(id, new ArrayList<>());
            messages.put(id, new ArrayList<>());
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
                                            messages.get(id).add(String.valueOf(args[0]));
                                            yield null;
                                        }
                                        case "isActive" -> true;
                                        default -> null;
                                    });
            players.add(player);
            return player;
        }

        private Object request(UUID id, RegisteredServer server) {
            return fake(
                    com.velocitypowered.api.proxy.ConnectionRequestBuilder.class,
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

        void gate(Gate gate) throws Exception {
            Field field = BackendRouting.class.getDeclaredField("gate");
            field.setAccessible(true);
            field.set(routing, gate);
        }

        List<Path> wakes() throws Exception {
            if (inbox == null) return List.of();
            try (var stream = Files.list(inbox)) {
                return stream.filter(path -> path.getFileName().toString().endsWith(".json"))
                        .toList();
            }
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

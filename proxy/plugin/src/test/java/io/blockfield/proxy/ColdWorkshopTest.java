package io.blockfield.proxy;

import static io.blockfield.proxy.GateEvictionTest.fake;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.scheduler.ScheduledTask;
import com.velocitypowered.api.proxy.scheduler.Scheduler;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Cold Workshop: lobby hold, waking message, and the CP wake POST. */
class ColdWorkshopTest {
    private static final String DRASL = "6727890496a93613a33463780ae01220";
    private static final String GENERATION = "123e4567-e89b-12d3-a456-426614174000";

    @Test
    void sleepingGateNeverAdmitsButKeepsMembers() {
        Gate gate = GateTest.dual("public", "sleeping", DRASL, "Builder");
        Gate workshop = gate.forBackend("workshop");
        assertTrue(workshop.sleeping());
        assertTrue(workshop.waking());
        assertFalse(gate.allows("workshop", DRASL, "Builder", false));
        assertTrue(workshop.member(DRASL, "Builder"));
        assertFalse(workshop.member(DRASL, "Impostor"));
    }

    @Test
    void sleepingMemberGetsMessageAndOneWakePost() throws Exception {
        var fixture = new Fixture("workshop.invalid");
        Player builder = fixture.player(UUID.randomUUID(), "workshop.invalid", "Builder");
        fixture.authenticate(builder);
        fixture.gate(SleepingGate());
        fixture.routing.admit(builder);
        assertTrue(
                fixture.messages.get(builder.getUniqueId()).stream()
                        .anyMatch(text -> text.contains("просыпается")),
                "sleeping workshop shows the waking message");
        List<String> wakes = fixture.wakes();
        assertEquals(1, wakes.size(), "one wake POST per sleeping member");
        JsonObject request = JsonParser.parseString(wakes.get(0)).getAsJsonObject();
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
        assertEquals("workshop", request.get("consumerId").getAsString());
        assertEquals(GENERATION, request.get("generation").getAsString());
        assertEquals("*", request.get("target").getAsString());
        assertEquals(30, request.get("expiresInSeconds").getAsInt());
        UUID.fromString(request.get("idempotencyKey").getAsString());
        JsonObject actor = request.getAsJsonObject("actor");
        assertEquals(Set.of("draslId", "name"), actor.keySet());
        assertEquals(DRASL, actor.get("draslId").getAsString());
        assertEquals("Builder", actor.get("name").getAsString());
    }

    @Test
    void wakeRateLimitedToOnePerMinutePerProxy() throws Exception {
        var fixture = new Fixture("workshop.invalid");
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
        assertEquals(1, fixture.wakes().size(), "one wake POST per 60 s per proxy");
        // A later connect attempt past the limit posts again, with its own key.
        Field lastWake = BackendRouting.class.getDeclaredField("lastWakeMs");
        lastWake.setAccessible(true);
        lastWake.set(fixture.routing, 0L);
        fixture.routing.admit(second);
        assertEquals(2, fixture.wakes().size(), "next attempt past the limit posts again");
        assertNotEquals(
                idempotencyKey(fixture.wakes().get(0)),
                idempotencyKey(fixture.wakes().get(1)),
                "idempotency key is per connect attempt");
    }

    private static String idempotencyKey(String body) {
        return JsonParser.parseString(body).getAsJsonObject().get("idempotencyKey").getAsString();
    }

    @Test
    void unconfiguredWakeShowsMessageOnly() throws Exception {
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
        assertEquals(List.of(), fixture.wakes(), "unconfigured wake never posts");
    }

    @Test
    void malformedGateKeepsExistingBehaviour() throws Exception {
        var fixture = new Fixture("workshop.invalid");
        Player builder = fixture.player(UUID.randomUUID(), "workshop.invalid", "Builder");
        fixture.authenticate(builder);
        fixture.gate(Gate.closed());
        var denied = fixture.connect(builder, "workshop");
        assertFalse(denied.getResult().isAllowed());
        assertTrue(
                fixture.messages.get(builder.getUniqueId()).stream()
                        .anyMatch(text -> text.contains(Gate.DEFAULT_MESSAGE)),
                "closed gate falls back to the existing closed message");
        assertEquals(List.of(), fixture.wakes(), "closed gate never wakes");
        fixture.gate(GateTest.dual("public", "unknown", null, null));
        assertFalse(fixture.connect(builder, "workshop").getResult().isAllowed());
        assertEquals(List.of(), fixture.wakes(), "unknown workshop state never wakes");
    }

    @Test
    void startingShowsMessageButSendsNoWake() throws Exception {
        var fixture = new Fixture("workshop.invalid");
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
        assertEquals(List.of(), fixture.wakes(), "STARTING sends no wake POST");
    }

    @Test
    void outsiderGetsNoWake() throws Exception {
        var fixture = new Fixture("workshop.invalid");
        Player outsider = fixture.player(UUID.randomUUID(), "workshop.invalid", "Other");
        fixture.authenticate(outsider);
        fixture.gate(SleepingGate());
        assertFalse(fixture.connect(outsider, "workshop").getResult().isAllowed());
        fixture.routing.admit(outsider);
        assertEquals(List.of(), fixture.wakes(), "strangers never wake the workshop");
    }

    @Test
    void gameUnaffectedBySleepingWorkshop() throws Exception {
        var fixture = new Fixture("workshop.invalid");
        Player gamer = fixture.player(UUID.randomUUID(), "game.invalid", "Player");
        fixture.authenticate(gamer);
        fixture.gate(SleepingGate());
        assertEquals(fixture.servers.get("game"), fixture.choose(gamer));
        assertTrue(fixture.connect(gamer, "game").getResult().isAllowed());
        assertEquals(List.of(), fixture.wakes(), "game traffic never wakes the workshop");
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
        final List<String> posts = new ArrayList<>();
        final Authentication authentication;
        final BackendRouting routing;

        Fixture(String host) {
            this(host, null);
            setWake(
                    new WorkshopWake(
                            URI.create("https://cp.invalid/v2/workshop/wake"),
                            () -> "token",
                            "workshop",
                            GENERATION,
                            "prod",
                            body -> {
                                posts.add(body);
                                return 202;
                            },
                            LoggerFactory.getLogger("test")));
        }

        Fixture(String host, WorkshopWake wake) {
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
                            this, proxy, LoggerFactory.getLogger("test"), authentication, host);
            setWake(wake);
        }

        private void setWake(WorkshopWake wake) {
            try {
                Field field = BackendRouting.class.getDeclaredField("wake");
                field.setAccessible(true);
                field.set(routing, wake);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
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

        List<String> wakes() {
            return List.copyOf(posts);
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

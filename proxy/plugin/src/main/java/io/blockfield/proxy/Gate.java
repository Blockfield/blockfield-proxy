package io.blockfield.proxy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Вход на backend по gate.json host-контроллера мастерской (tools/workshop, blockfield-releases#8).
 * Без BLOCKFIELD_GATE_FILE прокси работает как раньше; с ним отсутствующий, битый или незнакомый
 * файл закрывает вход.
 */
record Gate(
        String state,
        String backend,
        String message,
        Set<Member> allowed,
        Map<String, Gate> backends,
        boolean deploying) {
    static final String DEFAULT_MESSAGE =
            "На сервере проводятся технические работы. Обычный вход временно недоступен.";
    static final Gate OPEN = new Gate("public", "game", "", Set.of());
    private static final Pattern DRASL_ID = Pattern.compile("[0-9a-f]{32}");
    private static final int MAX_BYTES = 256 * 1024;

    record Member(String draslId, String name) {}

    Gate(String state, String backend, String message, Set<Member> allowed) {
        this(state, backend, message, allowed, Map.of(), false);
    }

    Gate(
            String state,
            String backend,
            String message,
            Set<Member> allowed,
            Map<String, Gate> backends) {
        this(state, backend, message, allowed, backends, false);
    }

    Gate(String state, String backend, String message, Set<Member> allowed, boolean deploying) {
        this(state, backend, message, allowed, Map.of(), deploying);
    }

    static Gate closed() {
        return new Gate("closed", "game", DEFAULT_MESSAGE, Set.of());
    }

    /**
     * Control-plane projection (U1): CP modes open/draining/maintenance. Unknown or corrupt answers
     * fail closed; draining keeps its message (entry stays open for game members, unlike
     * maintenance).
     */
    static Gate fromCp(String mode, String message) {
        String msg = message == null ? "" : message.strip();
        if (msg.length() > 500) msg = msg.substring(0, 500);
        return switch (mode) {
            case "open" -> OPEN;
            case "draining" -> new Gate("draining", "game", msg, Set.of());
            case "maintenance" ->
                    new Gate("closed", "game", msg.isEmpty() ? DEFAULT_MESSAGE : msg, Set.of());
            default -> closed();
        };
    }

    /** This file gate with the control-plane decision for game entry; Workshop rules unchanged. */
    Gate withGame(Gate controlPlane) {
        Gate game = controlPlane.forBackend("game");
        // A Game deploy in progress survives the control-plane merge: the CP decides entry,
        // the gate file carries the deploy flag.
        boolean deploying = forBackend("game").deploying;
        Gate merged = new Gate(game.state, game.backend, game.message, game.allowed, deploying);
        return new Gate(
                merged.state,
                merged.backend,
                merged.message,
                merged.allowed,
                Map.of("game", merged, "workshop", forBackend("workshop")),
                deploying);
    }

    Gate forBackend(String server) {
        if (!backends.isEmpty()) return backends.getOrDefault(server, closed());
        return server.equals(backend) ? this : closed();
    }

    boolean open() {
        return state.equals("public");
    }

    /** Cold Workshop: the controller stopped the backend to save RAM; members may wake it. */
    boolean sleeping() {
        return state.equals("sleeping");
    }

    /** Sleeping or already starting after a wake: do not connect, show the waking message. */
    boolean waking() {
        return sleeping() || state.equals("starting") || state.equals("STARTING");
    }

    boolean member(String draslId, String name) {
        return draslId != null && allowed.contains(new Member(normalizeId(draslId), name));
    }

    static String normalizeId(String id) {
        return id == null ? null : id.replace("-", "").toLowerCase(Locale.ROOT);
    }

    /**
     * @param gameMember игрок сейчас или недавно был на игровом сервере (доигрывает DRAINING)
     */
    boolean allows(String server, String draslId, String name, boolean gameMember) {
        Gate selected = forBackend(server);
        if (!server.equals(selected.backend)) return false;
        return switch (selected.state) {
            case "public" -> true;
            case "draining" -> gameMember;
            // testers: closed playtest of a workshop candidate on the same backend, same member
            // list. sleeping/starting never admit: BackendRouting parks members in the lobby.
            case "builders", "testers" -> selected.member(draslId, name);
            default -> false;
        };
    }

    static Gate load(Path path) {
        if (path == null) return OPEN;
        try (InputStream in = Files.newInputStream(path)) {
            byte[] data = in.readNBytes(MAX_BYTES + 1);
            return data.length > MAX_BYTES
                    ? closed()
                    : parse(new String(data, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return closed();
        }
    }

    static Gate parse(String text) {
        try {
            JsonObject json = JsonParser.parseString(text).getAsJsonObject();
            JsonElement schema = json.get("schema");
            if (!schema.isJsonPrimitive()
                    || !schema.getAsJsonPrimitive().isNumber()
                    || !(schema.getAsString().equals("1") || schema.getAsString().equals("2")))
                return closed();
            Gate legacy = parseEntry(json, string(json, "backend"));
            if (!json.has("backends")) return legacy;
            JsonObject entries = json.getAsJsonObject("backends");
            // A present backends object is authoritative, including missing or corrupt entries.
            Map<String, Gate> backends =
                    Map.of(
                            "game", parseBackend(entries, "game"),
                            "workshop", parseBackend(entries, "workshop"));
            return new Gate(legacy.state, legacy.backend, legacy.message, legacy.allowed, backends);
        } catch (RuntimeException e) {
            return closed();
        }
    }

    /**
     * Deploy-in-progress flag (schema-2 readers use it, schema-1 never has it): absent is false,
     * any other shape fails closed with the entry.
     */
    private static boolean optionalDeploying(JsonObject json) {
        if (!json.has("deploying")) return false;
        JsonElement value = json.get("deploying");
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException("Invalid deploying flag");
        }
        return value.getAsBoolean();
    }

    private static Gate parseBackend(JsonObject entries, String backend) {
        try {
            return parseEntry(entries.getAsJsonObject(backend), backend);
        } catch (RuntimeException e) {
            return new Gate("closed", backend, DEFAULT_MESSAGE, Set.of());
        }
    }

    private static String string(JsonObject json, String key) {
        JsonElement value = json.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Expected a string");
        }
        return value.getAsString();
    }

    private static Gate parseEntry(JsonObject json, String backend) {
        String state = string(json, "gate");
        boolean validState =
                switch (backend) {
                    case "game" -> Set.of("public", "draining", "closed").contains(state);
                    // sleeping/STARTING come from the cold-Workshop controller; STARTING keeps
                    // its phase name, so both cases are accepted here.
                    case "workshop" ->
                            Set.of(
                                            "builders",
                                            "testers",
                                            "closed",
                                            "sleeping",
                                            "starting",
                                            "STARTING")
                                    .contains(state);
                    default -> false;
                };
        if (!validState || json.has("backend") && !backend.equals(string(json, "backend"))) {
            throw new IllegalArgumentException("Unknown gate or backend");
        }
        String message = string(json, "message").strip();
        if (message.length() > 500) message = message.substring(0, 500);
        if (message.isEmpty() && !state.equals("public")) message = DEFAULT_MESSAGE;
        Set<Member> allowed = new HashSet<>();
        JsonArray members = json.getAsJsonArray("allowed");
        for (JsonElement element : members) {
            JsonObject member = element.getAsJsonObject();
            String id = normalizeId(string(member, "draslId"));
            String name = string(member, "name");
            if (!DRASL_ID.matcher(id).matches() || !Authentication.NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("Invalid gate member");
            }
            allowed.add(new Member(id, name));
        }
        return new Gate(state, backend, message, Set.copyOf(allowed), optionalDeploying(json));
    }
}

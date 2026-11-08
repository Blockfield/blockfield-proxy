package io.blockfield.proxy;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Admission state for one backend. The only runtime source is the control-plane push/ACK consumer
 * ({@link ControlSubscriber}): {@link Gate#fromCp} maps the CP game value, {@link
 * Gate#fromWorkshop} the CP workshop leg, anything unknown or stale fails closed. (CP-C2 cutover
 * removed the {@code gate.json} file gate and its parser.)
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
     * Control-plane projection: CP modes open/draining/maintenance. Unknown or corrupt answers fail
     * closed; draining keeps its message (entry stays open for game members, unlike maintenance).
     */
    static Gate fromCp(String mode, String message) {
        String msg = message == null ? "" : message.strip();
        if (msg.length() > 500) msg = msg.substring(0, 500);
        // A missing mode never opens entry: switch would NPE on null out of offer().
        if (mode == null) return closed();
        return switch (mode) {
            case "open" -> OPEN;
            case "draining" -> new Gate("draining", "game", msg, Set.of());
            case "maintenance" ->
                    new Gate("closed", "game", msg.isEmpty() ? DEFAULT_MESSAGE : msg, Set.of());
            default -> closed();
        };
    }

    Gate forBackend(String server) {
        if (!backends.isEmpty()) return backends.getOrDefault(server, closed());
        return server.equals(backend) ? this : closed();
    }

    /**
     * Dual-leg composite from single legs; the top level carries the game leg. Missing or corrupt
     * legs read closed, so a half-known CP answer never opens entry.
     */
    static Gate dual(Gate game, Gate workshop) {
        Gate g = single(game, "game");
        Gate w = single(workshop, "workshop");
        return new Gate(g.state, g.backend, g.message, g.allowed, Map.of("game", g, "workshop", w));
    }

    /** Applies one CP leg, keeping the other leg untouched (frames carry one target). */
    Gate withLeg(String target, Gate leg) {
        if ("workshop".equals(target)) return dual(forBackend("game"), leg);
        return dual(leg, forBackend("workshop"));
    }

    private static Gate single(Gate leg, String backend) {
        Gate s = leg == null ? closed() : leg.forBackend(backend);
        return new Gate(s.state, backend, s.message, s.allowed);
    }

    /**
     * Control-plane projection of the workshop leg (CP serves the controller states the file gate
     * carried). Unknown states, missing shapes and invalid members fail the leg closed; STARTING
     * keeps its phase name, like the file gate did.
     */
    static Gate fromWorkshop(JsonObject value) {
        try {
            if (value == null) throw new IllegalArgumentException("Missing workshop value");
            String state = string(value, "state");
            if (!Set.of("builders", "testers", "closed", "sleeping", "starting", "STARTING")
                    .contains(state)) throw new IllegalArgumentException("Unknown workshop state");
            String message = string(value, "message").strip();
            if (message.length() > 500) message = message.substring(0, 500);
            if (message.isEmpty()) message = DEFAULT_MESSAGE;
            JsonElement allowed = value.get("allowed");
            if (allowed == null || !allowed.isJsonArray())
                throw new IllegalArgumentException("Missing allowed list");
            Set<Member> members = new HashSet<>();
            for (JsonElement element : allowed.getAsJsonArray()) {
                JsonObject member = element.getAsJsonObject();
                String id = normalizeId(string(member, "draslId"));
                String name = string(member, "name");
                if (!DRASL_ID.matcher(id).matches() || !Authentication.NAME.matcher(name).matches())
                    throw new IllegalArgumentException("Invalid workshop member");
                members.add(new Member(id, name));
            }
            return new Gate(state, "workshop", message, Set.copyOf(members));
        } catch (RuntimeException e) {
            return new Gate("closed", "workshop", DEFAULT_MESSAGE, Set.of());
        }
    }

    private static String string(JsonObject json, String key) {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("Expected a string: " + key);
        return value.getAsString();
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
}

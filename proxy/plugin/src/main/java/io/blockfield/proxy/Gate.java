package io.blockfield.proxy;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Admission state for one backend. The only runtime source is the control-plane push/ACK consumer
 * ({@link ControlSubscriber}): {@link Gate#fromCp} maps the CP gate value, anything unknown or
 * stale fails closed. (CP-C2 cutover removed the {@code gate.json} file gate and its parser.)
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

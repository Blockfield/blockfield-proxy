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
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Вход на backend по gate.json host-контроллера мастерской (tools/workshop, blockfield-releases#8).
 * Без BLOCKFIELD_GATE_FILE прокси работает как раньше; с ним отсутствующий, битый или незнакомый
 * файл закрывает вход.
 */
record Gate(String state, String backend, String message, Set<Member> allowed) {
    static final String DEFAULT_MESSAGE =
            "На сервере проводятся технические работы. Обычный вход временно недоступен.";
    static final Gate OPEN = new Gate("public", "game", "", Set.of());
    private static final Pattern DRASL_ID = Pattern.compile("[0-9a-f]{32}");
    private static final int MAX_BYTES = 256 * 1024;

    record Member(String draslId, String name) {}

    static Gate closed() {
        return new Gate("closed", "game", DEFAULT_MESSAGE, Set.of());
    }

    boolean open() {
        return state.equals("public");
    }

    static String normalizeId(String id) {
        return id == null ? null : id.replace("-", "").toLowerCase(Locale.ROOT);
    }

    /**
     * @param gameMember игрок сейчас или недавно был на игровом сервере (доигрывает DRAINING)
     */
    boolean allows(String server, String draslId, String name, boolean gameMember) {
        if (!server.equals(backend)) return false;
        return switch (state) {
            case "public" -> true;
            case "draining" -> gameMember;
            // testers: closed playtest of a workshop candidate on the same backend, same member
            // list.
            case "builders", "testers" ->
                    draslId != null && allowed.contains(new Member(normalizeId(draslId), name));
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
            if (json.get("schema").getAsInt() != 1) return closed();
            String state = json.get("gate").getAsString();
            String backend = json.get("backend").getAsString();
            if (!Set.of("public", "draining", "closed", "builders", "testers").contains(state)
                    || !Set.of("game", "workshop").contains(backend)) {
                return closed();
            }
            String message = json.get("message").getAsString().strip();
            if (message.length() > 500) message = message.substring(0, 500);
            if (message.isEmpty() && !state.equals("public")) message = DEFAULT_MESSAGE;
            Set<Member> allowed = new HashSet<>();
            JsonArray members = json.getAsJsonArray("allowed");
            for (JsonElement element : members) {
                JsonObject member = element.getAsJsonObject();
                String id = normalizeId(member.get("draslId").getAsString());
                String name = member.get("name").getAsString();
                if (!DRASL_ID.matcher(id).matches() || !Authentication.NAME.matcher(name).matches())
                    return closed();
                allowed.add(new Member(id, name));
            }
            return new Gate(state, backend, message, Set.copyOf(allowed));
        } catch (RuntimeException e) {
            return closed();
        }
    }
}

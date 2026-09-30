package io.blockfield.proxy;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Properties;

/** Prepare the embedded Limbo from one verified native map version before opening any listener. */
public final class LimboSelection {
    private static final int MAX_METADATA = 16 * 1024 * 1024;
    private static final int MAX_SCHEMATIC = 4 * 1024 * 1024;

    private LimboSelection() {}

    public static void main(String[] args) throws IOException {
        String selection = System.getenv("BLOCKFIELD_LIMBO_SELECTION_FILE");
        if (selection == null || selection.isBlank()) return;
        if (args.length != 1) throw new IOException("Limbo runtime directory required");
        String digest =
                prepare(
                        Path.of(selection),
                        environment("BLOCKFIELD_MAP_ACTIVE_DIR"),
                        environment("BLOCKFIELD_LIMBO_ARTIFACTS_DIR"),
                        Path.of(args[0]));
        System.out.println("Limbo map version " + digest);
    }

    private static Path environment(String name) throws IOException {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IOException("Required Limbo path: " + name);
        return Path.of(value);
    }

    static String prepare(Path selectionFile, Path active, Path artifacts, Path runtime)
            throws IOException {
        active = directory(active);
        artifacts = directory(artifacts);
        runtime = directory(runtime);
        selectionFile = checked(selectionFile);
        if (!selectionFile.equals(active.resolve("limbo/selection.json")))
            throw new IOException("Limbo selection must belong to the active map directory");
        byte[] selectionBytes = read(selectionFile, 64 * 1024);
        JsonObject selection = json(selectionBytes);
        identity(selection);
        String digest = checksum(selection, "versionDigest");
        Path headFile = active.resolve("limbo/head.json");
        byte[] headBytes = read(headFile, 64 * 1024);
        JsonObject head = json(headBytes);
        identity(head);
        if (!digest.equals(checksum(head, "versionDigest"))
                || !"limbo".equals(string(head, "kind")))
            throw new IOException("Limbo selection disagrees with the active head");
        Path bundle = directory(active.resolve("limbo/versions").resolve(digest));
        byte[] manifestBytes = read(bundle.resolve("manifest.json"), MAX_METADATA);
        if (!digest.equals(hash(manifestBytes)))
            throw new IOException("Corrupt Limbo map manifest");
        JsonObject manifest = json(manifestBytes);
        identity(manifest);
        if (!"limbo".equals(string(manifest, "kind")))
            throw new IOException("Invalid Limbo map kind");
        byte[] configBytes = read(bundle.resolve("map.json"), MAX_METADATA);
        String configHash = hash(configBytes);
        if (!configHash.equals(checksum(selection, "configSha256"))
                || !configHash.equals(checksum(manifest.getAsJsonObject("files"), "map.json")))
            throw new IOException("Limbo config disagrees with the selected version");
        JsonObject config = json(configBytes);
        if (!"limbo".equals(string(config, "id")) || !"limbo".equals(string(config, "kind")))
            throw new IOException("Invalid Limbo config identity");
        JsonObject data;
        try {
            data = config.getAsJsonObject("mode_data").getAsJsonObject("limbo");
        } catch (RuntimeException error) {
            throw new IOException("Missing Limbo config", error);
        }
        int width = positive(data, "width", 256);
        int height = positive(data, "height", 256);
        int length = positive(data, "length", 256);
        if ((long) width * height * length > 2_000_000
                || width != positive(selection, "width", 256)
                || height != positive(selection, "height", 256)
                || length != positive(selection, "length", 256)
                || number(config.getAsJsonObject("bounds"), "minX") != 0
                || number(config.getAsJsonObject("bounds"), "minZ") != 0
                || number(config.getAsJsonObject("bounds"), "maxX") != width
                || number(config.getAsJsonObject("bounds"), "maxZ") != length
                || !"Sponge-v2".equals(string(selection, "format")))
            throw new IOException("Invalid zero-origin Limbo export bounds");
        Spawn spawn = spawn(data, width, height, length);
        if (!spawn.equals(spawn(selection, width, height, length)))
            throw new IOException("Limbo spawn disagrees with the selected config");
        String schematicHash = checksum(selection, "schematicSha256");
        String filename = digest + "-" + schematicHash + ".schem";
        if (!filename.equals(string(selection, "artifact")))
            throw new IOException("Invalid Limbo artifact name");
        byte[] schematic = read(artifacts.resolve(filename), MAX_SCHEMATIC);
        if (!schematicHash.equals(hash(schematic)))
            throw new IOException("Corrupt Limbo schematic");
        Properties properties = new Properties();
        Path propertiesFile = runtime.resolve("server.properties");
        try (var input = Files.newInputStream(checked(propertiesFile), LinkOption.NOFOLLOW_LINKS)) {
            properties.load(input);
        }
        properties.setProperty("level-name", "world;lobby.schem");
        properties.setProperty(
                "world-spawn",
                "world;"
                        + spawn.x
                        + ";"
                        + spawn.y
                        + ";"
                        + spawn.z
                        + ";"
                        + spawn.yaw
                        + ";"
                        + spawn.pitch);
        StringWriter output = new StringWriter();
        properties.store(output, null);
        if (!Arrays.equals(selectionBytes, read(selectionFile, 64 * 1024))
                || !Arrays.equals(headBytes, read(headFile, 64 * 1024)))
            throw new IOException("Limbo head changed during preparation");
        replace(runtime.resolve("lobby.schem"), schematic);
        replace(propertiesFile, output.toString().getBytes(StandardCharsets.ISO_8859_1));
        return digest;
    }

    private record Spawn(double x, double y, double z, double yaw, double pitch) {}

    private static Spawn spawn(JsonObject object, int width, int height, int length)
            throws IOException {
        try {
            JsonObject value = object.getAsJsonObject("spawn");
            Spawn spawn =
                    new Spawn(
                            number(value, "x"),
                            number(value, "y"),
                            number(value, "z"),
                            value.has("yaw") ? number(value, "yaw") : 0,
                            value.has("pitch") ? number(value, "pitch") : 0);
            if (spawn.x < 0
                    || spawn.x >= width
                    || spawn.y < 0
                    || spawn.y >= height
                    || spawn.z < 0
                    || spawn.z >= length
                    || Math.abs(spawn.pitch) > 90)
                throw new IOException("Limbo spawn is outside the export");
            return spawn;
        } catch (RuntimeException error) {
            throw new IOException("Invalid Limbo spawn", error);
        }
    }

    private static void identity(JsonObject object) throws IOException {
        if (positive(object, "schema", 1) != 1 || !"limbo".equals(string(object, "mapId")))
            throw new IOException("Invalid Limbo metadata identity");
    }

    private static int positive(JsonObject object, String name, int maximum) throws IOException {
        double value = number(object, name);
        if (value < 1 || value > maximum || value != Math.rint(value))
            throw new IOException("Invalid Limbo integer: " + name);
        return (int) value;
    }

    private static double number(JsonObject object, String name) throws IOException {
        try {
            if (!object.getAsJsonPrimitive(name).isNumber()) throw new IllegalArgumentException();
            double value = object.get(name).getAsDouble();
            if (!Double.isFinite(value)) throw new IllegalArgumentException();
            return value;
        } catch (RuntimeException error) {
            throw new IOException("Invalid Limbo number: " + name, error);
        }
    }

    private static String string(JsonObject object, String name) throws IOException {
        try {
            if (!object.getAsJsonPrimitive(name).isString()) throw new IllegalArgumentException();
            return object.get(name).getAsString();
        } catch (RuntimeException error) {
            throw new IOException("Invalid Limbo string: " + name, error);
        }
    }

    private static String checksum(JsonObject object, String name) throws IOException {
        String value = string(object, name);
        if (!value.matches("[0-9a-f]{64}"))
            throw new IOException("Invalid Limbo checksum: " + name);
        return value;
    }

    private static Path checked(Path path) throws IOException {
        if (!path.isAbsolute()) throw new IOException("Absolute Limbo path required");
        path = path.normalize();
        for (Path part = path; part != null; part = part.getParent())
            if (Files.isSymbolicLink(part))
                throw new IOException("Limbo paths cannot contain symlinks");
        return path;
    }

    private static Path directory(Path path) throws IOException {
        path = checked(path);
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Missing Limbo directory");
        return path;
    }

    private static byte[] read(Path path, int maximum) throws IOException {
        path = checked(path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Missing Limbo file");
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(maximum + 1);
            if (bytes.length > maximum) throw new IOException("Oversized Limbo file");
            return bytes;
        }
    }

    private static JsonObject json(byte[] bytes) throws IOException {
        try {
            return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (RuntimeException error) {
            throw new IOException("Invalid Limbo metadata JSON", error);
        }
    }

    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static void replace(Path target, byte[] bytes) throws IOException {
        checked(target);
        Path temporary = Files.createTempFile(target.getParent(), ".limbo-", ".tmp");
        try {
            Files.write(temporary, bytes);
            Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}

package io.blockfield.proxy;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Properties;

class LimboSelectionTest {
    @TempDir Path root;

    private record Fixture(
            Path active,
            Path artifacts,
            Path runtime,
            Path selection,
            Path artifact,
            String digest) {
        String prepare() throws IOException {
            return LimboSelection.prepare(selection, active, artifacts, runtime);
        }
    }

    private Fixture fixture() throws Exception {
        Path active = Files.createDirectory(root.resolve("active"));
        Path artifacts = Files.createDirectory(root.resolve("artifacts"));
        Path runtime = Files.createDirectory(root.resolve("runtime"));
        Files.writeString(
                runtime.resolve("server.properties"),
                "server-port=30000\nforwarding-secrets=test-value\nworld-spawn=old\n");
        Files.write(runtime.resolve("lobby.schem"), new byte[] {9});
        String config =
                """
                {"id":"limbo","kind":"limbo","name":"Limbo",
                 "bounds":{"minX":0,"maxX":176,"minZ":0,"maxZ":176},
                 "mode_data":{"limbo":{"width":176,"height":48,"length":176,
                   "spawn":{"x":88.5,"y":17,"z":99.5,"yaw":180,"pitch":0}}}}
                """;
        String configHash = hash(config.getBytes(StandardCharsets.UTF_8));
        String manifest =
                "{\"schema\":1,\"mapId\":\"limbo\",\"kind\":\"limbo\",\"files\":{\"map.json\":\""
                        + configHash
                        + "\"}}";
        String digest = hash(manifest.getBytes(StandardCharsets.UTF_8));
        Path map = Files.createDirectory(active.resolve("limbo"));
        Path bundle = Files.createDirectories(map.resolve("versions").resolve(digest));
        Files.writeString(bundle.resolve("manifest.json"), manifest);
        Files.writeString(bundle.resolve("map.json"), config);
        Files.writeString(
                map.resolve("head.json"),
                "{\"schema\":1,\"mapId\":\"limbo\",\"kind\":\"limbo\",\"versionDigest\":\""
                        + digest
                        + "\"}");
        byte[] schematic = new byte[] {1, 2, 3, 4};
        String schematicHash = hash(schematic);
        String artifact = digest + "-" + schematicHash + ".schem";
        Files.write(artifacts.resolve(artifact), schematic);
        JsonObject selection =
                JsonParser.parseString(
                                """
                                {"schema":1,"mapId":"limbo","format":"Sponge-v2","width":176,"height":48,"length":176,
                                 "spawn":{"x":88.5,"y":17,"z":99.5,"yaw":180,"pitch":0}}
                                """)
                        .getAsJsonObject();
        selection.addProperty("versionDigest", digest);
        selection.addProperty("configSha256", configHash);
        selection.addProperty("schematicSha256", schematicHash);
        selection.addProperty("artifact", artifact);
        Path selectionFile = map.resolve("selection.json");
        Files.writeString(selectionFile, selection.toString());
        return new Fixture(
                active, artifacts, runtime, selectionFile, artifacts.resolve(artifact), digest);
    }

    @Test
    void preparesExactSelectedGeometryAndSpawnPreservingOtherProperties() throws Exception {
        Fixture fixture = fixture();
        assertEquals(fixture.digest, fixture.prepare());
        assertArrayEquals(
                Files.readAllBytes(fixture.artifact),
                Files.readAllBytes(fixture.runtime.resolve("lobby.schem")));
        Properties properties = new Properties();
        try (var input = Files.newInputStream(fixture.runtime.resolve("server.properties"))) {
            properties.load(input);
        }
        assertEquals("world;lobby.schem", properties.getProperty("level-name"));
        assertEquals("world;88.5;17.0;99.5;180.0;0.0", properties.getProperty("world-spawn"));
        assertEquals("30000", properties.getProperty("server-port"));
        assertEquals("test-value", properties.getProperty("forwarding-secrets"));
    }

    @Test
    void rejectsCorruptArtifactWithoutReplacingRuntime() throws Exception {
        Fixture fixture = fixture();
        Files.write(fixture.artifact, new byte[] {4, 3, 2, 1});
        assertThrows(IOException.class, fixture::prepare);
        assertArrayEquals(
                new byte[] {9}, Files.readAllBytes(fixture.runtime.resolve("lobby.schem")));
    }

    @Test
    void refusesStaleHeadAndMissingMetadata() throws Exception {
        Fixture fixture = fixture();
        Path head = fixture.active.resolve("limbo/head.json");
        Files.writeString(head, Files.readString(head).replace(fixture.digest, "a".repeat(64)));
        assertThrows(IOException.class, fixture::prepare);
        Files.delete(fixture.selection);
        assertThrows(IOException.class, fixture::prepare);
    }

    @Test
    void bindsDimensionsAndSpawnToVerifiedNativeConfig() throws Exception {
        Fixture fixture = fixture();
        String original = Files.readString(fixture.selection);
        for (String modified :
                new String[] {
                    original.replace("\"width\":176", "\"width\":175"),
                    original.replace("\"y\":17", "\"y\":18"),
                    original.replace("\"artifact\":\"", "\"artifact\":\"../")
                }) {
            Files.writeString(fixture.selection, modified);
            assertThrows(IOException.class, fixture::prepare);
        }
        Files.writeString(fixture.selection, original);
        Files.writeString(
                fixture.active
                        .resolve("limbo/versions")
                        .resolve(fixture.digest)
                        .resolve("map.json"),
                "{}");
        assertThrows(IOException.class, fixture::prepare);
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}

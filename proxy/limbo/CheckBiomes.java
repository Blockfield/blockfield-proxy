import com.loohp.limbo.network.protocol.packets.ClientboundLevelChunkWithLightPacket;
import com.loohp.limbo.registry.RegistryCustom;
import com.loohp.limbo.world.Environment;

import net.kyori.adventure.key.Key;
import net.querz.mca.Chunk;
import net.querz.nbt.tag.CompoundTag;
import net.querz.nbt.tag.ListTag;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.util.ArrayList;
import java.util.Collections;

class CheckBiomes {
    public static void main(String[] args) throws Exception {
        var registry = RegistryCustom.WORLDGEN_BIOME.getEntries();
        var entries = new ArrayList<>(registry.entrySet());
        System.out.println("Original biome 39: " + entries.get(39).getKey());
        // Registry IDs follow JAR resource order, so exercise every possible index.
        for (int shift = 0; shift < entries.size(); shift++) {
            check(Environment.NORMAL, "minecraft:plains");
            check(Environment.NETHER, "minecraft:nether_wastes");
            check(Environment.END, "minecraft:the_end");
            Collections.rotate(entries, 1);
            registry.clear();
            for (var entry : entries) registry.put(entry.getKey(), entry.getValue());
        }
        System.out.println("PASS: every chunk section uses its named biome across registry orders");
    }

    private static void check(Environment environment, String name) throws Exception {
        int expected = RegistryCustom.WORLDGEN_BIOME.indexOf(Key.key(name));
        require(expected >= 0, "Missing biome " + name);
        Chunk chunk = Chunk.newChunk();
        chunk.setHeightMaps(new CompoundTag());
        chunk.setTileEntities(new ListTag<>(CompoundTag.class));
        var packet =
                new ClientboundLevelChunkWithLightPacket(
                        0,
                        0,
                        chunk,
                        environment,
                        Collections.nCopies(18, null),
                        Collections.nCopies(18, null));
        var input = new DataInputStream(new ByteArrayInputStream(packet.serializePacket()));
        input.readUnsignedByte();
        input.readInt();
        input.readInt();
        require(
                input.readUnsignedByte() == 10 && input.readUnsignedByte() == 0,
                "Expected empty heightmap compound");
        int size = readVarInt(input);
        int start = input.available();
        for (int section = 0; section < 16; section++) {
            require(input.readUnsignedShort() == 0, "Expected empty section");
            require(input.readUnsignedByte() == 0, "Expected single block palette");
            require(readVarInt(input) == 0 && readVarInt(input) == 0, "Expected air palette");
            require(input.readUnsignedByte() == 0, "Expected single biome palette");
            int actual = readVarInt(input);
            require(
                    actual == expected,
                    name + ": expected registry ID " + expected + ", got " + actual);
            require(readVarInt(input) == 0, "Expected empty biome data array");
        }
        require(start - input.available() == size, "Unexpected section data size");
    }

    private static int readVarInt(DataInputStream input) throws Exception {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int next = input.readUnsignedByte();
            value |= (next & 127) << shift;
            if (next < 128) return value;
        }
        throw new AssertionError("Invalid VarInt");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

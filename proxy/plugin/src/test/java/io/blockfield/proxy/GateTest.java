package io.blockfield.proxy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class GateTest {
    static final String ID = "e10438e4797b49718cfecec6a8fa171b";

    static String gate(String state, String backend, String allowed) {
        return "{\"schema\":1,\"revision\":3,\"gate\":\"" + state + "\",\"backend\":\"" + backend
                + "\",\"message\":\"Техработы\",\"allowed\":[" + allowed + "]}";
    }

    @Test
    void withoutGateFileProxyStaysOpen() {
        assertSame(Gate.OPEN, Gate.load(null));
        assertTrue(Gate.OPEN.allows("game", null, "Anyone", false));
        assertFalse(Gate.OPEN.allows("workshop", ID, "Nether", false));
    }

    @Test
    void missingCorruptOrUnknownFailsClosed(@TempDir Path dir) throws Exception {
        assertEquals("closed", Gate.load(dir.resolve("missing.json")).state());
        Path file = dir.resolve("gate.json");
        for (String bad : new String[]{"{", "[]", "{\"schema\":2,\"gate\":\"public\",\"backend\":\"game\",\"message\":\"\",\"allowed\":[]}",
                gate("staging", "game", ""), gate("public", "staging", ""), gate("builders", "workshop", "{\"draslId\":\"x\",\"name\":\"Nether\"}"),
                "{\"schema\":1,\"gate\":\"public\",\"backend\":\"game\"}"}) {
            Files.writeString(file, bad);
            Gate loaded = Gate.load(file);
            assertEquals("closed", loaded.state(), bad);
            assertEquals(Gate.DEFAULT_MESSAGE, loaded.message());
            assertFalse(loaded.allows("game", ID, "Nether", true));
        }
        Files.writeString(file, "{\"schema\":1,\"gate\":\"public\",\"backend\":\"game\",\"message\":\"" + "x".repeat(300_000) + "\",\"allowed\":[]}");
        assertEquals("closed", Gate.load(file).state());
    }

    @Test
    void publicAndDraining() {
        Gate open = Gate.parse(gate("public", "game", ""));
        assertTrue(open.open());
        assertTrue(open.allows("game", null, "Anyone", false));
        assertFalse(open.allows("workshop", ID, "Nether", false));
        Gate draining = Gate.parse(gate("draining", "game", ""));
        assertFalse(draining.open());
        assertTrue(draining.allows("game", null, "Member", true));
        assertFalse(draining.allows("game", ID, "Nether", false));
    }

    @Test
    void testersAreTheWorkshopMembersOnTheWorkshopBackend() {
        Gate testers = Gate.parse(gate("testers", "workshop", "{\"draslId\":\"" + ID + "\",\"name\":\"Nether\"}"));
        assertTrue(testers.allows("workshop", ID, "Nether", false));
        assertFalse(testers.allows("workshop", ID, "Impostor", false));
        assertFalse(testers.allows("game", ID, "Nether", true));
    }

    @Test
    void buildersNeedDraslIdAndName() {
        Gate builders = Gate.parse(gate("builders", "workshop", "{\"draslId\":\"E10438E4-797B-4971-8CFE-CEC6A8FA171B\",\"name\":\"Nether\",\"role\":\"admin\"}"));
        assertEquals("Техработы", builders.message());
        assertTrue(builders.allows("workshop", ID, "Nether", false));
        assertTrue(builders.allows("workshop", "e10438e4-797b-4971-8cfe-cec6a8fa171b", "Nether", false));
        assertFalse(builders.allows("workshop", ID, "Impostor", false));
        assertFalse(builders.allows("workshop", "00000000000000000000000000000000", "Nether", false));
        assertFalse(builders.allows("workshop", null, "Nether", false));
        assertFalse(builders.allows("game", ID, "Nether", true));
        assertFalse(Gate.parse(gate("closed", "workshop", "")).allows("workshop", ID, "Nether", true));
    }

    @Test
    void emptyMessageFallsBackOutsidePublic() {
        Gate gate = Gate.parse("{\"schema\":1,\"gate\":\"closed\",\"backend\":\"game\",\"message\":\" \",\"allowed\":[]}");
        assertEquals(Gate.DEFAULT_MESSAGE, gate.message());
    }
}

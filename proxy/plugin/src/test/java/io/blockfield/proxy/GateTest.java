package io.blockfield.proxy;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

class GateTest {
    static final String ID = "e10438e4797b49718cfecec6a8fa171b";

    static String gate(String state, String backend, String allowed) {
        return "{\"schema\":1,\"revision\":3,\"gate\":\""
                + state
                + "\",\"backend\":\""
                + backend
                + "\",\"message\":\"Техработы\",\"allowed\":["
                + allowed
                + "]}";
    }

    static String dual(String entries) {
        String legacy = gate("public", "game", "");
        return legacy.substring(0, legacy.length() - 1) + ",\"backends\":" + entries + "}";
    }

    static String entry(String state, String message, String members) {
        return "{\"gate\":\""
                + state
                + "\",\"message\":\""
                + message
                + "\",\"allowed\":["
                + members
                + "]}";
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
        for (String bad :
                new String[] {
                    "{",
                    "[]",
                    "{\"schema\":3,\"gate\":\"public\",\"backend\":\"game\",\"message\":\"\",\"allowed\":[]}",
                    gate("staging", "game", ""),
                    gate("public", "staging", ""),
                    gate("builders", "workshop", "{\"draslId\":\"x\",\"name\":\"Nether\"}"),
                    "{\"schema\":1,\"gate\":\"public\",\"backend\":\"game\"}"
                }) {
            Files.writeString(file, bad);
            Gate loaded = Gate.load(file);
            assertEquals("closed", loaded.state(), bad);
            assertEquals(Gate.DEFAULT_MESSAGE, loaded.message());
            assertFalse(loaded.allows("game", ID, "Nether", true));
        }
        Files.writeString(
                file,
                "{\"schema\":1,\"gate\":\"public\",\"backend\":\"game\",\"message\":\""
                        + "x".repeat(300_000)
                        + "\",\"allowed\":[]}");
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
        Gate testers =
                Gate.parse(
                        gate(
                                "testers",
                                "workshop",
                                "{\"draslId\":\"" + ID + "\",\"name\":\"Nether\"}"));
        assertTrue(testers.allows("workshop", ID, "Nether", false));
        assertFalse(testers.allows("workshop", ID, "Impostor", false));
        assertFalse(testers.allows("game", ID, "Nether", true));
    }

    @Test
    void buildersNeedDraslIdAndName() {
        Gate builders =
                Gate.parse(
                        gate(
                                "builders",
                                "workshop",
                                "{\"draslId\":\"E10438E4-797B-4971-8CFE-CEC6A8FA171B\",\"name\":\"Nether\",\"role\":\"admin\"}"));
        assertEquals("Техработы", builders.message());
        assertTrue(builders.allows("workshop", ID, "Nether", false));
        assertTrue(
                builders.allows(
                        "workshop", "e10438e4-797b-4971-8cfe-cec6a8fa171b", "Nether", false));
        assertFalse(builders.allows("workshop", ID, "Impostor", false));
        assertFalse(
                builders.allows("workshop", "00000000000000000000000000000000", "Nether", false));
        assertFalse(builders.allows("workshop", null, "Nether", false));
        assertFalse(builders.allows("game", ID, "Nether", true));
        assertFalse(
                Gate.parse(gate("closed", "workshop", "")).allows("workshop", ID, "Nether", true));
    }

    @Test
    void emptyMessageFallsBackOutsidePublic() {
        Gate gate =
                Gate.parse(
                        "{\"schema\":1,\"gate\":\"closed\",\"backend\":\"game\",\"message\":\""
                                + " \",\"allowed\":[]}");
        assertEquals(Gate.DEFAULT_MESSAGE, gate.message());
    }

    @Test
    void gameAndRestrictedWorkshopOpenIndependently() {
        for (String state : new String[] {"builders", "testers"}) {
            Gate gate =
                    Gate.parse(
                            dual(
                                    "{\"game\":"
                                            + entry("public", "Game", "")
                                            + ",\"workshop\":"
                                            + entry(
                                                    state,
                                                    "Workshop",
                                                    "{\"draslId\":\""
                                                            + ID
                                                            + "\",\"name\":\"Nether\"}")
                                            + "}"));
            assertTrue(gate.allows("game", null, "Anyone", false));
            assertTrue(gate.allows("workshop", ID, "Nether", false));
            assertFalse(gate.allows("workshop", ID, "Anyone", true));
            assertFalse(gate.allows("workshop", null, "Nether", false));
            assertEquals("Workshop", gate.forBackend("workshop").message());
        }
    }

    @Test
    void missingOrInvalidTargetNeverFallsBackToLegacy() {
        for (String invalid :
                new String[] {
                    "null",
                    "[]",
                    "{}",
                    entry("unknown", "", ""),
                    entry("public", "", ""),
                    entry("draining", "", ""),
                    entry("builders", "", "{\"draslId\":\"x\",\"name\":\"Nether\"}"),
                    "{\"gate\":true,\"message\":\"\",\"allowed\":[]}",
                    "{\"gate\":\"public\",\"message\":false,\"allowed\":[]}",
                    "{\"gate\":\"public\",\"message\":\"\",\"allowed\":null}",
                    "{\"backend\":\"game\",\"gate\":\"public\",\"message\":\"\",\"allowed\":[]}"
                }) {
            Gate gate =
                    Gate.parse(
                            dual(
                                    "{\"game\":"
                                            + entry("public", "", "")
                                            + ",\"workshop\":"
                                            + invalid
                                            + "}"));
            assertTrue(gate.allows("game", ID, "Nether", false), invalid);
            assertFalse(gate.allows("workshop", ID, "Nether", true), invalid);
        }
        Gate missingWorkshop = Gate.parse(dual("{\"game\":" + entry("public", "", "") + "}"));
        assertTrue(missingWorkshop.allows("game", ID, "Nether", false));
        assertFalse(missingWorkshop.allows("workshop", ID, "Nether", true));
        String workshop = entry("builders", "", "{\"draslId\":\"" + ID + "\",\"name\":\"Nether\"}");
        Gate missingGame = Gate.parse(dual("{\"workshop\":" + workshop + "}"));
        assertFalse(missingGame.allows("game", ID, "Nether", true));
        assertTrue(missingGame.allows("workshop", ID, "Nether", false));
        for (String malformed : new String[] {"null", "[]", "true", "{}"}) {
            Gate gate = Gate.parse(dual(malformed));
            assertFalse(gate.allows("game", ID, "Nether", true), malformed);
            assertFalse(gate.allows("workshop", ID, "Nether", true), malformed);
        }
    }

    @Test
    void gameRejectsWorkshopStatesWithoutClosingHealthyWorkshop() {
        String members = "{\"draslId\":\"" + ID + "\",\"name\":\"Nether\"}";
        for (String state : new String[] {"builders", "testers"}) {
            Gate gate =
                    Gate.parse(
                            dual(
                                    "{\"game\":"
                                            + entry(state, "", members)
                                            + ",\"workshop\":"
                                            + entry("builders", "", members)
                                            + "}"));
            assertFalse(gate.allows("game", ID, "Nether", true), state);
            assertTrue(gate.allows("workshop", ID, "Nether", false), state);
        }
    }

    @Test
    void legacyGateRejectsStatesOfTheOtherBackend() {
        String members = "{\"draslId\":\"" + ID + "\",\"name\":\"Nether\"}";
        for (String backend : new String[] {"game", "workshop"}) {
            String[] invalid =
                    backend.equals("game")
                            ? new String[] {"builders", "testers"}
                            : new String[] {"public", "draining"};
            for (String state : invalid) {
                Gate gate = Gate.parse(gate(state, backend, members));
                assertFalse(gate.allows("game", ID, "Nether", true), backend + ":" + state);
                assertFalse(gate.allows("workshop", ID, "Nether", true), backend + ":" + state);
            }
        }
    }

    @Test
    void schemaTwoParsesLikeSchemaOne() {
        String schema1 =
                dual(
                        "{\"game\":"
                                + entry("public", "Game", "")
                                + ",\"workshop\":"
                                + entry("closed", "", "")
                                + "}");
        Gate one = Gate.parse(schema1);
        Gate two = Gate.parse(schema1.replace("\"schema\":1", "\"schema\":2"));
        assertTrue(two.allows("game", null, "Anyone", false));
        assertFalse(two.allows("workshop", ID, "Nether", true));
        assertEquals(one.state(), two.state());
        assertEquals(one.message(), two.message());
    }

    @Test
    void deployingFlagMarksGameDeployInProgress() {
        String doc =
                "{\"schema\":2,\"revision\":3,\"gate\":\"closed\",\"backend\":\"game\","
                    + "\"message\":\"\",\"allowed\":[],\"deploying\":true,\"backends\":{\"game\":"
                    + "{\"gate\":\"closed\",\"message\":\"\",\"allowed\":[],\"deploying\":true},"
                    + "\"workshop\":"
                        + entry("closed", "", "")
                        + "}}";
        Gate gate = Gate.parse(doc);
        assertTrue(gate.forBackend("game").deploying());
        assertFalse(gate.forBackend("workshop").deploying());
        assertFalse(gate.allows("game", ID, "Nether", true));
    }

    @Test
    void legacySchemaOneWithoutDeployingStaysFalse() {
        Gate legacy = Gate.parse(gate("closed", "game", ""));
        assertFalse(legacy.deploying());
        assertFalse(legacy.forBackend("game").deploying());
        Gate dualGate =
                Gate.parse(
                        dual(
                                "{\"game\":"
                                        + entry("closed", "", "")
                                        + ",\"workshop\":"
                                        + entry("closed", "", "")
                                        + "}"));
        assertFalse(dualGate.forBackend("game").deploying());
    }

    @Test
    void nonBooleanDeployingOrSchemaThreeFailsClosed() {
        for (String bad :
                new String[] {
                    "{\"schema\":2,\"gate\":\"public\",\"backend\":\"game\",\"message\":\"\","
                            + "\"allowed\":[],\"deploying\":\"yes\"}",
                    "{\"schema\":3,\"gate\":\"public\",\"backend\":\"game\",\"message\":\"\","
                            + "\"allowed\":[]}"
                }) {
            Gate loaded = Gate.parse(bad);
            assertEquals("closed", loaded.state(), bad);
            assertFalse(loaded.deploying(), bad);
        }
    }
}

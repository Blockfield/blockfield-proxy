package io.blockfield.proxy;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

/**
 * Admission semantics of the CP-only gate. The CP-C2 cutover removed the {@code gate.json} file
 * gate and its format: tests build gates directly, the way {@link ControlSubscriber} delivers them.
 */
class GateTest {
    static final String ID = "e10438e4797b49718cfecec6a8fa171b";

    static Gate dual(String gameState, String workshopState, String memberId, String memberName) {
        return new Gate(
                gameState,
                "game",
                "",
                Set.of(),
                Map.of(
                        "game",
                        new Gate(gameState, "game", "", Set.of()),
                        "workshop",
                        new Gate(
                                workshopState,
                                "workshop",
                                "Workshop gate",
                                memberName == null
                                        ? Set.of()
                                        : Set.of(new Gate.Member(memberId, memberName)))));
    }

    @Test
    void fromCpMapsModesAndFailsClosed() {
        assertTrue(Gate.fromCp("open", "").open());
        Gate draining = Gate.fromCp("draining", "");
        assertEquals("draining", draining.state());
        assertTrue(draining.allows("game", null, "Anyone", true));
        assertFalse(draining.allows("game", null, "Anyone", false));
        Gate maintenance = Gate.fromCp("maintenance", "Техработы");
        assertEquals("closed", maintenance.state());
        assertEquals("Техработы", maintenance.message());
        assertFalse(maintenance.allows("game", null, "Anyone", true));
        assertEquals("closed", Gate.fromCp("staging", "").state());
        assertEquals(Gate.DEFAULT_MESSAGE, Gate.fromCp("maintenance", "").message());
    }

    @Test
    void withoutCpSnapshotEntryStaysClosed() {
        assertEquals("closed", Gate.closed().state());
        assertEquals(Gate.DEFAULT_MESSAGE, Gate.closed().message());
        assertFalse(Gate.closed().allows("game", ID, "Nether", true));
        assertFalse(Gate.closed().allows("workshop", ID, "Nether", false));
    }

    @Test
    void openGateAdmitsGameOnly() {
        assertTrue(Gate.OPEN.allows("game", null, "Anyone", false));
        assertFalse(Gate.OPEN.allows("workshop", ID, "Nether", false));
        assertTrue(Gate.fromCp("open", "").allows("game", null, "Anyone", false));
        Gate cp = Gate.fromCp("open", "");
        assertEquals("closed", cp.forBackend("workshop").state());
        assertFalse(cp.allows("workshop", ID, "Nether", false));
    }

    @Test
    void gameAndRestrictedWorkshopOpenIndependently() {
        for (String state : new String[] {"builders", "testers"}) {
            Gate gate = dual("public", state, ID, "Nether");
            assertTrue(gate.allows("game", null, "Anyone", false));
            assertTrue(gate.allows("workshop", ID, "Nether", false));
            assertFalse(gate.allows("workshop", ID, "Anyone", true));
            assertFalse(gate.allows("workshop", null, "Nether", false));
            assertEquals("Workshop gate", gate.forBackend("workshop").message());
        }
    }

    @Test
    void unknownBackendNeverAdmits() {
        Gate gate = dual("public", "builders", ID, "Nether");
        Gate selected = gate.forBackend("lobby");
        assertEquals("closed", selected.state());
        assertFalse(gate.allows("lobby", ID, "Nether", true));
    }

    @Test
    void sleepingGateNeverAdmitsButKeepsMembers() {
        Gate gate = dual("public", "sleeping", ID, "Builder");
        Gate workshop = gate.forBackend("workshop");
        assertTrue(workshop.sleeping());
        assertTrue(workshop.waking());
        assertFalse(gate.allows("workshop", ID, "Builder", false));
        assertTrue(workshop.member(ID, "Builder"));
        assertFalse(workshop.member(ID, "Impostor"));
        assertTrue(gate.allows("game", null, "Anyone", false));
    }

    @Test
    void memberIdsMatchCaseAndDashInsensitively() {
        Gate gate = dual("public", "builders", ID, "Nether");
        assertTrue(
                gate.allows("workshop", "E10438E4-797B-4971-8CFE-CEC6A8FA171B", "Nether", false));
        assertFalse(gate.allows("workshop", "00000000000000000000000000000000", "Nether", false));
        assertFalse(gate.allows("workshop", null, "Nether", false));
        assertFalse(gate.allows("workshop", ID, "Impostor", false));
    }
}

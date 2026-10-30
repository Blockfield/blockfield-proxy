package io.blockfield.proxy;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicLong;

class GatePollerTest {
    static String cp(String mode, String message) {
        return "{\"schema\":1,\"revision\":3,\"mode\":\""
                + mode
                + "\",\"message\":\""
                + message
                + "\",\"since\":7,\"observedAt\":9}";
    }

    static class Script implements GatePoller.Fetcher {
        final Queue<Object> answers = new ArrayDeque<>();

        public String fetch() throws Exception {
            Object next = answers.remove();
            if (next instanceof RuntimeException e) throw e;
            return (String) next;
        }
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
    void corruptAnswersNeverMoveTheProjection() {
        assertNull(GatePoller.parse("{"));
        assertNull(GatePoller.parse("[]"));
        assertNull(GatePoller.parse(cp("staging", "")));
        assertNull(GatePoller.parse("{\"schema\":2,\"mode\":\"open\",\"message\":\"\"}"));
        assertNull(GatePoller.parse("{\"schema\":1,\"message\":\"\"}"));
        assertNotNull(GatePoller.parse(cp("open", "")));
    }

    @Test
    void pollerTracksCpAndClosesPastTheLease() {
        AtomicLong clock = new AtomicLong(1_000);
        Script script = new Script();
        script.answers.add(cp("open", ""));
        GatePoller poller = new GatePoller(script, clock::get);
        assertEquals("closed", poller.current().state()); // fail closed до первого ответа
        assertTrue(poller.pollOnce().open());
        script.answers.add(cp("maintenance", "Техработы"));
        clock.addAndGet(GatePoller.POLL_MS);
        assertEquals("closed", poller.pollOnce().state());
        // CP лёг: проекция держится до конца лизы, затем вход закрыт.
        script.answers.add(new RuntimeException("down"));
        clock.addAndGet(9_000);
        Gate held = poller.pollOnce();
        assertEquals("closed", held.state());
        assertEquals("Техработы", held.message()); // лиза не вышла: держим последнее
        clock.addAndGet(GatePoller.LEASE_MS + 1);
        script.answers.add(new RuntimeException("down"));
        Gate closed = poller.pollOnce();
        assertEquals("closed", closed.state());
        assertEquals(Gate.DEFAULT_MESSAGE, closed.message());
        assertFalse(closed.allows("game", null, "Anyone", true));
    }

    @Test
    void singleFailureKeepsTheLastGoodProjection() {
        AtomicLong clock = new AtomicLong(0);
        Script script = new Script();
        script.answers.add(cp("open", ""));
        GatePoller poller = new GatePoller(script, clock::get);
        assertTrue(poller.pollOnce().open());
        script.answers.add("{broken");
        clock.addAndGet(GatePoller.POLL_MS);
        assertTrue(poller.pollOnce().open());
    }
}

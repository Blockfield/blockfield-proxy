package io.blockfield.proxy;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

class OwnerAlertTest {
    private static final long THRESHOLD = 120_000;

    private final List<String> sent = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger attempts = new AtomicInteger();
    private int failuresLeft;

    private OwnerAlert alert() {
        return alert(THRESHOLD);
    }

    private OwnerAlert alert(long thresholdMs) {
        return new OwnerAlert(
                "https://owner.invalid/hook",
                thresholdMs,
                LoggerFactory.getLogger("test"),
                text -> {
                    attempts.incrementAndGet();
                    if (failuresLeft > 0) {
                        failuresLeft--;
                        throw new java.io.IOException("boom");
                    }
                    sent.add(text);
                },
                Runnable::run);
    }

    @Test
    void closedGateAlertsOnceAfterThreshold() {
        OwnerAlert alert = alert();
        alert.tick(true, false, true, 0);
        alert.tick(true, false, true, 60_000);
        alert.tick(true, false, true, THRESHOLD - 1);
        assertTrue(sent.isEmpty());
        alert.tick(true, false, true, THRESHOLD);
        assertEquals(1, sent.size());
        assertTrue(sent.get(0).contains("game entry closed"));
        alert.tick(true, false, true, THRESHOLD + 60_000);
        alert.tick(true, false, true, THRESHOLD + 600_000);
        assertEquals(1, sent.size());
    }

    @Test
    void debounceRestartsWhenConditionFlickers() {
        OwnerAlert alert = alert();
        alert.tick(true, false, true, 0);
        alert.tick(true, false, true, 60_000);
        alert.tick(true, false, false, 61_000);
        alert.tick(true, false, true, 62_000);
        alert.tick(true, false, true, 62_000 + THRESHOLD - 1);
        assertTrue(sent.isEmpty());
        alert.tick(true, false, true, 62_000 + THRESHOLD);
        assertEquals(1, sent.size());
    }

    @Test
    void recoverySentOnceThenSilence() {
        OwnerAlert alert = alert();
        alert.tick(true, false, true, 0);
        alert.tick(true, false, true, THRESHOLD);
        assertEquals(1, sent.size());
        alert.tick(true, false, false, THRESHOLD + 3_000);
        assertEquals(2, sent.size());
        assertTrue(sent.get(1).contains("Recovered") || sent.get(1).contains("recovered"));
        alert.tick(true, false, false, THRESHOLD + 300_000);
        alert.tick(true, false, true, THRESHOLD + 301_000);
        assertEquals(2, sent.size());
    }

    @Test
    void linkDownAlertsEvenWhenGateOpen() {
        OwnerAlert alert = alert();
        alert.tick(false, false, false, 0);
        alert.tick(false, false, false, THRESHOLD - 1);
        assertTrue(sent.isEmpty());
        alert.tick(false, false, false, THRESHOLD);
        assertEquals(1, sent.size());
        assertTrue(sent.get(0).contains("control plane unreachable"));
    }

    @Test
    void maintenanceSuppressesClosedGate() {
        OwnerAlert alert = alert();
        for (long now = 0; now <= 600_000; now += 3_000) alert.tick(true, true, true, now);
        assertTrue(sent.isEmpty());
    }

    @Test
    void conditionSwitchRecoversOldThenDebouncesNew() {
        OwnerAlert alert = alert();
        alert.tick(false, false, false, 0);
        alert.tick(false, false, false, THRESHOLD);
        assertEquals(1, sent.size());
        // Link back, but the gate is stuck closed outside maintenance: recovery now,
        // gate alert only after its own debounce.
        alert.tick(true, false, true, THRESHOLD + 3_000);
        assertEquals(2, sent.size());
        assertTrue(sent.get(1).contains("reachable again"));
        alert.tick(true, false, true, THRESHOLD + 3_000 + THRESHOLD - 1);
        assertEquals(2, sent.size());
        alert.tick(true, false, true, THRESHOLD + 3_000 + THRESHOLD);
        assertEquals(3, sent.size());
        assertTrue(sent.get(2).contains("game entry closed"));
    }

    @Test
    void inertWithoutUrl() {
        OwnerAlert alert =
                new OwnerAlert(
                        "",
                        THRESHOLD,
                        LoggerFactory.getLogger("test"),
                        text -> {
                            throw new AssertionError("inert alert must never send");
                        },
                        Runnable::run);
        for (long now = 0; now <= 600_000; now += 3_000) alert.tick(false, false, true, now);
    }

    @Test
    void failedSendRetriedThrottled() {
        OwnerAlert alert = alert(1_000);
        failuresLeft = 1;
        alert.tick(true, false, true, 0);
        alert.tick(true, false, true, 1_000);
        assertEquals(1, attempts.get());
        assertTrue(sent.isEmpty());
        // Inside the retry gap: no redelivery.
        alert.tick(true, false, true, 1_000 + 59_000);
        assertEquals(1, attempts.get());
        alert.tick(true, false, true, 1_000 + 60_000);
        assertEquals(2, attempts.get());
        assertEquals(1, sent.size());
    }

    @Test
    void liveClassificationThroughSubscriber() {
        AtomicLong clock = new AtomicLong(1_000_000);
        AtomicLong nanos = new AtomicLong(0);
        ControlSubscriber sub =
                ControlSubscriber.fromParts(
                        "https://cp.invalid",
                        "token",
                        "proxy-1",
                        LoggerFactory.getLogger("test"),
                        clock::get,
                        message -> {},
                        new ControlSubscriber.Recording(),
                        nanos::get);
        OwnerAlert alert = alert(10_000);
        assertTrue(
                sub.applySnapshot(
                        "{\"schema\":2,\"audience\":\"proxy-1\","
                            + "\"epoch\":\"11111111-1111-1111-1111-111111111111\","
                            + "\"generation\":\"22222222-2222-2222-2222-222222222222\","
                            + "\"cursor\":5,\"settings\":[{\"namespace\":\"gate\",\"target\":\"*\","
                            + "\"revision\":3,\"value\":{\"mode\":\"open\",\"message\":\"\"},"
                            + "\"value_hash\":\"9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08\"}],"
                            + "\"hash\":\"9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08\"}"));
        for (long now = 0; now <= 60_000; now += 3_000) alert.tick(sub, sub.current(), now);
        assertTrue(sent.isEmpty());
    }
}

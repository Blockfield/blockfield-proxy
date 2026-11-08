package io.blockfield.proxy;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

class ControlSubscriberTest {
    private static final String CONSUMER = "proxy-1";
    private static final String EPOCH = "11111111-1111-1111-1111-111111111111";
    private static final String GENERATION = "22222222-2222-2222-2222-222222222222";
    private static final String HASH =
            "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";

    private final AtomicLong clock = new AtomicLong(1_000_000);
    private final AtomicLong nanos = new AtomicLong(0);
    private final ControlSubscriber.Recording recording = new ControlSubscriber.Recording();

    private ControlSubscriber subscriber() {
        return ControlSubscriber.fromParts(
                "https://cp.invalid",
                "token",
                CONSUMER,
                LoggerFactory.getLogger("test"),
                clock::get,
                message -> {},
                recording,
                nanos::get);
    }

    private static String snapshot(String mode, long cursor) {
        return "{\"schema\":2,\"audience\":\""
                + CONSUMER
                + "\",\"epoch\":\""
                + EPOCH
                + "\",\"generation\":\""
                + GENERATION
                + "\",\"cursor\":"
                + cursor
                + ",\"settings\":[{\"namespace\":\"gate\",\"target\":\"*\",\"revision\":3,"
                + "\"value\":{\"mode\":\""
                + mode
                + "\",\"message\":\"\"},\"value_hash\":\""
                + HASH
                + "\"}],\"hash\":\""
                + HASH
                + "\"}";
    }

    private String envelope(long sequence, boolean executable, String reason, String payload) {
        return "{\"schema\":2,\"epoch\":\""
                + EPOCH
                + "\",\"sequence\":"
                + sequence
                + ",\"operationId\":\""
                + UUID.randomUUID()
                + "\",\"audience\":\""
                + CONSUMER
                + "\",\"generation\":\""
                + GENERATION
                + "\",\"revision\":4,\"payloadHash\":\""
                + HASH
                + "\",\"authorizationEpoch\":\""
                + EPOCH
                + "\",\"expiresAt\":\""
                + Instant.ofEpochMilli(clock.get() + 60_000)
                + "\",\"executable\":"
                + executable
                + ",\"reason\":"
                + (reason == null ? "null" : "\"" + reason + "\"")
                + (payload == null ? ",\"payload\":null" : ",\"payload\":" + payload)
                + "}";
    }

    private static String gatePayload(String mode) {
        return "{\"kind\":\"settings\",\"namespace\":\"gate\",\"target\":\"*\","
                + "\"value\":{\"mode\":\""
                + mode
                + "\",\"message\":\"\"},\"timing\":\"live\"}";
    }

    private static String workshopPayload(String state, String memberId, String memberName) {
        String allowed =
                memberId == null
                        ? "[]"
                        : "[{\"draslId\":\"" + memberId + "\",\"name\":\"" + memberName + "\"}]";
        return "{\"kind\":\"settings\",\"namespace\":\"gate\",\"target\":\"workshop\","
                + "\"value\":{\"state\":\""
                + state
                + "\",\"message\":\"night build\",\"allowed\":"
                + allowed
                + "},\"timing\":\"live\"}";
    }

    private static String snapshotDual(
            String gameMode,
            String workshopState,
            String memberId,
            String memberName,
            long cursor) {
        String allowed =
                memberId == null
                        ? "[]"
                        : "[{\"draslId\":\"" + memberId + "\",\"name\":\"" + memberName + "\"}]";
        return "{\"schema\":2,\"audience\":\""
                + CONSUMER
                + "\",\"epoch\":\""
                + EPOCH
                + "\",\"generation\":\""
                + GENERATION
                + "\",\"cursor\":"
                + cursor
                + ",\"settings\":[{\"namespace\":\"gate\",\"target\":\"*\",\"revision\":3,"
                + "\"value\":{\"mode\":\""
                + gameMode
                + "\",\"message\":\"\"},\"value_hash\":\""
                + HASH
                + "\"},{\"namespace\":\"gate\",\"target\":\"workshop\",\"revision\":4,"
                + "\"value\":{\"state\":\""
                + workshopState
                + "\",\"message\":\"night build\",\"allowed\":"
                + allowed
                + "},\"value_hash\":\""
                + HASH
                + "\"}],\"hash\":\""
                + HASH
                + "\"}";
    }

    @Test
    void firstBootWithoutSnapshotStaysClosed() {
        assertEquals("closed", subscriber().current().state());
    }

    @Test
    void halfConfiguredConsumerNeverFallsBackToFile() {
        ControlSubscriber missingUrl =
                ControlSubscriber.fromParts(
                        "",
                        "",
                        CONSUMER,
                        LoggerFactory.getLogger("test"),
                        clock::get,
                        message -> {},
                        recording,
                        nanos::get);
        assertNotNull(missingUrl, "half-configured v2 must exist and pin the game leg closed");
        assertEquals("closed", missingUrl.current().state());
        assertNull(
                ControlSubscriber.fromParts(
                        "https://cp.invalid",
                        "token",
                        "",
                        LoggerFactory.getLogger("test"),
                        clock::get,
                        message -> {},
                        recording),
                "without a consumer id there is no subscriber: the caller pins entry closed");
    }

    @Test
    void snapshotAppliesScopedGate() {
        ControlSubscriber sub = subscriber();
        assertTrue(sub.applySnapshot(snapshot("open", 5)));
        assertTrue(sub.current().open());
        assertEquals(5, sub.cursor());
    }

    @Test
    void snapshotRejectsForeignAudienceAndBadSchema() {
        ControlSubscriber sub = subscriber();
        assertTrue(sub.applySnapshot(snapshot("open", 5)));
        assertFalse(sub.applySnapshot(snapshot("open", 5).replace(CONSUMER, "proxy-2")));
        assertFalse(sub.applySnapshot(snapshot("open", 5).replace("\"schema\":2", "\"schema\":1")));
        assertFalse(sub.applySnapshot(snapshot("open", 5).replace(EPOCH, "not-a-uuid")));
        assertTrue(sub.current().open(), "rejected snapshots never move the gate");
        assertEquals(5, sub.cursor());
    }

    @Test
    void gapAndReplayAskResyncOrHold() {
        ControlSubscriber sub = subscriber();
        assertTrue(sub.applySnapshot(snapshot("open", 5)));
        assertEquals(
                ControlSubscriber.Verdict.RESYNC,
                sub.offer(envelope(7, true, null, gatePayload("maintenance"))),
                "reordered/gapped frame must re-snapshot, never apply");
        assertTrue(sub.current().open());
        assertEquals(
                ControlSubscriber.Verdict.HELD,
                sub.offer(envelope(5, true, null, gatePayload("maintenance"))),
                "replayed frame is a duplicate, not a state change");
        assertTrue(sub.current().open());
        assertEquals(5, sub.cursor());
    }

    @Test
    void executableGateFrameAppliesAndAcksInOrder() throws Exception {
        ControlSubscriber sub = subscriber();
        assertTrue(sub.applySnapshot(snapshot("open", 5)));
        assertEquals(
                ControlSubscriber.Verdict.APPLIED,
                sub.offer(envelope(6, true, null, gatePayload("maintenance"))));
        assertEquals("closed", sub.current().state());
        awaitCursor(sub, 6);
        awaitBodies(recording.bodies, 3);
        assertEquals(6, sub.cursor());
        assertEquals(3, recording.bodies.size());
        String[] states =
                recording.bodies.stream()
                        .map(ControlSubscriberTest::stateOf)
                        .toArray(String[]::new);
        assertArrayEquals(new String[] {"received", "validated", "applied"}, states);
        for (String body : recording.bodies) {
            JsonObject ack = JsonParser.parseString(body).getAsJsonObject();
            assertEquals(6, ack.get("sequence").getAsInt());
            assertEquals(HASH, ack.get("payloadHash").getAsString());
            assertNotNull(ack.get("actualAt").getAsString());
        }
    }

    @Test
    void expiredFrameAdvancesCursorWithoutApplying() throws Exception {
        ControlSubscriber sub = subscriber();
        assertTrue(sub.applySnapshot(snapshot("open", 5)));
        String expired =
                envelope(6, false, "expired", null)
                        .replace(
                                Instant.ofEpochMilli(clock.get() + 60_000).toString(),
                                Instant.ofEpochMilli(clock.get() - 1_000).toString());
        assertEquals(ControlSubscriber.Verdict.HELD, sub.offer(expired));
        assertTrue(sub.current().open(), "expired frame must not close or open entry");
        awaitCursor(sub, 6);
        awaitBodies(recording.bodies, 1);
        assertEquals(6, sub.cursor());
        assertEquals(1, recording.bodies.size());
        assertEquals("received", stateOf(recording.bodies.get(0)));
    }

    @Test
    void staleGenerationResyncs() {
        ControlSubscriber sub = subscriber();
        assertTrue(sub.applySnapshot(snapshot("open", 5)));
        String otherGen =
                envelope(6, true, null, gatePayload("maintenance")).replace(GENERATION, EPOCH);
        assertEquals(ControlSubscriber.Verdict.RESYNC, sub.offer(otherGen));
        assertTrue(sub.current().open());
        assertEquals(5, sub.cursor());
    }

    @Test
    void forgedAudienceResyncs() {
        ControlSubscriber sub = subscriber();
        assertTrue(sub.applySnapshot(snapshot("open", 5)));
        String forged =
                envelope(6, true, null, gatePayload("maintenance")).replace(CONSUMER, "proxy-9");
        assertEquals(ControlSubscriber.Verdict.RESYNC, sub.offer(forged));
        assertTrue(sub.current().open());
    }

    @Test
    void nonGatePayloadIsRejectedWithoutApplying() throws Exception {
        ControlSubscriber sub = subscriber();
        assertTrue(sub.applySnapshot(snapshot("open", 5)));
        String action =
                "{\"kind\":\"action\",\"namespace\":\"moderation\",\"target\":\"game\","
                        + "\"value\":{\"command\":\"kick\"}}";
        assertEquals(ControlSubscriber.Verdict.HELD, sub.offer(envelope(6, true, null, action)));
        assertTrue(sub.current().open());
        awaitCursor(sub, 6);
        awaitBodies(recording.bodies, 1);
        assertEquals(6, sub.cursor());
        assertEquals("rejected", stateOf(recording.bodies.get(0)));
    }

    @Test
    void partitionHoldsLastGateThenClosesPastTheLease() {
        ControlSubscriber sub = subscriber();
        assertTrue(sub.applySnapshot(snapshot("open", 5)));
        nanos.addAndGet(ControlSubscriber.LEASE_NS - 1);
        assertTrue(sub.current().open(), "partition inside the lease keeps the last snapshot");
        nanos.addAndGet(2);
        Gate closed = sub.current();
        assertEquals("closed", closed.state(), "partition past the lease closes entry");
        assertFalse(closed.allows("game", null, "Anyone", true));
    }

    @Test
    void v2GameLegNeverAdmitsWorkshop() {
        // A snapshot without the workshop leg leaves it closed; workshop members are never
        // evicted by a game-leg change — they never enter.
        ControlSubscriber sub = subscriber();
        assertTrue(sub.applySnapshot(snapshot("open", 5)));
        Gate current = sub.current();
        assertTrue(current.allows("game", null, "Anyone", false));
        assertEquals("closed", current.forBackend("workshop").state());
        assertFalse(current.allows("workshop", EPOCH.replace("-", ""), "NetherG", false));

        nanos.addAndGet(ControlSubscriber.LEASE_NS + 1);
        Gate expired = sub.current();
        assertFalse(expired.allows("game", null, "Anyone", true));
        assertFalse(expired.allows("workshop", EPOCH.replace("-", ""), "NetherG", false));
    }

    private static String stateOf(String ackBody) {
        return JsonParser.parseString(ackBody).getAsJsonObject().get("state").getAsString();
    }

    private static void awaitCursor(ControlSubscriber sub, long want) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (sub.cursor() != want && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
        }
    }

    private static void awaitBodies(List<String> bodies, int want) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (bodies.size() < want && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
        }
    }

    @Test
    void malformedPayloadHashResyncsWithoutAdvancing() {
        ControlSubscriber sub = subscriber();
        assertTrue(sub.applySnapshot(snapshot("open", 5)));
        String bad =
                envelope(6, true, null, gatePayload("maintenance")).replace(HASH, "not-a-hash");
        assertEquals(
                ControlSubscriber.Verdict.RESYNC,
                sub.offer(bad),
                "an unverifiable change must re-snapshot, or replay never brings it back");
        assertEquals(5, sub.cursor());
        assertTrue(sub.current().open());
        assertEquals(0, recording.bodies.size(), "no ACK without a verifiable payload hash");
    }

    @Test
    void gateFrameWithoutStringModeFailsClosedWithoutThrowing() throws Exception {
        for (String payload :
                new String[] {
                    gatePayload("maintenance").replace("\"mode\":\"maintenance\",", ""),
                    gatePayload("maintenance").replace("\"mode\":\"maintenance\"", "\"mode\":42")
                }) {
            ControlSubscriber sub = subscriber();
            assertTrue(sub.applySnapshot(snapshot("open", 5)));
            assertEquals(
                    ControlSubscriber.Verdict.APPLIED,
                    sub.offer(envelope(6, true, null, payload)),
                    "a mode-less gate frame must fail closed, never throw out of offer()");
            assertEquals("closed", sub.current().state());
            awaitCursor(sub, 6);
            assertEquals(
                    ControlSubscriber.Verdict.APPLIED,
                    sub.offer(envelope(7, true, null, gatePayload("open"))),
                    "the stream keeps moving after a corrupt frame");
            assertTrue(sub.current().open());
        }
    }

    @Test
    void heldFrameRefreshesLease() {
        ControlSubscriber sub = subscriber();
        assertTrue(sub.applySnapshot(snapshot("open", 5)));
        nanos.addAndGet(ControlSubscriber.LEASE_NS - 1);
        assertEquals(
                ControlSubscriber.Verdict.HELD,
                sub.offer(envelope(5, true, null, gatePayload("maintenance"))),
                "a duplicate on a live stream proves the CP is reachable");
        nanos.addAndGet(2);
        assertTrue(
                sub.current().open(),
                "a held frame refreshes the lease like a keep-alive, so a steady gate stays open");
    }

    @Test
    void workshopTargetNeverDrivesGameEntry() {
        ControlSubscriber sub = subscriber();
        // A game-shaped value at the workshop target is an invalid workshop leg: it fails
        // that leg closed and leaves the game leg exactly as it was.
        String mixed =
                snapshotDual("open", "builders", GateTest.ID, "Nether", 5)
                        .replace("\"state\":\"builders\"", "\"mode\":\"open\"");
        assertTrue(sub.applySnapshot(mixed));
        assertTrue(
                sub.current().allows("game", null, "Anyone", false),
                "a workshop entry must never drive game entry");
        assertEquals("closed", sub.current().forBackend("workshop").state());
        assertEquals(5, sub.cursor());
    }

    @Test
    void ambiguousSnapshotPinsClosed() {
        ControlSubscriber sub = subscriber();
        String two =
                "{\"schema\":2,\"audience\":\""
                        + CONSUMER
                        + "\",\"epoch\":\""
                        + EPOCH
                        + "\",\"generation\":\""
                        + GENERATION
                        + "\",\"cursor\":5,\"settings\":["
                        + "{\"namespace\":\"gate\",\"target\":\"*\",\"revision\":3,"
                        + "\"value\":{\"mode\":\"open\",\"message\":\"\"},\"value_hash\":\""
                        + HASH
                        + "\"},"
                        + "{\"namespace\":\"gate\",\"target\":\"game\",\"revision\":4,"
                        + "\"value\":{\"mode\":\"open\",\"message\":\"\"},\"value_hash\":\""
                        + HASH
                        + "\"}],\"hash\":\""
                        + HASH
                        + "\"}";
        assertTrue(sub.applySnapshot(two));
        assertEquals(
                "closed",
                sub.current().state(),
                "two game-leg entries pin closed instead of depending on array order");
    }

    @Test
    void workshopLegInSnapshotAdmitsWorkshop() {
        ControlSubscriber sub = subscriber();
        assertTrue(sub.applySnapshot(snapshotDual("open", "builders", GateTest.ID, "Nether", 5)));
        Gate current = sub.current();
        assertTrue(current.allows("game", null, "Anyone", false));
        assertTrue(current.allows("workshop", GateTest.ID, "Nether", false));
        assertFalse(current.allows("workshop", GateTest.ID, "Impostor", false));
        assertEquals(5, sub.cursor());
    }

    @Test
    void workshopFrameAppliesWithoutTouchingGameLeg() throws Exception {
        ControlSubscriber sub = subscriber();
        assertTrue(sub.applySnapshot(snapshot("open", 5)));
        assertEquals(
                ControlSubscriber.Verdict.APPLIED,
                sub.offer(
                        envelope(
                                6,
                                true,
                                null,
                                workshopPayload("builders", GateTest.ID, "Nether"))));
        assertTrue(sub.current().allows("game", null, "Anyone", false));
        assertTrue(sub.current().allows("workshop", GateTest.ID, "Nether", false));
        awaitCursor(sub, 6);
        assertEquals(
                ControlSubscriber.Verdict.APPLIED,
                sub.offer(envelope(7, true, null, gatePayload("maintenance"))));
        assertFalse(sub.current().allows("game", null, "Anyone", true));
        assertTrue(
                sub.current().allows("workshop", GateTest.ID, "Nether", false),
                "a game-leg frame must not close the workshop leg");
    }

    @Test
    void staleLeaseClosesBothLegs() {
        ControlSubscriber sub = subscriber();
        assertTrue(sub.applySnapshot(snapshotDual("open", "builders", GateTest.ID, "Nether", 5)));
        nanos.addAndGet(ControlSubscriber.LEASE_NS + 1);
        Gate expired = sub.current();
        assertFalse(expired.allows("game", null, "Anyone", true));
        assertFalse(expired.allows("workshop", GateTest.ID, "Nether", false));
    }

    @Test
    void workshopFrameLeavesGameLegUntouched() throws Exception {
        ControlSubscriber sub = subscriber();
        assertTrue(sub.applySnapshot(snapshot("open", 5)));
        String other =
                gatePayload("maintenance").replace("\"target\":\"*\"", "\"target\":\"workshop\"");
        assertEquals(ControlSubscriber.Verdict.APPLIED, sub.offer(envelope(6, true, null, other)));
        assertTrue(sub.current().open(), "a workshop entry must never drive game entry");
        assertEquals(
                "closed",
                sub.current().forBackend("workshop").state(),
                "a game-shaped workshop value is an invalid workshop leg, fails closed");
        awaitCursor(sub, 6);
        awaitBodies(recording.bodies, 3);
        assertEquals(6, sub.cursor());
        assertEquals("applied", stateOf(recording.bodies.get(2)));
    }

    @Test
    void defaultSenderPostsAcksToCp() throws Exception {
        List<String> bodies = Collections.synchronizedList(new ArrayList<>());
        List<String> auth = Collections.synchronizedList(new ArrayList<>());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v2/agents/" + CONSUMER + "/acks",
                exchange -> {
                    bodies.add(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8));
                    auth.add(exchange.getRequestHeaders().getFirst("Authorization"));
                    byte[] ok = "{}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, ok.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(ok);
                    }
                });
        server.start();
        try {
            ControlSubscriber sub =
                    ControlSubscriber.fromParts(
                            "http://127.0.0.1:" + server.getAddress().getPort(),
                            "secret-token",
                            CONSUMER,
                            LoggerFactory.getLogger("test"),
                            clock::get,
                            message -> {},
                            null,
                            nanos::get);
            assertNotNull(sub, "fromParts without a sender must default to POSTing ACKs");
            assertTrue(sub.applySnapshot(snapshot("open", 5)));
            assertEquals(
                    ControlSubscriber.Verdict.APPLIED,
                    sub.offer(envelope(6, true, null, gatePayload("maintenance"))));
            awaitBodies(bodies, 3);
            assertEquals(3, bodies.size(), "production ACKs reach POST /acks");
            for (String body : bodies) {
                JsonObject ack = JsonParser.parseString(body).getAsJsonObject();
                assertEquals(6, ack.get("sequence").getAsInt());
                assertEquals(HASH, ack.get("payloadHash").getAsString());
            }
            assertTrue(
                    auth.stream().allMatch("Bearer secret-token"::equals),
                    "ACKs carry the provisioned bearer token");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void streamAppliesFramesWhileConnectionStaysOpen() throws Exception {
        List<String> acks = Collections.synchronizedList(new ArrayList<>());
        String snap = snapshot("open", 5);
        String frame = envelope(6, true, null, gatePayload("maintenance"));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // The CP serves the open stream and the ACK POSTs concurrently.
        ExecutorService pool = Executors.newCachedThreadPool();
        server.setExecutor(pool);
        server.createContext(
                "/v2/agents/" + CONSUMER + "/snapshot",
                exchange -> {
                    byte[] body = snap.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                });
        server.createContext(
                "/v2/agents/" + CONSUMER + "/events",
                exchange -> {
                    exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, 0);
                    try {
                        OutputStream out = exchange.getResponseBody();
                        out.write(": keep-alive\n\n".getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        Thread.sleep(200);
                        out.write(
                                ("event: control\ndata: " + frame + "\n\n")
                                        .getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        // The CP keeps a healthy stream open; frames must apply before it
                        // closes.
                        Thread.sleep(15_000);
                        out.close();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (IOException e) {
                        // The test stopped the server mid-stream; nothing to assert here.
                    }
                });
        server.createContext(
                "/v2/agents/" + CONSUMER + "/acks",
                exchange -> {
                    acks.add(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8));
                    byte[] ok = "{}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, ok.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(ok);
                    }
                });
        server.start();
        try {
            ControlSubscriber sub =
                    ControlSubscriber.fromParts(
                            "http://127.0.0.1:" + server.getAddress().getPort(),
                            "token",
                            CONSUMER,
                            LoggerFactory.getLogger("test"),
                            clock::get,
                            message -> {},
                            null,
                            nanos::get);
            assertNotNull(sub);
            sub.start();
            long deadline = System.currentTimeMillis() + 10_000;
            while (sub.cursor() != 6 && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertEquals(6, sub.cursor(), "a frame applies while the SSE stream stays open");
            assertEquals("closed", sub.current().state());
            long ackDeadline = System.currentTimeMillis() + 5_000;
            while (acks.size() < 3 && System.currentTimeMillis() < ackDeadline) {
                Thread.sleep(50);
            }
            assertEquals(3, acks.size(), "streamed frames are ACKed over the same connection");
        } finally {
            server.stop(0);
            pool.shutdownNow();
        }
    }

    @Test
    void malformedConsumerIdPinsClosed() {
        ControlSubscriber space =
                ControlSubscriber.fromParts(
                        "https://cp.invalid",
                        "token",
                        "proxy 1",
                        LoggerFactory.getLogger("test"),
                        clock::get,
                        message -> {},
                        recording,
                        nanos::get);
        assertNotNull(space, "a set consumer id is a consumer even when malformed");
        assertEquals("closed", space.current().state());
        ControlSubscriber slash =
                ControlSubscriber.fromParts(
                        "https://cp.invalid",
                        "token",
                        "proxy/1",
                        LoggerFactory.getLogger("test"),
                        clock::get,
                        message -> {},
                        recording,
                        nanos::get);
        assertNotNull(slash);
        assertEquals("closed", slash.current().state());
        assertNull(
                ControlSubscriber.fromParts(
                        "https://cp.invalid",
                        "token",
                        "   ",
                        LoggerFactory.getLogger("test"),
                        clock::get,
                        message -> {},
                        recording,
                        nanos::get),
                "a blank id means v2 is not configured");
    }

    @Test
    void leaseUsesMonotonicClock() {
        ControlSubscriber sub = subscriber();
        assertTrue(sub.applySnapshot(snapshot("open", 5)));
        clock.addAndGet(ControlSubscriber.LEASE_MS - 1);
        nanos.addAndGet(ControlSubscriber.LEASE_NS - 1);
        assertTrue(sub.current().open());
        clock.addAndGet(-30_000);
        clock.addAndGet(2);
        nanos.addAndGet(2);
        assertEquals(
                "closed",
                sub.current().state(),
                "a backward wall step must not extend the lease past a partition");
    }

    @Test
    void unparseableControlDataResyncs() {
        ControlSubscriber sub = subscriber();
        assertTrue(sub.applySnapshot(snapshot("open", 5)));
        assertEquals(
                ControlSubscriber.Verdict.RESYNC,
                sub.offer("truncated {\"schema\":2"),
                "a change the proxy cannot parse must re-snapshot, not sit as current");
        assertEquals(5, sub.cursor());
        assertTrue(sub.current().open(), "the last good gate stands until the re-snapshot lands");
        assertEquals(0, recording.bodies.size());
    }

    @Test
    void multiLineDataJoinsWithNewline() throws Exception {
        String full = envelope(6, true, null, gatePayload("maintenance"));
        // Split between members, where a newline is insignificant whitespace.
        int cut = full.indexOf("\"generation\"");
        String first = full.substring(0, cut);
        String second = full.substring(cut);
        String snap = snapshot("open", 5);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v2/agents/" + CONSUMER + "/snapshot",
                exchange -> {
                    byte[] body = snap.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                });
        server.createContext(
                "/v2/agents/" + CONSUMER + "/events",
                exchange -> {
                    exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, 0);
                    try {
                        OutputStream out = exchange.getResponseBody();
                        out.write(
                                ("event: control\ndata: " + first + "\ndata: " + second + "\n\n")
                                        .getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        // The frame must already have applied; the stream only stays open
                        // to prove nothing waits for the server to close it.
                        Thread.sleep(12_000);
                        out.close();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (IOException e) {
                        // The test stopped the server mid-stream; nothing to assert here.
                    }
                });
        server.start();
        try {
            ControlSubscriber sub =
                    ControlSubscriber.fromParts(
                            "http://127.0.0.1:" + server.getAddress().getPort(),
                            "token",
                            CONSUMER,
                            LoggerFactory.getLogger("test"),
                            clock::get,
                            message -> {},
                            recording,
                            nanos::get);
            assertNotNull(sub);
            sub.start();
            long deadline = System.currentTimeMillis() + 10_000;
            while (sub.cursor() != 6 && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertEquals(6, sub.cursor(), "a multi-line control event applies as one envelope");
            assertEquals("closed", sub.current().state());
            sub.stop();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void idleStreamReconnectsAfterThreeMissedBeats() throws Exception {
        AtomicInteger events = new AtomicInteger();
        List<String> acks = Collections.synchronizedList(new ArrayList<>());
        String snap = snapshot("open", 5);
        String frame = envelope(6, true, null, gatePayload("maintenance"));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService pool = Executors.newCachedThreadPool();
        server.setExecutor(pool);
        server.createContext(
                "/v2/agents/" + CONSUMER + "/snapshot",
                exchange -> {
                    byte[] body = snap.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                });
        server.createContext(
                "/v2/agents/" + CONSUMER + "/events",
                exchange -> {
                    int n = events.incrementAndGet();
                    exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, 0);
                    try {
                        OutputStream out = exchange.getResponseBody();
                        if (n == 1) {
                            // Silent half-open connection: no frame, no keep-alive.
                            Thread.sleep(25_000);
                            out.close();
                            return;
                        }
                        out.write(": keep-alive\n\n".getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        out.write(
                                ("event: control\ndata: " + frame + "\n\n")
                                        .getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        Thread.sleep(12_000);
                        out.close();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (IOException e) {
                        // The test stopped the server mid-stream; nothing to assert here.
                    }
                });
        server.createContext(
                "/v2/agents/" + CONSUMER + "/acks",
                exchange -> {
                    acks.add(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8));
                    byte[] ok = "{}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, ok.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(ok);
                    }
                });
        server.start();
        try {
            ControlSubscriber sub =
                    ControlSubscriber.fromParts(
                            "http://127.0.0.1:" + server.getAddress().getPort(),
                            "token",
                            CONSUMER,
                            LoggerFactory.getLogger("test"),
                            clock::get,
                            message -> {},
                            null,
                            nanos::get);
            assertNotNull(sub);
            sub.start();
            Thread.sleep(1_500);
            nanos.addAndGet(2 * ControlSubscriber.IDLE_NS);
            long deadline = System.currentTimeMillis() + 15_000;
            while (sub.cursor() != 6 && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertEquals(6, sub.cursor(), "an idle stream is aborted and reconnected");
            assertTrue(events.get() >= 2, "the reconnect opens a fresh stream");
            assertEquals(3, acks.size(), "the replayed frame is ACKed after reconnect");
            sub.stop();
        } finally {
            server.stop(0);
            pool.shutdownNow();
        }
    }

    @Test
    void outOfRangeRevisionLabeledSafely() throws Exception {
        ControlSubscriber sub = subscriber();
        assertTrue(sub.applySnapshot(snapshot("open", 5)));
        String huge =
                envelope(6, true, null, gatePayload("maintenance"))
                        .replace("\"revision\":4", "\"revision\":5000000000");
        assertEquals(
                ControlSubscriber.Verdict.APPLIED,
                sub.offer(huge),
                "an out-of-int revision labels the ACK instead of corrupting it");
        awaitCursor(sub, 6);
        assertEquals(6, sub.cursor());
        ControlSubscriber again = subscriber();
        assertTrue(again.applySnapshot(snapshot("open", 5)));
        String frac =
                envelope(6, true, null, gatePayload("maintenance"))
                        .replace("\"revision\":4", "\"revision\":1.5");
        assertEquals(ControlSubscriber.Verdict.APPLIED, again.offer(frac));
        awaitCursor(again, 6);
        awaitBodies(recording.bodies, 6);
        assertEquals(6, again.cursor());
        assertTrue(
                recording.bodies.stream().anyMatch(b -> b.contains("\"revision\":5000000000")),
                "a long revision is reported exactly, not truncated to int");
        assertTrue(
                recording.bodies.getLast().contains("\"revision\":null"),
                "a fractional revision degrades to null in the ACK");
    }

    @Test
    void gateAppliesBeforeBlockingAcks() throws Exception {
        CountDownLatch ackInFlight = new CountDownLatch(1);
        CountDownLatch releaseAck = new CountDownLatch(1);
        List<String> sent = Collections.synchronizedList(new ArrayList<>());
        ControlSubscriber sub =
                ControlSubscriber.fromParts(
                        "https://cp.invalid",
                        "token",
                        CONSUMER,
                        LoggerFactory.getLogger("test"),
                        clock::get,
                        message -> {},
                        body -> {
                            sent.add(body);
                            ackInFlight.countDown();
                            releaseAck.await(15, TimeUnit.SECONDS);
                        },
                        nanos::get);
        assertNotNull(sub);
        assertTrue(sub.applySnapshot(snapshot("open", 5)));
        AtomicReference<ControlSubscriber.Verdict> verdict = new AtomicReference<>();
        Thread offer =
                new Thread(
                        () ->
                                verdict.set(
                                        sub.offer(envelope(6, true, null, gatePayload("closed")))));
        offer.setDaemon(true);
        offer.start();
        assertTrue(ackInFlight.await(15, TimeUnit.SECONDS), "the first ACK blocks in flight");
        // The gate-closing frame is already enforced while its ACKs still block.
        assertEquals("closed", sub.current().state());
        assertEquals(5, sub.cursor(), "the cursor still implies the ACKs were sent");
        releaseAck.countDown();
        offer.join(15_000);
        assertFalse(offer.isAlive(), "offer returns once the ACKs go through");
        assertEquals(ControlSubscriber.Verdict.APPLIED, verdict.get());
        awaitCursor(sub, 6);
        awaitBodies(sent, 3);
        assertEquals(6, sub.cursor());
        assertEquals(3, sent.size(), "received, validated and applied are all sent");
    }

    @Test
    void nextFrameAppliesWhilePriorAcksBlocked() throws Exception {
        CountDownLatch firstAckInFlight = new CountDownLatch(1);
        CountDownLatch releaseAcks = new CountDownLatch(1);
        List<String> sent = Collections.synchronizedList(new ArrayList<>());
        ControlSubscriber sub =
                ControlSubscriber.fromParts(
                        "https://cp.invalid",
                        "token",
                        CONSUMER,
                        LoggerFactory.getLogger("test"),
                        clock::get,
                        message -> {},
                        body -> {
                            sent.add(body);
                            if (sent.size() == 1) {
                                firstAckInFlight.countDown();
                                releaseAcks.await(15, TimeUnit.SECONDS);
                            }
                        },
                        nanos::get);
        assertNotNull(sub);
        assertTrue(sub.applySnapshot(snapshot("open", 5)));
        AtomicReference<ControlSubscriber.Verdict> sixth = new AtomicReference<>();
        Thread first =
                new Thread(
                        () -> sixth.set(sub.offer(envelope(6, true, null, gatePayload("open")))));
        first.setDaemon(true);
        first.start();
        assertTrue(
                firstAckInFlight.await(15, TimeUnit.SECONDS), "frame 6 ACK blocks on the worker");
        // Frame 7 closes the gate while frame 6's ACKs are still in flight.
        assertEquals(
                ControlSubscriber.Verdict.APPLIED,
                sub.offer(envelope(7, true, null, gatePayload("closed"))),
                "a stalled ACK endpoint must not delay the next frame");
        assertEquals("closed", sub.current().state());
        assertEquals(7, sub.applied(), "applied tracks the gate, not the ACKs");
        assertEquals(5, sub.cursor(), "the visible cursor still implies the ACKs were sent");
        releaseAcks.countDown();
        first.join(15_000);
        assertFalse(first.isAlive(), "frame 6 returns once its ACKs go through");
        assertEquals(ControlSubscriber.Verdict.APPLIED, sixth.get());
        awaitCursor(sub, 7);
        awaitBodies(sent, 6);
        assertEquals(6, sent.size(), "received, validated and applied per frame, in order");
        List<Integer> seqs =
                sent.stream()
                        .map(
                                body ->
                                        JsonParser.parseString(body)
                                                .getAsJsonObject()
                                                .get("sequence")
                                                .getAsInt())
                        .toList();
        assertEquals(List.of(6, 6, 6, 7, 7, 7), seqs, "one ordered worker keeps frame order");
        sub.stop();
    }

    @Test
    void ackFinishingAfterResyncKeepsCursorResetAndResnapshots() throws Exception {
        AtomicInteger snapshots = new AtomicInteger();
        AtomicInteger streams = new AtomicInteger();
        AtomicLong cursorAtResnapshot = new AtomicLong(Long.MIN_VALUE);
        AtomicReference<ControlSubscriber> subRef = new AtomicReference<>();
        CountDownLatch ackInFlight = new CountDownLatch(1);
        CountDownLatch releaseAck = new CountDownLatch(1);
        String snap = snapshot("open", 5);
        String frame6 = envelope(6, true, null, gatePayload("open"));
        // Applied is 6, so frame 8 is a gap: the loop must reset and re-snapshot.
        String frame8 = envelope(8, true, null, gatePayload("open"));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService pool = Executors.newCachedThreadPool();
        server.setExecutor(pool);
        server.createContext(
                "/v2/agents/" + CONSUMER + "/snapshot",
                exchange -> {
                    if (snapshots.incrementAndGet() == 2) {
                        cursorAtResnapshot.set(subRef.get().cursor());
                    }
                    byte[] body = snap.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                });
        server.createContext(
                "/v2/agents/" + CONSUMER + "/events",
                exchange -> {
                    exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, 0);
                    try {
                        OutputStream out = exchange.getResponseBody();
                        if (streams.incrementAndGet() == 1) {
                            out.write(
                                    ("event: control\ndata: " + frame6 + "\n\n")
                                            .getBytes(StandardCharsets.UTF_8));
                            out.flush();
                            ackInFlight.await(10, TimeUnit.SECONDS);
                            out.write(
                                    ("event: control\ndata: " + frame8 + "\n\n")
                                            .getBytes(StandardCharsets.UTF_8));
                            out.flush();
                        }
                        Thread.sleep(15_000);
                        out.close();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (IOException e) {
                        // The loop closed the stream after the resync; nothing to assert here.
                    }
                });
        server.createContext(
                "/v2/agents/" + CONSUMER + "/acks",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    ackInFlight.countDown();
                    try {
                        releaseAck.await(15, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    byte[] ok = "{}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, ok.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(ok);
                    }
                });
        server.start();
        ControlSubscriber sub = null;
        try {
            sub =
                    ControlSubscriber.fromParts(
                            "http://127.0.0.1:" + server.getAddress().getPort(),
                            "token",
                            CONSUMER,
                            LoggerFactory.getLogger("test"),
                            clock::get,
                            message -> {},
                            null,
                            nanos::get);
            assertNotNull(sub);
            subRef.set(sub);
            sub.start();
            assertTrue(ackInFlight.await(10, TimeUnit.SECONDS), "frame 6 applies, its ACK blocks");
            awaitCursor(sub, -1);
            assertEquals(-1, sub.cursor(), "the gap resets the cursor while the ACK is in flight");
            // Frame 6's ACK finishes after the reset: its advance must be discarded.
            releaseAck.countDown();
            long deadline = System.currentTimeMillis() + 12_000;
            while (snapshots.get() < 2 && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertEquals(2, snapshots.get(), "the loop re-snapshots after a resync");
            assertEquals(
                    -1, cursorAtResnapshot.get(), "a stale ACK must not restore the old cursor");
        } finally {
            if (sub != null) sub.stop();
            releaseAck.countDown();
            server.stop(0);
            pool.shutdownNow();
        }
    }

    @Test
    void oldEpochAckDoesNotLowerCursorAfterSnapshot() throws Exception {
        CountDownLatch firstInFlight = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondInFlight = new CountDownLatch(1);
        CountDownLatch releaseSecond = new CountDownLatch(1);
        List<String> sent = Collections.synchronizedList(new ArrayList<>());
        ControlSubscriber sub =
                ControlSubscriber.fromParts(
                        "https://cp.invalid",
                        "token",
                        CONSUMER,
                        LoggerFactory.getLogger("test"),
                        clock::get,
                        message -> {},
                        body -> {
                            sent.add(body);
                            if (sent.size() == 1) {
                                firstInFlight.countDown();
                                releaseFirst.await(15, TimeUnit.SECONDS);
                            } else if (sent.size() == 4) {
                                // Frame 10's first receipt: frame 6's job, advance included,
                                // is already done, and this one has not advanced yet.
                                secondInFlight.countDown();
                                releaseSecond.await(15, TimeUnit.SECONDS);
                            }
                        },
                        nanos::get);
        assertNotNull(sub);
        try {
            assertTrue(sub.applySnapshot(snapshot("open", 5)));
            assertEquals(
                    ControlSubscriber.Verdict.APPLIED,
                    sub.offer(envelope(6, true, null, gatePayload("open"))));
            assertTrue(firstInFlight.await(15, TimeUnit.SECONDS), "frame 6 ACK blocks");
            assertTrue(sub.applySnapshot(snapshot("open", 9)));
            assertEquals(
                    ControlSubscriber.Verdict.APPLIED,
                    sub.offer(envelope(10, true, null, gatePayload("open"))));
            releaseFirst.countDown();
            assertTrue(secondInFlight.await(15, TimeUnit.SECONDS));
            assertEquals(9, sub.cursor(), "an ACK queued before the snapshot must not lower it");
            releaseSecond.countDown();
            awaitCursor(sub, 10);
            assertEquals(10, sub.cursor(), "a job from the current epoch still advances");
        } finally {
            releaseFirst.countDown();
            releaseSecond.countDown();
            sub.stop();
        }
    }

    @Test
    void partialLineStallReconnectsWithinIdle() throws Exception {
        AtomicInteger events = new AtomicInteger();
        String snap = snapshot("open", 5);
        String frame = envelope(6, true, null, gatePayload("maintenance"));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService pool = Executors.newCachedThreadPool();
        server.setExecutor(pool);
        server.createContext(
                "/v2/agents/" + CONSUMER + "/snapshot",
                exchange -> {
                    byte[] body = snap.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                });
        server.createContext(
                "/v2/agents/" + CONSUMER + "/events",
                exchange -> {
                    int n = events.incrementAndGet();
                    exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, 0);
                    try {
                        OutputStream out = exchange.getResponseBody();
                        if (n == 1) {
                            // Bytes without a newline, then silence: readLine alone waits
                            // forever, so only a bounded read reconnects.
                            out.write("event: con".getBytes(StandardCharsets.UTF_8));
                            out.flush();
                            Thread.sleep(25_000);
                            out.close();
                            return;
                        }
                        out.write(": keep-alive\n\n".getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        out.write(
                                ("event: control\ndata: " + frame + "\n\n")
                                        .getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        Thread.sleep(12_000);
                        out.close();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (IOException e) {
                        // The test stopped the server mid-stream; nothing to assert here.
                    }
                });
        server.createContext(
                "/v2/agents/" + CONSUMER + "/acks",
                exchange -> {
                    byte[] ok = "{}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, ok.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(ok);
                    }
                });
        server.start();
        try {
            ControlSubscriber sub =
                    ControlSubscriber.fromParts(
                            "http://127.0.0.1:" + server.getAddress().getPort(),
                            "token",
                            CONSUMER,
                            LoggerFactory.getLogger("test"),
                            clock::get,
                            message -> {},
                            null,
                            nanos::get);
            assertNotNull(sub);
            sub.start();
            Thread.sleep(1_000);
            nanos.addAndGet(2 * ControlSubscriber.IDLE_NS);
            long deadline = System.currentTimeMillis() + 20_000;
            while (sub.cursor() != 6 && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertEquals(6, sub.cursor(), "a stalled partial line is aborted and replayed");
            assertTrue(events.get() >= 2, "the reconnect opens a fresh stream");
            sub.stop();
        } finally {
            server.stop(0);
            pool.shutdownNow();
        }
    }

    @Test
    void stopClosesTheLiveBodyPromptly() throws Exception {
        String snap = snapshot("open", 5);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v2/agents/" + CONSUMER + "/snapshot",
                exchange -> {
                    byte[] body = snap.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                });
        server.createContext(
                "/v2/agents/" + CONSUMER + "/events",
                exchange -> {
                    exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, 0);
                    try {
                        // Partial line with no newline: interrupt() alone cannot wake the
                        // read, only closing the body can.
                        OutputStream out = exchange.getResponseBody();
                        out.write("event: con".getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        Thread.sleep(25_000);
                        out.close();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (IOException e) {
                        // The test stopped the server mid-stream; nothing to assert here.
                    }
                });
        server.start();
        try {
            ControlSubscriber sub =
                    ControlSubscriber.fromParts(
                            "http://127.0.0.1:" + server.getAddress().getPort(),
                            "token",
                            CONSUMER,
                            LoggerFactory.getLogger("test"),
                            clock::get,
                            message -> {},
                            recording,
                            nanos::get);
            assertNotNull(sub);
            sub.start();
            long deadline = System.currentTimeMillis() + 5_000;
            while (sub.cursor() != 5 && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertEquals(5, sub.cursor(), "the snapshot lands before the stream stalls");
            // Let the reader block on the partial line, then grab the body while the
            // stream still holds it: after stop() the reader may already have exited.
            Thread.sleep(1_500);
            var bodyField = ControlSubscriber.class.getDeclaredField("liveBody");
            bodyField.setAccessible(true);
            InputStream body = null;
            long bodyDeadline = System.currentTimeMillis() + 5_000;
            while (body == null && System.currentTimeMillis() < bodyDeadline) {
                body = (InputStream) bodyField.get(sub);
                if (body == null) Thread.sleep(50);
            }
            assertNotNull(body, "the stream holds its body while reading");
            long started = System.currentTimeMillis();
            sub.stop();
            try {
                assertEquals(-1, body.read(), "stop() closes the body, unblocking the read");
            } catch (IOException e) {
                // A closed body may throw instead of returning -1; either way the read
                // is unblocked instead of stalling on the partial line.
            }
            assertTrue(
                    System.currentTimeMillis() - started < 3_000,
                    "stop() returns promptly instead of outliving the stall");
            var threadField = ControlSubscriber.class.getDeclaredField("streamThread");
            threadField.setAccessible(true);
            Thread thread = (Thread) threadField.get(sub);
            thread.join(3_000);
            assertFalse(thread.isAlive(), "the stream thread exits after the body is closed");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void stopInterruptsAnIdleStreamPromptly() throws Exception {
        String snap = snapshot("open", 5);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v2/agents/" + CONSUMER + "/snapshot",
                exchange -> {
                    byte[] body = snap.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                });
        server.createContext(
                "/v2/agents/" + CONSUMER + "/events",
                exchange -> {
                    exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, 0);
                    try {
                        // Silent stream: stop() must wake the reader anyway.
                        Thread.sleep(25_000);
                        exchange.getResponseBody().close();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (IOException e) {
                        // The test stopped the server mid-stream; nothing to assert here.
                    }
                });
        server.start();
        try {
            // Point a recording subscriber at the stub so no ACK server is needed.
            ControlSubscriber live =
                    ControlSubscriber.fromParts(
                            "http://127.0.0.1:" + server.getAddress().getPort(),
                            "token",
                            CONSUMER,
                            LoggerFactory.getLogger("test"),
                            clock::get,
                            message -> {},
                            recording,
                            nanos::get);
            assertNotNull(live);
            live.start();
            long deadline = System.currentTimeMillis() + 5_000;
            while (live.cursor() != 5 && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertEquals(5, live.cursor(), "the snapshot lands before the stream goes quiet");
            var stop = ControlSubscriber.class.getDeclaredMethod("stop");
            stop.setAccessible(true);
            var field = ControlSubscriber.class.getDeclaredField("streamThread");
            field.setAccessible(true);
            stop.invoke(live);
            Thread thread = (Thread) field.get(live);
            assertNotNull(thread);
            thread.join(3_000);
            assertFalse(thread.isAlive(), "stop() wakes a stream blocked with no keep-alives");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void gateUriNormalizesSlashesAndRejectsGarbage() {
        // P1-2: trailing slash делал путь //v1/gate и закрывал вход навсегда.
        assertEquals(
                "https://cp.invalid:8080/v1/gate",
                ControlSubscriber.gateUri("https://cp.invalid:8080/").toString());
        assertEquals(
                "https://cp.invalid:8080/v1/gate",
                ControlSubscriber.gateUri("  https://cp.invalid:8080  ").toString());
        assertEquals(
                "https://cp.invalid:8080/v1/gate",
                ControlSubscriber.gateUri("https://cp.invalid:8080").toString());
        assertThrows(
                IllegalArgumentException.class,
                () -> ControlSubscriber.gateUri("not a url with spaces"));
    }

    @Test
    void gateUriStripsPathQueryAndFragment() {
        // P1-2 remainder: a path prefix made the poll path /prefix/v1/gate, CP 404s on
        // exact-path match, entry closed from boot forever.
        assertEquals(
                "https://cp.invalid:8080/v1/gate",
                ControlSubscriber.gateUri("https://cp.invalid:8080/prefix").toString());
        assertEquals(
                "https://cp.invalid:8080/v1/gate",
                ControlSubscriber.gateUri("https://cp.invalid:8080/prefix/?next=1#frag")
                        .toString());
        assertEquals(
                "http://[::1]:8080/v1/gate",
                ControlSubscriber.gateUri("http://[::1]:8080/prefix/").toString());
        assertThrows(
                IllegalArgumentException.class, () -> ControlSubscriber.gateUri("cp.invalid:8080"));
    }
}

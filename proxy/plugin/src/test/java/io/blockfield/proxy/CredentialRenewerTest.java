package io.blockfield.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.helpers.NOPLogger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

class CredentialRenewerTest {
    @TempDir Path dir;

    /** Fake CP: one credential per token, renew mints "next-<token>". */
    final class FakeCp implements CredentialRenewer.Transport {
        final List<String> calls = new ArrayList<>();
        Instant issued;
        Instant expires;
        String rejected = "";
        Path state;
        String stateAtConfirm;

        @Override
        public CredentialRenewer.Reply call(String method, String path, String token)
                throws java.io.IOException {
            calls.add(path + " " + token);
            if (token.equals(rejected)) return new CredentialRenewer.Reply(401, "{}");
            return switch (path) {
                case "/v2/me" ->
                        new CredentialRenewer.Reply(
                                200,
                                "{\"issuedAt\":\""
                                        + issued
                                        + "\",\"expiresAt\":\""
                                        + expires.toString().replace("Z", "+00:00")
                                        + "\"}");
                case "/v2/credentials/renew" ->
                        new CredentialRenewer.Reply(
                                201,
                                "{\"token\":\"next-"
                                        + token
                                        + "\",\"expiresAt\":\""
                                        + expires
                                        + "\"}");
                default -> {
                    stateAtConfirm =
                            state != null && Files.exists(state) ? Files.readString(state) : null;
                    yield new CredentialRenewer.Reply(200, "{}");
                }
            };
        }
    }

    long now = Instant.parse("2026-11-12T00:00:00Z").toEpochMilli();

    CredentialRenewer renewer(FakeCp cp, Path state, String env) {
        cp.state = state;
        return new CredentialRenewer(cp, state, env, NOPLogger.NOP_LOGGER, () -> now);
    }

    @Test
    void renewsSevenDaysBeforeExpiryOrAQuarterOfAShortLifetime() throws Exception {
        Instant issued = Instant.ofEpochMilli(now).minus(Duration.ofDays(22));
        Instant expires = issued.plus(Duration.ofDays(30));
        assertEquals(expires.minus(Duration.ofDays(7)), CredentialRenewer.renewAt(issued, expires));
        Instant shortIssued = Instant.ofEpochMilli(now);
        assertEquals(
                shortIssued.plus(Duration.ofMinutes(6)),
                CredentialRenewer.renewAt(shortIssued, shortIssued.plus(Duration.ofMinutes(8))));
        assertEquals(issued, CredentialRenewer.renewAt(issued.plus(Duration.ofDays(31)), issued));

        FakeCp cp = new FakeCp();
        cp.issued = issued;
        cp.expires = expires;
        CredentialRenewer r = renewer(cp, dir.resolve("s.json"), "env");
        // 8 days left: outside the window, sleeps at most an hour and does not renew.
        assertEquals(Duration.ofHours(1).toMillis(), r.tick());
        assertEquals(List.of("/v2/me env"), cp.calls);
        now += Duration.ofDays(1).toMillis();
        assertEquals(0, r.tick());
        assertEquals("next-env", r.token());
    }

    @Test
    void persistsTheSuccessorBeforeConfirmingIt() throws Exception {
        FakeCp cp = new FakeCp();
        cp.issued = Instant.ofEpochMilli(now).minus(Duration.ofDays(29));
        cp.expires = cp.issued.plus(Duration.ofDays(30));
        Path state = dir.resolve("nested/s.json");
        CredentialRenewer r = renewer(cp, state, "env");
        List<String> swapped = new ArrayList<>();
        r.onToken(swapped::add);
        r.tick();
        cp.issued = Instant.ofEpochMilli(now);
        cp.expires = cp.issued.plus(Duration.ofDays(30));
        r.tick();
        assertEquals(
                List.of(
                        "/v2/me env",
                        "/v2/credentials/renew env",
                        "/v2/credentials/renew/confirm next-env",
                        "/v2/me next-env"),
                cp.calls.subList(0, 4));
        assertTrue(cp.stateAtConfirm.contains("\"token\":\"next-env\""), cp.stateAtConfirm);
        assertEquals(List.of("next-env"), swapped);
        assertEquals(
                "rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(state)));
    }

    @Test
    void restartReadsTheStateFileAndConfirmsIt() throws Exception {
        Path state = dir.resolve("s.json");
        CredentialRenewer.persist(state, "next-env", "env", now);
        FakeCp cp = new FakeCp();
        cp.issued = Instant.ofEpochMilli(now);
        cp.expires = cp.issued.plus(Duration.ofDays(30));
        CredentialRenewer r = renewer(cp, state, "env");
        assertEquals("next-env", r.token());
        r.tick();
        assertEquals(
                List.of("/v2/credentials/renew/confirm next-env", "/v2/me next-env"), cp.calls);
    }

    @Test
    void fallsBackToEnvWithoutStateForANewEnvTokenOrOnRejection() throws Exception {
        Path state = dir.resolve("s.json");
        FakeCp cp = new FakeCp();
        cp.issued = Instant.ofEpochMilli(now);
        cp.expires = cp.issued.plus(Duration.ofDays(30));
        assertEquals("env", renewer(cp, state, "env").token());
        // The owner re-issued the env token: the old chain's state file no longer applies.
        CredentialRenewer.persist(state, "next-old", "old-env", now);
        assertEquals("env", renewer(cp, state, "env").token());
        Files.writeString(state, "not json");
        assertEquals("env", renewer(cp, state, "env").token());
        // A persisted token the CP rejects long after its renewal falls back to env at runtime.
        CredentialRenewer.persist(
                state, "next-env", "env", now - CredentialRenewer.LAG_GRACE_MS - 1);
        cp.rejected = "next-env";
        CredentialRenewer r = renewer(cp, state, "env");
        r.tick();
        assertEquals("env", r.token());
        // The rejected successor must not come back after a restart.
        assertFalse(Files.exists(state));
    }

    @Test
    void a401RightAfterAConfirmedRenewalKeepsTheSuccessor() throws Exception {
        Path state = dir.resolve("s.json");
        CredentialRenewer.persist(state, "next-env", "env", now);
        CredentialRenewer r =
                new CredentialRenewer(
                        (method, path, token) ->
                                new CredentialRenewer.Reply(
                                        path.endsWith("/confirm") ? 200 : 401, "{}"),
                        state,
                        "env",
                        NOPLogger.NOP_LOGGER,
                        () -> now);
        assertEquals(CredentialRenewer.RETRY_MS, r.tick());
        assertEquals("next-env", r.token());
    }

    @Test
    void lagging401sAcrossTicksAndARestartKeepTheSuccessorWithinTheGrace() throws Exception {
        Path state = dir.resolve("s.json");
        FakeCp cp = new FakeCp();
        cp.issued = Instant.ofEpochMilli(now).minus(Duration.ofDays(29));
        cp.expires = cp.issued.plus(Duration.ofDays(30));
        CredentialRenewer r = renewer(cp, state, "env");
        assertEquals(0, r.tick());
        // The CP revoked env on confirm, but /v2/me still lags behind for the successor.
        CredentialRenewer.Transport lagging =
                (method, path, token) ->
                        new CredentialRenewer.Reply(path.endsWith("/confirm") ? 200 : 401, "{}");
        r = new CredentialRenewer(lagging, state, "env", NOPLogger.NOP_LOGGER, () -> now);
        for (int i = 0; i < 3; i++) {
            assertEquals(CredentialRenewer.RETRY_MS, r.tick());
            now += CredentialRenewer.RETRY_MS;
        }
        assertEquals("next-env", r.token());
        // A restart replays confirm (404 when already done) and must not drop the successor.
        r =
                new CredentialRenewer(
                        (method, path, token) ->
                                new CredentialRenewer.Reply(
                                        path.endsWith("/confirm") ? 404 : 401, "{}"),
                        state,
                        "env",
                        NOPLogger.NOP_LOGGER,
                        () -> now);
        assertEquals(CredentialRenewer.RETRY_MS, r.tick());
        assertEquals("next-env", r.token());
        assertTrue(Files.exists(state));
        // Past the grace a rejected successor is really rejected: back to env.
        now += CredentialRenewer.LAG_GRACE_MS;
        assertEquals(0, r.tick());
        assertEquals("env", r.token());
        assertFalse(Files.exists(state));
    }

    @Test
    void aCpWithoutRenewalSupportIsCheckedHourly() throws Exception {
        List<String> calls = new ArrayList<>();
        CredentialRenewer r =
                new CredentialRenewer(
                        (method, path, token) -> {
                            calls.add(path);
                            return new CredentialRenewer.Reply(200, "{\"client\":\"proxy\"}");
                        },
                        dir.resolve("s.json"),
                        "env",
                        NOPLogger.NOP_LOGGER,
                        () -> now);
        assertEquals(Duration.ofHours(1).toMillis(), r.tick());
        assertEquals(List.of("/v2/me"), calls);
    }
}

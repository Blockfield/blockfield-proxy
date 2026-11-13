package io.blockfield.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.helpers.NOPLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

class CredentialRenewerTest {
    @TempDir Path dir;

    /** Fake CP: one credential per token, renew mints "next-<token>", confirm revokes the old. */
    final class FakeCp implements CredentialRenewer.Transport {
        final List<String> calls = new ArrayList<>();
        final Set<String> rejected = new HashSet<>();
        Instant issued;
        Instant expires;
        int confirmFailures;
        Path state;
        String stateAtConfirm;

        @Override
        public CredentialRenewer.Reply call(String method, String path, String token)
                throws java.io.IOException {
            calls.add(path + " " + token);
            if (rejected.contains(token)) return new CredentialRenewer.Reply(401, "{}");
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
                    if (confirmFailures > 0) {
                        confirmFailures--;
                        yield new CredentialRenewer.Reply(401, "{}");
                    }
                    rejected.add(token.substring("next-".length()));
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

    /** A CP whose credentials are always inside the renewal window. */
    FakeCp dueCp() {
        FakeCp cp = new FakeCp();
        cp.issued = Instant.ofEpochMilli(now).minus(Duration.ofDays(29));
        cp.expires = cp.issued.plus(Duration.ofDays(30));
        return cp;
    }

    /** Renews env, confirms the successor (revoking env) and renews again, unconfirmed. */
    CredentialRenewer secondRenewal(FakeCp cp, Path state) throws Exception {
        CredentialRenewer r = renewer(cp, state, "env");
        assertEquals(0, r.tick());
        assertEquals(0, r.tick());
        assertEquals("next-next-env", r.token());
        assertTrue(cp.rejected.contains("env"));
        return r;
    }

    static JsonObject saved(Path state) {
        return CredentialRenewer.load(state, "env", NOPLogger.NOP_LOGGER);
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
        assertTrue(cp.stateAtConfirm.contains("\"confirmed\":false"), cp.stateAtConfirm);
        assertTrue(cp.stateAtConfirm.contains("\"predecessor\":\"env\""), cp.stateAtConfirm);
        assertEquals(List.of("next-env"), swapped);
        assertEquals(
                "rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(state)));
        assertTrue(Files.readString(state).contains("\"confirmed\":true"), Files.readString(state));
        assertFalse(saved(state).has("predecessor"));
    }

    @Test
    void restartReadsTheStateFileAndConfirmsIt() throws Exception {
        Path state = dir.resolve("s.json");
        CredentialRenewer.persist(state, "next-env", "env", "env", false);
        FakeCp cp = new FakeCp();
        cp.issued = Instant.ofEpochMilli(now);
        cp.expires = cp.issued.plus(Duration.ofDays(30));
        CredentialRenewer r = renewer(cp, state, "env");
        assertEquals("next-env", r.token());
        r.tick();
        assertEquals(
                List.of("/v2/credentials/renew/confirm next-env", "/v2/me next-env"), cp.calls);
        assertTrue(Files.readString(state).contains("\"confirmed\":true"));
    }

    @Test
    void aReissuedEnvTokenReplacesTheOldChain() throws Exception {
        Path state = dir.resolve("s.json");
        FakeCp cp = dueCp();
        assertEquals("env", renewer(cp, state, "env").token());
        Files.writeString(state, "not json");
        assertEquals("env", renewer(cp, state, "env").token());
        assertEquals("not json", Files.readString(state));
        // The owner re-issued the env token: the old chain's state file no longer applies.
        CredentialRenewer.persist(state, "next-old", "old", "old", false);
        assertEquals("env", renewer(cp, state, "env").token());
        assertEquals("env", saved(state).get("token").getAsString());
        assertTrue(saved(state).get("confirmed").getAsBoolean());
        assertFalse(Files.readString(state).contains("old"), "old chain tokens left on disk");
    }

    @Test
    void aRejectedFirstSuccessorFallsBackToEnvAsItsPredecessor() throws Exception {
        Path state = dir.resolve("s.json");
        CredentialRenewer.persist(state, "next-env", "env", "env", false);
        FakeCp cp = dueCp();
        cp.rejected.add("next-env");
        CredentialRenewer r = renewer(cp, state, "env");
        assertEquals(CredentialRenewer.RETRY_MS, r.tick());
        assertEquals("env", r.token());
        // The rejected successor must not come back after a restart.
        assertEquals("env", renewer(cp, state, "env").token());
    }

    @Test
    void aRejectedSecondSuccessorFallsBackToItsPredecessorNotEnv() throws Exception {
        Path state = dir.resolve("s.json");
        FakeCp cp = dueCp();
        CredentialRenewer r = secondRenewal(cp, state);
        List<String> swapped = new ArrayList<>();
        r.onToken(swapped::add);
        cp.rejected.add("next-next-env");
        assertEquals(CredentialRenewer.RETRY_MS, r.tick());
        assertEquals("next-env", r.token());
        assertEquals(List.of("next-env"), swapped);
        JsonObject saved = saved(state);
        assertEquals("next-env", saved.get("token").getAsString());
        assertTrue(saved.get("confirmed").getAsBoolean());
        assertFalse(saved.has("predecessor"));
        assertEquals("next-env", renewer(cp, state, "env").token());
        // env was revoked by the first confirm: only the first renewal ever used it.
        assertEquals(
                List.of("/v2/me env", "/v2/credentials/renew env"),
                cp.calls.stream().filter(c -> c.endsWith(" env")).toList());
    }

    @Test
    void aRejectedSuccessorAndPredecessorLeaveTheStateFileAlone() throws Exception {
        Path state = dir.resolve("s.json");
        FakeCp cp = dueCp();
        CredentialRenewer r = secondRenewal(cp, state);
        String before = Files.readString(state);
        int seen = cp.calls.size();
        cp.rejected.add("next-next-env");
        cp.rejected.add("next-env");
        for (int i = 0; i < 3; i++) assertEquals(CredentialRenewer.RETRY_MS, r.tick());
        assertEquals("next-next-env", r.token());
        assertEquals(before, Files.readString(state));
        assertFalse(
                cp.calls.subList(seen, cp.calls.size()).contains("/v2/me env"),
                "fell back to the revoked env token");
    }

    @Test
    void aConfirm401KeepsConfirmingUntilItSucceeds() throws Exception {
        Path state = dir.resolve("s.json");
        CredentialRenewer.persist(state, "next-env", "env", "env", false);
        FakeCp cp = new FakeCp();
        cp.issued = Instant.ofEpochMilli(now);
        cp.expires = cp.issued.plus(Duration.ofDays(30));
        cp.confirmFailures = 1;
        CredentialRenewer r = renewer(cp, state, "env");
        assertEquals(CredentialRenewer.RETRY_MS, r.tick());
        assertFalse(saved(state).get("confirmed").getAsBoolean());
        r.tick();
        assertEquals(
                List.of(
                        "/v2/credentials/renew/confirm next-env",
                        "/v2/me next-env",
                        "/v2/me env",
                        "/v2/credentials/renew/confirm next-env",
                        "/v2/me next-env"),
                cp.calls);
        assertTrue(saved(state).get("confirmed").getAsBoolean());
        assertEquals("next-env", r.token());
    }

    @Test
    void aConfirm404ProvesNothingUntilThePredecessorIsRejected() throws Exception {
        Path state = dir.resolve("s.json");
        CredentialRenewer.persist(state, "next-env", "env", "env", false);
        Set<String> rejected = new HashSet<>();
        CredentialRenewer r =
                new CredentialRenewer(
                        (method, path, token) ->
                                new CredentialRenewer.Reply(
                                        path.endsWith("/confirm")
                                                ? 404
                                                : rejected.contains(token) ? 401 : 200,
                                        "{}"),
                        state,
                        "env",
                        NOPLogger.NOP_LOGGER,
                        () -> now);
        // A route-level 404 while the predecessor still works: stay pending, keep confirming.
        assertEquals(CredentialRenewer.RETRY_MS, r.tick());
        assertFalse(saved(state).get("confirmed").getAsBoolean());
        assertEquals("env", saved(state).get("predecessor").getAsString());
        rejected.add("env");
        r.tick();
        assertTrue(saved(state).get("confirmed").getAsBoolean());
        assertFalse(saved(state).has("predecessor"));
        assertEquals("next-env", r.token());
    }

    @Test
    void forcesTheSuccessorFileAndItsDirectoryBeforeConfirming() throws Exception {
        FakeCp cp = dueCp();
        Path state = dir.resolve("s.json");
        CredentialRenewer r = renewer(cp, state, "env");
        CredentialRenewer.forced =
                path ->
                        cp.calls.add(
                                Files.isDirectory(path)
                                        ? "force dir"
                                        : path.getFileName().toString().endsWith(".tmp")
                                                ? "force temp"
                                                : "force " + path.getFileName());
        try {
            assertEquals(0, r.tick());
            r.tick();
        } finally {
            CredentialRenewer.forced = path -> {};
        }
        assertEquals(
                List.of(
                        "/v2/me env",
                        "/v2/credentials/renew env",
                        "force temp",
                        "force dir",
                        "/v2/credentials/renew/confirm next-env"),
                cp.calls.subList(0, 5));
    }

    @Test
    void nonWindowsDirectoryForceFailureThrowsAndDoesNotConfirm() throws Exception {
        Path sub = dir.resolve("write-only");
        Files.createDirectory(sub);
        Path state = sub.resolve("s.json");
        FakeCp cp = dueCp();
        CredentialRenewer r = renewer(cp, state, "env");
        String prevOs = System.getProperty("os.name");
        System.setProperty("os.name", "Linux");
        Files.setPosixFilePermissions(sub, PosixFilePermissions.fromString("-wx------"));
        try {
            assertThrows(
                    IOException.class,
                    () ->
                            CredentialRenewer.persist(
                                    sub.resolve("direct.json"), "x", null, "env", true));
            assertThrows(IOException.class, r::tick);
            assertEquals("env", r.token());
            assertTrue(
                    cp.calls.stream().noneMatch(c -> c.contains("/confirm")), cp.calls.toString());
            // Next tick while the directory still cannot be synced must not confirm either.
            assertThrows(IOException.class, r::tick);
            assertTrue(
                    cp.calls.stream().noneMatch(c -> c.contains("/confirm")), cp.calls.toString());
        } finally {
            Files.setPosixFilePermissions(sub, PosixFilePermissions.fromString("rwx------"));
            if (prevOs == null) System.clearProperty("os.name");
            else System.setProperty("os.name", prevOs);
        }
        assertFalse(saved(state).get("confirmed").getAsBoolean());
        assertEquals(0, r.tick());
        assertFalse(saved(state).get("confirmed").getAsBoolean());
        assertTrue(cp.calls.stream().noneMatch(c -> c.contains("/confirm")), cp.calls.toString());
    }

    @Test
    void forcesNewlyCreatedParentDirectoriesAndExistingAncestorBeforeConfirming() throws Exception {
        FakeCp cp = dueCp();
        Path parent = dir.resolve("a");
        Path leaf = parent.resolve("b");
        Path state = leaf.resolve("s.json");
        CredentialRenewer r = renewer(cp, state, "env");
        CredentialRenewer.forced =
                path ->
                        cp.calls.add(
                                path.equals(dir)
                                        ? "force ancestor"
                                        : path.equals(parent)
                                                ? "force parent"
                                                : path.equals(leaf)
                                                        ? "force leaf"
                                                        : path.getFileName()
                                                                        .toString()
                                                                        .endsWith(".tmp")
                                                                ? "force temp"
                                                                : "force " + path);
        try {
            assertEquals(0, r.tick());
            r.tick();
        } finally {
            CredentialRenewer.forced = path -> {};
        }
        assertEquals(
                List.of(
                        "/v2/me env",
                        "/v2/credentials/renew env",
                        "force temp",
                        "force leaf",
                        "force parent",
                        "force ancestor",
                        "/v2/credentials/renew/confirm next-env"),
                cp.calls.subList(0, 7));
    }

    @Test
    void windowsSkipsDirectoryFlushAndStillPersistsAndConfirms() throws Exception {
        Path state = dir.resolve("s.json");
        Path winWriteOnly = dir.resolve("win-write-only");
        Files.createDirectory(winWriteOnly);
        FakeCp cp = dueCp();
        CredentialRenewer r = renewer(cp, state, "env");
        String prevOs = System.getProperty("os.name");
        System.setProperty("os.name", "Windows 11");
        Files.setPosixFilePermissions(winWriteOnly, PosixFilePermissions.fromString("-wx------"));
        CredentialRenewer.forced =
                path -> cp.calls.add(Files.isDirectory(path) ? "force dir" : "force temp");
        try {
            assertEquals(0, r.tick());
            cp.issued = Instant.ofEpochMilli(now);
            cp.expires = cp.issued.plus(Duration.ofDays(30));
            r.tick();
            CredentialRenewer.persist(
                    winWriteOnly.resolve("s.json"), "next-env", "env", "env", false);
        } finally {
            CredentialRenewer.forced = path -> {};
            Files.setPosixFilePermissions(
                    winWriteOnly, PosixFilePermissions.fromString("rwx------"));
            if (prevOs == null) System.clearProperty("os.name");
            else System.setProperty("os.name", prevOs);
        }
        assertEquals(
                List.of(
                        "/v2/me env",
                        "/v2/credentials/renew env",
                        "force temp",
                        "/v2/credentials/renew/confirm next-env",
                        "force temp",
                        "/v2/me next-env",
                        "force temp"),
                cp.calls);
        assertTrue(saved(state).get("confirmed").getAsBoolean());
    }

    @Test
    void a401RightAfterAConfirmedRenewalKeepsTheSuccessor() throws Exception {
        Path state = dir.resolve("s.json");
        CredentialRenewer.persist(state, "next-env", null, "env", true);
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
    void confirmed401sAcrossTicksAndARestartKeepTheSuccessor() throws Exception {
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
        assertEquals(CredentialRenewer.RETRY_MS, r.tick());
        assertEquals("next-env", r.token());
        assertTrue(Files.readString(state).contains("\"confirmed\":true"));
        // Past the old 10-minute grace a confirmed successor is still kept, never env.
        for (int i = 0; i < 30; i++) {
            assertEquals(CredentialRenewer.RETRY_MS, r.tick());
            now += CredentialRenewer.RETRY_MS;
        }
        assertEquals("next-env", r.token());
        assertTrue(Files.exists(state));
        // A restart with confirmed state keeps the successor without falling back.
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

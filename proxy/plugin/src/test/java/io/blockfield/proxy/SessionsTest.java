package io.blockfield.proxy;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.HexFormat;

class SessionsTest {
    private static final byte[] SECRET = Sessions.secret("forwarding");
    private static final String ACCOUNT = "74b2f4aeceb14b2dabbfc5f41d0a8846";
    private static final long NOW = 1_800_000_000L;

    private static byte[] answer(String jwt, String keyHex, String serverId) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(2);
        out.writeBytes(Authentication.utf(jwt));
        out.writeBytes(
                Authentication.utf(
                        HexFormat.of()
                                .formatHex(
                                        Sessions.hmac(HexFormat.of().parseHex(keyHex), serverId))));
        return out.toByteArray();
    }

    @Test
    void sessionProvesKeyForThisServerIdOnly() {
        String[] session = Sessions.issue(SECRET, "Player_1", ACCOUNT, NOW);
        assertEquals(
                ACCOUNT,
                Sessions.verify(
                        SECRET,
                        "Player_1",
                        "abc",
                        answer(session[0], session[1], "abc"),
                        NOW + 60));
        assertNull(
                Sessions.verify(
                        SECRET,
                        "Player_1",
                        "other",
                        answer(session[0], session[1], "abc"),
                        NOW + 60),
                "перехваченный ответ не переносится");
        assertNull(
                Sessions.verify(
                        SECRET, "player_1", "abc", answer(session[0], session[1], "abc"), NOW + 60),
                "ник с учётом регистра");
        assertNull(
                Sessions.verify(
                        SECRET,
                        "Player_1",
                        "abc",
                        answer(session[0], session[1], "abc"),
                        NOW + Sessions.TTL_SECONDS),
                "истёк");
        assertNull(
                Sessions.verify(
                        Sessions.secret("rotated"),
                        "Player_1",
                        "abc",
                        answer(session[0], session[1], "abc"),
                        NOW),
                "другой секрет");
        String forged = session[0].substring(0, session[0].lastIndexOf('.') + 1) + "AAAA";
        assertNull(
                Sessions.verify(SECRET, "Player_1", "abc", answer(forged, session[1], "abc"), NOW));
        assertNull(
                Sessions.verify(SECRET, "Player_1", "abc", new byte[] {2, 5, 1}, NOW),
                "мусор не роняет прокси");
    }

    @Test
    void legacyAndMalformedAccountClaimsCannotAuthenticate() {
        String[] session = Sessions.issue(SECRET, "Player_1", ACCOUNT, NOW);
        String[] parts = session[0].split("\\.");
        for (String identity : new String[] {null, "not-a-uuid"}) {
            var claims =
                    com.google.gson.JsonParser.parseString(
                                    new String(
                                            java.util.Base64.getUrlDecoder().decode(parts[1]),
                                            java.nio.charset.StandardCharsets.UTF_8))
                            .getAsJsonObject();
            if (identity == null) claims.remove("uuid");
            else claims.addProperty("uuid", identity);
            String unsigned =
                    parts[0]
                            + "."
                            + java.util.Base64.getUrlEncoder()
                                    .withoutPadding()
                                    .encodeToString(
                                            claims.toString()
                                                    .getBytes(
                                                            java.nio.charset.StandardCharsets
                                                                    .UTF_8));
            String jwt =
                    unsigned
                            + "."
                            + java.util.Base64.getUrlEncoder()
                                    .withoutPadding()
                                    .encodeToString(Sessions.hmac(SECRET, unsigned));
            assertNull(
                    Sessions.verify(
                            SECRET,
                            "Player_1",
                            "challenge",
                            answer(jwt, session[1], "challenge"),
                            NOW));
        }
    }
}

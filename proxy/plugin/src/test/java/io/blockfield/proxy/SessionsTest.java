package io.blockfield.proxy;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

class SessionsTest {
    private static final byte[] SECRET = Sessions.secret("forwarding");
    private static final long NOW = 1_800_000_000L;

    private static byte[] answer(String jwt, String keyHex, String serverId) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(2);
        out.writeBytes(BlockfieldProxy.utf(jwt));
        out.writeBytes(BlockfieldProxy.utf(HexFormat.of().formatHex(Sessions.hmac(HexFormat.of().parseHex(keyHex), serverId))));
        return out.toByteArray();
    }

    @Test
    void sessionProvesKeyForThisServerIdOnly() {
        String[] session = Sessions.issue(SECRET, "Player_1", NOW);
        assertTrue(Sessions.verify(SECRET, "Player_1", "abc", answer(session[0], session[1], "abc"), NOW + 60));
        assertFalse(Sessions.verify(SECRET, "Player_1", "other", answer(session[0], session[1], "abc"), NOW + 60), "перехваченный ответ не переносится");
        assertFalse(Sessions.verify(SECRET, "player_1", "abc", answer(session[0], session[1], "abc"), NOW + 60), "ник с учётом регистра");
        assertFalse(Sessions.verify(SECRET, "Player_1", "abc", answer(session[0], session[1], "abc"), NOW + Sessions.TTL_SECONDS), "истёк");
        assertFalse(Sessions.verify(Sessions.secret("rotated"), "Player_1", "abc", answer(session[0], session[1], "abc"), NOW), "другой секрет");
        String forged = session[0].substring(0, session[0].lastIndexOf('.') + 1) + "AAAA";
        assertFalse(Sessions.verify(SECRET, "Player_1", "abc", answer(forged, session[1], "abc"), NOW));
        assertFalse(Sessions.verify(SECRET, "Player_1", "abc", new byte[]{2, 5, 1}, NOW), "мусор не роняет прокси");
    }
}

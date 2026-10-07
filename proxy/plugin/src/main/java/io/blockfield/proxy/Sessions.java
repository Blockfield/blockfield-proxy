package io.blockfield.proxy;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Запомненный вход для игроков без токена Drasl (Prism offline). После /login клиент получает JWT
 * (HS256, ник и Drasl UUID) и отдельный ключ; при следующем входе он шлёт JWT и HMAC(ключ,
 * serverId). Ключ выводится из jti и секрета прокси и в JWT не лежит, поэтому перехваченный ответ
 * не годится для другого serverId.
 */
final class Sessions {
    static final long TTL_SECONDS = 30L * 24 * 3600;
    private static final String HEADER =
            b64("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
    private static final SecureRandom RANDOM = new SecureRandom();

    private Sessions() {}

    static byte[] secret(String forwardingSecret) {
        return hmac(forwardingSecret.getBytes(StandardCharsets.UTF_8), "blockfield-session-v1");
    }

    /** {jwt, ключ в hex}. */
    static String[] issue(byte[] secret, String name, String draslId, long now) {
        if (draslId == null || !draslId.matches("[0-9a-f]{32}"))
            throw new IllegalArgumentException("Drasl UUID");
        byte[] id = new byte[16];
        RANDOM.nextBytes(id);
        String jti = HexFormat.of().formatHex(id);
        JsonObject claims = new JsonObject();
        claims.addProperty("sub", name);
        claims.addProperty("uuid", draslId);
        claims.addProperty("jti", jti);
        claims.addProperty("iat", now);
        claims.addProperty("exp", now + TTL_SECONDS);
        String unsigned = HEADER + "." + b64(claims.toString().getBytes(StandardCharsets.UTF_8));
        return new String[] {
            unsigned + "." + b64(hmac(secret, unsigned)), HexFormat.of().formatHex(key(secret, jti))
        };
    }

    /**
     * Ответ клиента на login-запрос: байт 2, затем writeUtf(jwt) и writeUtf(hex HMAC(ключ,
     * serverId)). Возвращает Drasl UUID, либо null. Старые JWT без UUID не различают владельцев
     * повторно выданного ника и требуют нового /login.
     */
    static String verify(byte[] secret, String name, String serverId, byte[] response, long now) {
        try {
            ByteBuffer in = ByteBuffer.wrap(response);
            if (in.get() != 2) return null;
            String jwt = readUtf(in, 2048);
            String proof = readUtf(in, 64);
            if (in.hasRemaining()) return null;
            String[] parts = jwt.split("\\.");
            if (parts.length != 3 || !parts[0].equals(HEADER)) return null;
            if (!MessageDigest.isEqual(
                    Base64.getUrlDecoder().decode(parts[2]),
                    hmac(secret, parts[0] + "." + parts[1]))) return null;
            JsonObject claims =
                    JsonParser.parseString(
                                    new String(
                                            Base64.getUrlDecoder().decode(parts[1]),
                                            StandardCharsets.UTF_8))
                            .getAsJsonObject();
            if (!claims.get("sub").getAsString().equals(name)
                    || claims.get("exp").getAsLong() <= now) return null;
            String draslId = claims.get("uuid").getAsString();
            if (!draslId.matches("[0-9a-f]{32}")) return null;
            byte[] expected = hmac(key(secret, claims.get("jti").getAsString()), serverId);
            return MessageDigest.isEqual(HexFormat.of().parseHex(proof), expected) ? draslId : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] key(byte[] secret, String jti) {
        return hmac(secret, "key:" + jti);
    }

    private static String readUtf(ByteBuffer in, int max) {
        int length = 0;
        for (int shift = 0; ; shift += 7) {
            if (shift > 28) throw new IllegalArgumentException("varint");
            byte b = in.get();
            length |= (b & 0x7f) << shift;
            if (b >= 0) break;
        }
        if (length < 0 || length > max * 3) throw new IllegalArgumentException("too long");
        byte[] data = new byte[length];
        in.get(data);
        return new String(data, StandardCharsets.UTF_8);
    }

    private static String b64(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }
}

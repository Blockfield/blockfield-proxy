package io.blockfield.proxy;

import com.google.common.net.InetAddresses;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Explicit dev admission based only on the transport peer, never a handshake host. */
final class OfflineAccess {
    private record Network(byte[] address, int prefix) {}

    private final List<Network> networks = new ArrayList<>();

    OfflineAccess(String value) {
        if (value == null || value.isBlank()) return;
        for (String item : value.split(",", -1)) {
            String[] parts = item.strip().split("/", -1);
            if (parts.length > 2 || parts[0].isEmpty())
                throw new IllegalArgumentException("Invalid offline IP/CIDR: " + item);
            byte[] address = InetAddresses.forString(parts[0]).getAddress();
            int bits = address.length * 8;
            if (parts.length == 2 && !parts[1].matches("[0-9]{1,3}"))
                throw new IllegalArgumentException("Invalid offline prefix: " + item);
            int prefix = parts.length == 1 ? bits : Integer.parseInt(parts[1]);
            if (prefix > bits)
                throw new IllegalArgumentException("Invalid offline prefix: " + item);
            for (int bit = prefix; bit < bits; bit++) {
                if ((address[bit / 8] & (1 << (7 - bit % 8))) != 0)
                    throw new IllegalArgumentException("Offline CIDR has host bits: " + item);
            }
            networks.add(new Network(address, prefix));
        }
    }

    boolean enabled() {
        return !networks.isEmpty();
    }

    boolean allows(InetAddress peer) {
        if (peer == null) return false;
        byte[] address = peer.getAddress();
        for (Network network : networks) {
            if (address.length != network.address().length) continue;
            int bit = 0;
            while (bit < network.prefix()
                    && ((address[bit / 8] ^ network.address()[bit / 8]) & (1 << (7 - bit % 8)))
                            == 0) bit++;
            if (bit == network.prefix()) return true;
        }
        return false;
    }

    static UUID uuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Production wiring (gate file, Workshop host) means a misconfigured prod proxy: fail closed.
     */
    static void requireDevEnvironment(Map<String, String> environment) {
        for (String name : List.of("BLOCKFIELD_GATE_FILE", "BLOCKFIELD_WORKSHOP_HOST")) {
            String value = environment.get(name);
            if (value != null && !value.isBlank())
                throw new IllegalArgumentException(
                        "Offline dev login is refused with production " + name + " set");
        }
    }

    static void requireDirectPeer(Object configuration) {
        // Velocity's API omits this flag; unknown implementations must fail closed.
        try {
            if (!Boolean.FALSE.equals(
                    configuration.getClass().getMethod("isProxyProtocol").invoke(configuration)))
                throw new IllegalArgumentException(
                        "Offline dev login requires HAProxy protocol disabled");
        } catch (ReflectiveOperationException error) {
            throw new IllegalArgumentException("Cannot verify direct transport peer", error);
        }
    }
}

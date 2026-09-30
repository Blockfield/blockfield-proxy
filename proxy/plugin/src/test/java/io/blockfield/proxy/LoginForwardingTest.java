package io.blockfield.proxy;

import static io.blockfield.proxy.GateEvictionTest.fake;

import static org.junit.jupiter.api.Assertions.*;

import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerLoginPluginMessageEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class LoginForwardingTest {
    @Test
    void packReplyAndTaczConfigSurviveLobbyAndAreClearedOnDisconnect() {
        var forwarded = new AtomicReference<byte[]>();
        var deliveries = new AtomicInteger();
        var current = new AtomicReference<ServerConnection>();
        UUID id = UUID.randomUUID();
        Player player =
                fake(
                        Player.class,
                        (method, args) ->
                                switch (method) {
                                    case "getUniqueId" -> id;
                                    case "getCurrentServer" -> Optional.ofNullable(current.get());
                                    case "sendPluginMessage" -> {
                                        assertEquals(LoginForwarding.TACZ_TWEAKS, args[0]);
                                        forwarded.set((byte[]) args[1]);
                                        deliveries.incrementAndGet();
                                        yield true;
                                    }
                                    default -> null;
                                });
        ServerConnection connection =
                fake(
                        ServerConnection.class,
                        (method, args) -> method.equals("getPlayer") ? player : null);
        LoginForwarding forwarding = new LoginForwarding();
        var missing =
                new ServerLoginPluginMessageEvent(connection, LoginForwarding.PACK, new byte[0], 0);
        forwarding.onBackendLoginMessage(missing);
        assertFalse(missing.getResult().isAllowed());
        byte[] version = {1, 2, 3};
        forwarding.rememberPack(id, version);
        var pack =
                new ServerLoginPluginMessageEvent(connection, LoginForwarding.PACK, new byte[0], 1);
        forwarding.onBackendLoginMessage(pack);
        assertArrayEquals(version, pack.getResult().getResponse());
        byte[] config = {4, 5, 6};
        var tacz =
                new ServerLoginPluginMessageEvent(
                        connection, LoginForwarding.TACZ_TWEAKS, config, 2);
        forwarding.onBackendLoginMessage(tacz);
        assertArrayEquals(new byte[0], tacz.getResult().getResponse());

        current.set(on("lobby"));
        forwarding.onPostConnect(new ServerPostConnectEvent(player, null));
        assertEquals(0, deliveries.get());
        current.set(on("game"));
        forwarding.onPostConnect(new ServerPostConnectEvent(player, null));
        assertArrayEquals(config, forwarded.get());
        forwarding.onPostConnect(new ServerPostConnectEvent(player, null));
        assertEquals(1, deliveries.get(), "TaCZ config is sent exactly once");
        forwarding.onBackendLoginMessage(tacz);
        forwarding.onDisconnect(
                new DisconnectEvent(player, DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN));
        forwarding.onPostConnect(new ServerPostConnectEvent(player, null));
        assertEquals(1, deliveries.get(), "disconnect clears retained config");
        forwarding.onBackendLoginMessage(pack);
        assertFalse(pack.getResult().isAllowed(), "disconnect clears cached pack response");
    }

    private static ServerConnection on(String name) {
        RegisteredServer server =
                fake(
                        RegisteredServer.class,
                        (method, args) ->
                                method.equals("getServerInfo")
                                        ? new ServerInfo(
                                                name,
                                                InetSocketAddress.createUnresolved("127.0.0.1", 1))
                                        : null);
        return fake(
                ServerConnection.class,
                (method, args) -> method.equals("getServer") ? server : null);
    }
}

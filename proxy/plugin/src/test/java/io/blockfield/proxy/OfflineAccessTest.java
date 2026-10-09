package io.blockfield.proxy;

import static io.blockfield.proxy.GateEvictionTest.fake;

import static org.junit.jupiter.api.Assertions.*;

import com.velocitypowered.api.event.Continuation;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.player.GameProfileRequestEvent;
import com.velocitypowered.api.event.player.ServerLoginPluginMessageEvent;
import com.velocitypowered.api.proxy.LoginPhaseConnection;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.util.GameProfile;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

class OfflineAccessTest {
    @Test
    void strictNetworksAreDisabledByDefaultAndNeverResolveHostnames() throws Exception {
        assertFalse(new OfflineAccess(null).enabled());
        assertFalse(new OfflineAccess("  ").allows(InetAddress.getLoopbackAddress()));
        var access = new OfflineAccess("212.8.46.110/32,192.168.0.0/24,2001:db8::/32");
        assertTrue(access.allows(InetAddress.getByName("212.8.46.110")));
        assertTrue(access.allows(InetAddress.getByName("192.168.0.255")));
        assertTrue(access.allows(InetAddress.getByName("2001:db8::1234")));
        assertFalse(access.allows(InetAddress.getByName("212.8.46.111")));
        assertFalse(access.allows(InetAddress.getByName("192.168.1.1")));
        assertFalse(access.allows(InetAddress.getByName("2001:db9::1")));
        assertFalse(access.allows(null));
        for (String bad :
                List.of(
                        "localhost",
                        "192.168.0.1/24",
                        "::1/64",
                        "10.0.0.0/33",
                        "::/129",
                        "10.0.0.0/-1",
                        "10.0.0.0/",
                        "10.0.0.0/8/1",
                        "10.0.0.0/8,",
                        "999.0.0.1")) {
            assertThrows(IllegalArgumentException.class, () -> new OfflineAccess(bad), bad);
        }
        assertEquals(
                GameProfile.forOfflinePlayer("BFTest1").getId(), OfflineAccess.uuid("BFTest1"));
        assertNotEquals(OfflineAccess.uuid("BFTest1"), OfflineAccess.uuid("bftest1"));
    }

    public static final class DirectConfig {
        public boolean isProxyProtocol() {
            return false;
        }
    }

    public static final class ForwardedConfig {
        public boolean isProxyProtocol() {
            return true;
        }
    }

    @Test
    void forwardedOrUnknownPeerConfigurationIsRefused() {
        OfflineAccess.requireDirectPeer(new DirectConfig());
        assertThrows(
                IllegalArgumentException.class,
                () -> OfflineAccess.requireDirectPeer(new ForwardedConfig()));
        assertThrows(
                IllegalArgumentException.class,
                () -> OfflineAccess.requireDirectPeer(new Object()));
    }

    @Test
    void offlineLoginSkipsOnlyAuthAndKeepsCompatibilityReplies() throws Exception {
        for (String peer : List.of("192.168.0.42", "2001:db8::42", "203.0.113.42")) {
            var remote = new InetSocketAddress(InetAddress.getByName(peer), 12345);
            var queries = new ArrayList<Object>();
            byte[] reply = {1, 2, 3};
            LoginPhaseConnection connection =
                    fake(
                            LoginPhaseConnection.class,
                            (method, args) -> {
                                if (method.equals("getRemoteAddress")) return remote;
                                if (method.equals("sendLoginPluginMessage")) {
                                    queries.add(args[0]);
                                    ((LoginPhaseConnection.MessageConsumer) args[2])
                                            .onMessageResponse(
                                                    args[0].equals(Authentication.AUTH)
                                                            ? null
                                                            : reply);
                                }
                                return null;
                            });
            var forwarding = new LoginForwarding();
            var auth =
                    new Authentication(
                            this,
                            null,
                            LoggerFactory.getLogger("test"),
                            forwarding,
                            "http://127.0.0.1:1",
                            null,
                            new OfflineAccess("192.168.0.0/24,2001:db8::/32"));
            var pre = new PreLoginEvent(connection, "BFTest1");
            auth.onPreLogin(pre);
            boolean allowed = !peer.startsWith("203.");
            assertEquals(allowed, pre.getResult().isForceOfflineMode());
            assertEquals(
                    allowed
                            ? List.of(LoginForwarding.PACK, LoginForwarding.CONTRACT)
                            : List.of(
                                    LoginForwarding.PACK,
                                    LoginForwarding.CONTRACT,
                                    Authentication.AUTH),
                    queries);
            var profile =
                    new GameProfileRequestEvent(
                            connection, GameProfile.forOfflinePlayer("BFTest1"), false);
            await(auth.onProfile(profile));
            assertEquals(OfflineAccess.uuid("BFTest1"), profile.getGameProfile().getId());
            Player player =
                    fake(
                            Player.class,
                            (method, args) ->
                                    switch (method) {
                                        case "getRemoteAddress" -> remote;
                                        case "getUsername" -> "BFTest1";
                                        case "getUniqueId" -> profile.getGameProfile().getId();
                                        default -> null;
                                    });
            await(auth.onLogin(new LoginEvent(player)));
            assertEquals(allowed, auth.authenticated(player));
            assertNull(
                    auth.profileId(player.getUniqueId()),
                    "offline identities cannot satisfy Drasl workshop ACLs");
            var backend =
                    fake(
                            ServerConnection.class,
                            (method, args) -> method.equals("getPlayer") ? player : null);
            for (var channel : List.of(LoginForwarding.PACK, LoginForwarding.CONTRACT)) {
                var message = new ServerLoginPluginMessageEvent(backend, channel, new byte[0], 1);
                forwarding.onBackendLoginMessage(message);
                assertArrayEquals(reply, message.getResult().getResponse());
            }
        }
    }

    private static void await(EventTask task) throws Exception {
        var done = new CompletableFuture<Void>();
        task.execute(
                new Continuation() {
                    public void resume() {
                        done.complete(null);
                    }

                    public void resumeWithException(Throwable error) {
                        done.completeExceptionally(error);
                    }
                });
        done.get(2, TimeUnit.SECONDS);
    }
}

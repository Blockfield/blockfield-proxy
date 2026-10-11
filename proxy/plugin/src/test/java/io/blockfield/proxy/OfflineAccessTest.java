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
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.config.ProxyConfig;
import com.velocitypowered.api.util.GameProfile;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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

    /** Test-only transport flag: Velocity's API omits it, the server class carries it. */
    interface PeerConfig {
        boolean isProxyProtocol();
    }

    static ProxyServer proxyWithPeer(boolean proxyProtocol) {
        Object configuration =
                Proxy.newProxyInstance(
                        OfflineAccessTest.class.getClassLoader(),
                        new Class<?>[] {ProxyConfig.class, PeerConfig.class},
                        (self, method, args) ->
                                method.getName().equals("isProxyProtocol") ? proxyProtocol : null);
        return fake(
                ProxyServer.class,
                (method, args) -> method.equals("getConfiguration") ? configuration : null);
    }

    @Test
    void offlineLoginIsRefusedOnForwardedTransports() throws Exception {
        var remote = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 12345);
        LoginPhaseConnection connection =
                fake(
                        LoginPhaseConnection.class,
                        (method, args) -> method.equals("getRemoteAddress") ? remote : null);
        var offline = new OfflineAccess("127.0.0.0/24");
        var refused = new PreLoginEvent(connection, "BFTest1");
        new Authentication(
                        this,
                        proxyWithPeer(true),
                        LoggerFactory.getLogger("test"),
                        new LoginForwarding(),
                        "http://127.0.0.1:1",
                        null,
                        offline)
                .onPreLogin(refused);
        assertFalse(refused.getResult().isAllowed());
        assertFalse(refused.getResult().isForceOfflineMode());
        var allowed = new PreLoginEvent(connection, "BFTest1");
        new Authentication(
                        this,
                        proxyWithPeer(false),
                        LoggerFactory.getLogger("test"),
                        new LoginForwarding(),
                        "http://127.0.0.1:1",
                        null,
                        offline)
                .onPreLogin(allowed);
        assertTrue(allowed.getResult().isForceOfflineMode());
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
            // A fresh id, unlike forOfflinePlayer's: the assertion below only proves the
            // offline override when the input id differs from the offline one.
            var inputId = UUID.randomUUID();
            var profile =
                    new GameProfileRequestEvent(
                            connection, new GameProfile(inputId, "BFTest1", List.of()), false);
            await(auth.onProfile(profile));
            if (allowed) {
                assertEquals(OfflineAccess.uuid("BFTest1"), profile.getGameProfile().getId());
            } else {
                assertEquals(
                        inputId,
                        profile.getGameProfile().getId(),
                        "a refused login keeps the client-provided profile");
            }
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

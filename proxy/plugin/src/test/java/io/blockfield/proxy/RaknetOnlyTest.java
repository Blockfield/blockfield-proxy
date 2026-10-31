package io.blockfield.proxy;

import static org.junit.jupiter.api.Assertions.*;

import com.velocitypowered.api.proxy.InboundConnection;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Map;

class RaknetOnlyTest {
    /** Velocity's LoginInboundConnection → MinecraftConnection → Netty Channel → pipeline. */
    static final class Login {
        private final Minecraft minecraft;

        Login(Map<String, Object> handlers) {
            this(handlers, null);
        }

        Login(Map<String, Object> handlers, SocketAddress local) {
            minecraft = new Minecraft(new Channel(new Pipeline(handlers), local));
        }

        @SuppressWarnings("unused")
        private Minecraft delegatedConnection() {
            return minecraft;
        }
    }

    record Minecraft(Channel getChannel) {}

    abstract static class AbstractChannel {
        private final Pipeline pipeline;

        AbstractChannel(Pipeline pipeline) {
            this.pipeline = pipeline;
        }

        public Pipeline pipeline() {
            return pipeline;
        }
    }

    static final class Channel extends AbstractChannel {
        private final SocketAddress local;

        Channel(Pipeline pipeline, SocketAddress local) {
            super(pipeline);
            this.local = local;
        }

        public SocketAddress localAddress() {
            return local;
        }
    }

    record Pipeline(Map<String, Object> handlers) {
        public Object get(String name) {
            return handlers.get(name);
        }
    }

    interface Delegating {
        Minecraft delegatedConnection();
    }

    /** Makes a Velocity API fake look like a login that arrived over RakNet. */
    @SuppressWarnings("unchecked")
    static <T> T overRaknet(Class<T> type, T fake) {
        return overTransport(type, fake, true, null);
    }

    /** Makes a Velocity API fake look like a login that arrived over plain TCP. */
    @SuppressWarnings("unchecked")
    static <T> T overTcp(Class<T> type, T fake) {
        return overTransport(type, fake, false, null);
    }

    /** Makes a Velocity API fake look like a RakNet login on the given inbound port. */
    @SuppressWarnings("unchecked")
    static <T> T overRaknetOnPort(Class<T> type, T fake, int port) {
        return overTransport(type, fake, true, new InetSocketAddress("127.0.0.1", port));
    }

    @SuppressWarnings("unchecked")
    static <T> T overTransport(Class<T> type, T fake, boolean raknet, SocketAddress local) {
        Minecraft channel =
                new Login(
                                Map.of(
                                        raknet ? RaknetOnly.RAKNET_HANDLER : "handler",
                                        new Object()),
                                local)
                        .delegatedConnection();
        return (T)
                Proxy.newProxyInstance(
                        RaknetOnlyTest.class.getClassLoader(),
                        new Class<?>[] {type, Delegating.class},
                        (self, method, args) -> {
                            if (method.getName().equals("delegatedConnection")) return channel;
                            try {
                                return method.invoke(fake, args);
                            } catch (InvocationTargetException error) {
                                throw error.getCause();
                            }
                        });
    }

    @Test
    void onlyRaknetifyPipelinesMayLogIn() throws Exception {
        assertTrue(RaknetOnly.raknet(new Login(Map.of(RaknetOnly.RAKNET_HANDLER, new Object()))));
        assertFalse(RaknetOnly.raknet(new Login(Map.of("handler", new Object()))));
    }

    @Test
    void inboundPortReadsChannelLocalAddress() throws Exception {
        var login =
                new Login(
                        Map.of(RaknetOnly.RAKNET_HANDLER, new Object()),
                        new InetSocketAddress("127.0.0.1", 25567));
        assertEquals(25567, RaknetOnly.inboundPort(login));
    }

    @Test
    void unknownInternalsAreReportedInsteadOfGuessed() {
        InboundConnection api = GateEvictionTest.fake(InboundConnection.class, (m, a) -> null);
        assertThrows(ReflectiveOperationException.class, () -> RaknetOnly.raknet(api));
    }

    @Test
    void mismatchedInternalsRefuseEveryLogin() {
        InboundConnection api = GateEvictionTest.fake(InboundConnection.class, (m, a) -> null);
        var reported = new ArrayList<Exception>();
        assertSame(RaknetOnly.UNAVAILABLE, RaknetOnly.denial(api, reported::add));
        assertEquals(1, reported.size());
        assertSame(
                RaknetOnly.DENIED,
                RaknetOnly.denial(new Login(Map.of("handler", new Object())), reported::add));
        assertNull(
                RaknetOnly.denial(
                        new Login(Map.of(RaknetOnly.RAKNET_HANDLER, new Object())), reported::add));
        assertEquals(1, reported.size());
    }
}

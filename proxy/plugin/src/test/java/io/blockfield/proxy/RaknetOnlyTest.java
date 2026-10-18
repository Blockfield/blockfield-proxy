package io.blockfield.proxy;

import static org.junit.jupiter.api.Assertions.*;

import com.velocitypowered.api.proxy.InboundConnection;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Map;

class RaknetOnlyTest {
    /** Velocity's LoginInboundConnection → MinecraftConnection → Netty Channel → pipeline. */
    static final class Login {
        private final Minecraft minecraft;

        Login(Map<String, Object> handlers) {
            minecraft = new Minecraft(new Channel(new Pipeline(handlers)));
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
        Channel(Pipeline pipeline) {
            super(pipeline);
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
        Minecraft raknet =
                new Login(Map.of(RaknetOnly.RAKNET_HANDLER, new Object())).delegatedConnection();
        return (T)
                Proxy.newProxyInstance(
                        RaknetOnlyTest.class.getClassLoader(),
                        new Class<?>[] {type, Delegating.class},
                        (self, method, args) -> {
                            if (method.getName().equals("delegatedConnection")) return raknet;
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

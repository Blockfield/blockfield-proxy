package io.blockfield.proxy;

import static org.junit.jupiter.api.Assertions.*;

import com.velocitypowered.api.proxy.InboundConnection;

import org.junit.jupiter.api.Test;

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
}

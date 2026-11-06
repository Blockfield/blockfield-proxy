package io.blockfield.proxy;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;

import org.slf4j.Logger;

import java.util.concurrent.TimeUnit;

/**
 * Velocity lifecycle and ownership of the proxy's authentication, routing, and compatibility
 * modules.
 */
@Plugin(id = "blockfield-proxy", name = "Blockfield Proxy", version = "1.0.0")
public final class BlockfieldProxy {
    private final ProxyServer proxy;
    private final LoginForwarding forwarding;
    private final Authentication authentication;
    private final BackendRouting routing;

    @Inject
    public BlockfieldProxy(ProxyServer proxy, Logger logger) {
        this.proxy = proxy;
        try {
            forwarding = new LoginForwarding();
            authentication = new Authentication(this, proxy, logger, forwarding);
            routing = new BackendRouting(this, proxy, logger, authentication, forwarding);
        } catch (RuntimeException e) {
            // Fail closed: a half-built plugin leaves Velocity serving with no auth
            // gate, so stop the proxy instead of running open.
            logger.error("Blockfield plugin failed to initialize, stopping the proxy", e);
            try {
                proxy.shutdown();
            } catch (RuntimeException shutdownError) {
                logger.error("Proxy shutdown failed", shutdownError);
            }
            throw e;
        }
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent event) {
        var events = proxy.getEventManager();
        events.register(this, authentication);
        events.register(this, routing);
        events.register(this, forwarding);
        authentication.registerCommands(routing::admit);
        proxy.getChannelRegistrar().register(LoginForwarding.TACZ_TWEAKS, Authentication.SESSION);
        proxy.getScheduler()
                .buildTask(
                        this,
                        () -> {
                            authentication.expire();
                            routing.tick();
                        })
                .repeat(3, TimeUnit.SECONDS)
                .schedule();
    }
}

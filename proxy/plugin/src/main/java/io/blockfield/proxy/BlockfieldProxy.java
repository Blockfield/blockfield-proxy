package io.blockfield.proxy;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;

import org.slf4j.Logger;

import java.util.Map;
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
        this(proxy, logger, System.getenv("BLOCKFIELD_OFFLINE_ALLOWED_IPS"));
    }

    BlockfieldProxy(ProxyServer proxy, Logger logger, String offlineAllowedIps) {
        this(proxy, logger, offlineAllowedIps, System.getenv());
    }

    BlockfieldProxy(
            ProxyServer proxy,
            Logger logger,
            String offlineAllowedIps,
            Map<String, String> environment) {
        this.proxy = proxy;
        forwarding = new LoginForwarding();
        try {
            OfflineAccess offline = new OfflineAccess(offlineAllowedIps);
            if (offline.enabled()) {
                OfflineAccess.requireDirectPeer(proxy.getConfiguration());
                OfflineAccess.requireDevEnvironment(environment);
                logger.warn(
                        "DEV OFFLINE LOGIN ENABLED: allowlisted transport peers can impersonate any"
                                + " name; never enable in production");
            }
            authentication = new Authentication(this, proxy, logger, forwarding);
        } catch (IllegalArgumentException error) {
            logger.error("Refusing proxy startup: invalid offline dev configuration", error);
            proxy.shutdown();
            throw error;
        }
        routing = new BackendRouting(this, proxy, logger, authentication);
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

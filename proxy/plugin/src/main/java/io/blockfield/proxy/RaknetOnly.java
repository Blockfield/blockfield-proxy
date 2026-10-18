package io.blockfield.proxy;

import net.kyori.adventure.text.Component;

import java.lang.reflect.Method;
import java.util.function.Consumer;

/** Players log in only over RakNet; TCP 25565 stays open for status pings. */
final class RaknetOnly {
    /**
     * Raknetify Velocity 0.1.0+alpha.5.196 {@code RakNetVelocityConnectionUtil.postInitChannel}
     * adds this handler only when {@code channel.config() instanceof RakNet$Config} and never
     * removes it. Its classes live in Raknetify's own class loader, so match the pipeline name.
     */
    static final String RAKNET_HANDLER = "raknetify-velocity-event-listener";

    static final Component DENIED =
            Component.text(
                    "Вход только через лаунчер Blockfield или по адресу"
                            + " raknet;play.blockfield.pro:25566");

    static final Component UNAVAILABLE =
            Component.text("Вход временно недоступен: прокси не может проверить RakNet");

    private RaknetOnly() {}

    /**
     * Fails closed (owner decision 2026-10-18): when Velocity/Raknetify internals no longer match,
     * every login is refused rather than letting TCP logins in unnoticed.
     *
     * @return the denial reason, or {@code null} for a RakNet login
     */
    static Component denial(Object connection, Consumer<Exception> mismatch) {
        try {
            return raknet(connection) ? null : DENIED;
        } catch (ReflectiveOperationException | RuntimeException error) {
            mismatch.accept(error);
            return UNAVAILABLE;
        }
    }

    /**
     * Velocity 3.5.1 internals: PreLoginEvent carries {@code LoginInboundConnection}, whose
     * package-private {@code delegatedConnection()} returns the {@code MinecraftConnection}.
     *
     * @throws ReflectiveOperationException when Velocity/Netty internals no longer match
     */
    static boolean raknet(Object connection) throws ReflectiveOperationException {
        Object minecraft = call(connection, "delegatedConnection");
        Object pipeline = call(call(minecraft, "getChannel"), "pipeline");
        return call(pipeline, "get", RAKNET_HANDLER) != null;
    }

    private static Object call(Object target, String name, Object... args)
            throws ReflectiveOperationException {
        Class<?>[] types = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) types[i] = args[i].getClass();
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Method method = type.getDeclaredMethod(name, types);
                method.setAccessible(true);
                return method.invoke(target, args);
            } catch (NoSuchMethodException ignored) {
                // Netty declares pipeline()/get() on abstract/default superclasses.
            }
        }
        throw new NoSuchMethodException(target.getClass().getName() + "." + name);
    }
}

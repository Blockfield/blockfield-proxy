package io.blockfield.proxy;

import static io.blockfield.proxy.GateEvictionTest.fake;

import static org.junit.jupiter.api.Assertions.*;

import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.EventManager;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.ChannelRegistrar;
import com.velocitypowered.api.scheduler.Scheduler;

import net.kyori.adventure.text.Component;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

class BlockfieldProxyTest {
    @Test
    void lifecycleRegistersModulesCommandsChannelsAndTaskUnderThePluginOwner() {
        List<Object> listeners = new ArrayList<>();
        List<String> commands = new ArrayList<>();
        List<Object> owners = new ArrayList<>();
        var commandName = new AtomicReference<String>();
        var builder = new AtomicReference<CommandMeta.Builder>();
        builder.set(
                fake(
                        CommandMeta.Builder.class,
                        (method, args) -> {
                            if (method.equals("plugin")) owners.add(args[0]);
                            return method.equals("build")
                                    ? fake(CommandMeta.class, (m, a) -> null)
                                    : builder.get();
                        }));
        CommandManager manager =
                fake(
                        CommandManager.class,
                        (method, args) -> {
                            if (method.equals("metaBuilder")) {
                                commandName.set((String) args[0]);
                                return builder.get();
                            }
                            if (method.equals("register")) commands.add(commandName.get());
                            return null;
                        });
        EventManager events =
                fake(
                        EventManager.class,
                        (method, args) -> {
                            if (method.equals("register")) {
                                owners.add(args[0]);
                                listeners.add(args[1]);
                            }
                            return null;
                        });
        List<Object> channels = new ArrayList<>();
        ChannelRegistrar registrar =
                fake(
                        ChannelRegistrar.class,
                        (method, args) -> {
                            if (method.equals("register"))
                                channels.addAll(List.of((Object[]) args[0]));
                            return null;
                        });
        var task = new AtomicReference<Scheduler.TaskBuilder>();
        var tick = new AtomicReference<Runnable>();
        task.set(
                fake(
                        Scheduler.TaskBuilder.class,
                        (method, args) -> method.equals("repeat") ? task.get() : null));
        Scheduler scheduler =
                fake(
                        Scheduler.class,
                        (method, args) -> {
                            if (!method.equals("buildTask")) return null;
                            owners.add(args[0]);
                            tick.set((Runnable) args[1]);
                            return task.get();
                        });
        ProxyServer proxy =
                fake(
                        ProxyServer.class,
                        (method, args) ->
                                switch (method) {
                                    case "getEventManager" -> events;
                                    case "getCommandManager" -> manager;
                                    case "getChannelRegistrar" -> registrar;
                                    case "getScheduler" -> scheduler;
                                    default -> null;
                                });
        BlockfieldProxy plugin = new BlockfieldProxy(proxy, LoggerFactory.getLogger("test"));
        plugin.onInit(new ProxyInitializeEvent());
        assertEquals(
                List.of(Authentication.class, BackendRouting.class, LoginForwarding.class),
                listeners.stream().map(Object::getClass).toList());
        assertEquals(List.of("login", "register", "logout"), commands);
        assertEquals(List.of(LoginForwarding.TACZ_TWEAKS, Authentication.SESSION), channels);
        assertEquals(7, owners.size());
        assertTrue(owners.stream().allMatch(owner -> owner == plugin));
        assertNotNull(tick.get());
        tick.get().run();
    }

    @Test
    void onlyRestartsParkPlayers() {
        assertTrue(BackendRouting.parkable(Optional.empty()));
        assertTrue(
                BackendRouting.parkable(
                        Optional.of(
                                Component.translatable("multiplayer.disconnect.server_shutdown"))));
        assertTrue(
                BackendRouting.parkable(
                        Optional.of(
                                Component.translatable(
                                        "velocity.error.internal-server-connection-error"))));
        assertFalse(
                BackendRouting.parkable(Optional.of(Component.text("Требуется обновление пака."))));
        assertFalse(
                BackendRouting.parkable(
                        Optional.of(Component.translatable("multiplayer.disconnect.kicked"))));
    }

    @Test
    void serverIdIsEncodedLikeWriteUtf() {
        assertArrayEquals(new byte[] {2, 'a', 'b'}, Authentication.utf("ab"));
        assertEquals(41, Authentication.utf("0".repeat(40)).length);
        assertTrue(Authentication.NAME.matcher("Player_115").matches());
        assertFalse(Authentication.NAME.matcher("ab").matches());
    }
}

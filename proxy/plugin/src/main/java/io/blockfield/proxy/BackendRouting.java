package io.blockfield.proxy;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.format.NamedTextColor;

import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Gate admission, backend reconnects, and restart lobby behavior. */
final class BackendRouting {
    private final Object plugin;
    private final ProxyServer proxy;
    private final Logger logger;
    private final Authentication authentication;
    private static final Duration LOGIN_TIMEOUT = Duration.ofMinutes(5);
    // ponytail: участник доигрываемой Кампании = был на игровом сервере за 10 минут; точный резерв
    // проверяет мод. Передавать резервы в gate.json, если окна не хватит.
    private static final long GAME_MEMBER_MS = TimeUnit.MINUTES.toMillis(10);
    private static final String RESTARTING =
            "Игровой сервер перезапускается. Вернём вас автоматически, как только он поднимется.";

    private final Path gateFile =
            Optional.ofNullable(System.getenv("BLOCKFIELD_GATE_FILE"))
                    .filter(s -> !s.isBlank())
                    .map(Path::of)
                    .orElse(null);
    private volatile Gate gate = Gate.load(gateFile);
    private volatile FileTime gateStamp;

    /**
     * Gate whose evictions tick() last applied. A ping or connect may reload the file first; tick
     * must still evict once.
     */
    private Gate enforced = gate;

    private final Map<UUID, Long> lastOnGame = new ConcurrentHashMap<>();

    BackendRouting(Object plugin, ProxyServer proxy, Logger logger, Authentication authentication) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.logger = logger;
        this.authentication = authentication;
    }

    void admit(Player player) {
        target(player)
                .ifPresentOrElse(
                        backend ->
                                backend.ping()
                                        .thenRun(
                                                () ->
                                                        player.createConnectionRequest(backend)
                                                                .fireAndForget()),
                        () ->
                                player.sendMessage(
                                        Component.text(gate.message(), NamedTextColor.GOLD)));
    }

    private void reloadGate() {
        if (gateFile == null) return;
        FileTime stamp;
        try {
            stamp = Files.getLastModifiedTime(gateFile);
        } catch (java.io.IOException e) {
            stamp = null;
        }
        if (stamp != null && stamp.equals(gateStamp)) return;
        gateStamp = stamp;
        Gate next = Gate.load(gateFile);
        if (!next.equals(gate))
            logger.info(
                    "Gate {}:{} -> {}:{} ({} allowed)",
                    gate.state(),
                    gate.backend(),
                    next.state(),
                    next.backend(),
                    next.allowed().size());
        gate = next;
    }

    private boolean allowed(Player player, RegisteredServer server) {
        UUID id = player.getUniqueId();
        Long seen = lastOnGame.get(id);
        boolean member =
                player.getCurrentServer()
                                .map(c -> c.getServerInfo().getName().equals("game"))
                                .orElse(false)
                        || seen != null && System.currentTimeMillis() - seen <= GAME_MEMBER_MS;
        return authentication.authenticated(player)
                && gate.allows(
                        server.getServerInfo().getName(),
                        authentication.profileId(id),
                        player.getUsername(),
                        member);
    }

    /** Цель по gate, если игроку туда можно; иначе он ждёт в лобби. */
    private Optional<RegisteredServer> target(Player player) {
        return server(gate.backend()).filter(server -> allowed(player, server));
    }

    private String waitingText(Player player) {
        return target(player).isPresent() ? RESTARTING : gate.message();
    }

    @Subscribe
    public void onPing(ProxyPingEvent event) {
        reloadGate();
        if (!gate.open()) {
            event.setPing(
                    event.getPing()
                            .asBuilder()
                            .description(Component.text(gate.message(), NamedTextColor.GOLD))
                            .build());
        }
    }

    @Subscribe
    public void onChooseServer(PlayerChooseInitialServerEvent event) {
        reloadGate();
        boolean ok = authentication.authenticated(event.getPlayer());
        Optional<RegisteredServer> target = ok ? target(event.getPlayer()) : Optional.empty();
        (target.isPresent() ? target : server("lobby")).ifPresent(event::setInitialServer);
        if (!ok) {
            proxy.getScheduler()
                    .buildTask(
                            plugin,
                            () -> {
                                Player player = event.getPlayer();
                                if (player.isActive() && !authentication.authenticated(player)) {
                                    player.disconnect(Component.text("Время на вход истекло."));
                                }
                            })
                    .delay(LOGIN_TIMEOUT)
                    .schedule();
        }
    }

    @Subscribe
    public void onPreConnect(ServerPreConnectEvent event) {
        if (isLobby(event.getOriginalServer())) return;
        reloadGate();
        Player player = event.getPlayer();
        if (!authentication.authenticated(player)) {
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            if (event.getPreviousServer() != null) {
                player.sendMessage(
                        Component.text(
                                "Сначала войдите: /login или /register.", NamedTextColor.RED));
            }
        } else if (!allowed(player, event.getOriginalServer())) {
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            if (event.getPreviousServer() != null)
                player.sendMessage(Component.text(gate.message(), NamedTextColor.GOLD));
        }
    }

    @Subscribe
    public void onPostConnect(ServerPostConnectEvent event) {
        Player player = event.getPlayer();
        RegisteredServer current = player.getCurrentServer().map(c -> c.getServer()).orElse(null);
        if (current == null) return;
        if (!isLobby(current)) return;
        if (authentication.authenticated(player)) {
            // После кика с игрового сервера этот текст уже пришёл в RedirectPlayer.
            if (event.getPreviousServer() != null) return;
            player.sendMessage(Component.text(waitingText(player), NamedTextColor.GOLD));
        } else {
            player.sendMessage(
                    Component.text("Войдите в аккаунт Blockfield:\n", NamedTextColor.GOLD)
                            .append(Component.text("/login <пароль>", NamedTextColor.YELLOW))
                            .append(
                                    Component.text(
                                            " — если аккаунт уже есть\n", NamedTextColor.GRAY))
                            .append(
                                    Component.text(
                                            "/register <пароль> <пароль>", NamedTextColor.YELLOW))
                            .append(
                                    Component.text(
                                            " — новый аккаунт для ника " + player.getUsername(),
                                            NamedTextColor.GRAY)));
        }
    }

    @Subscribe
    public void onKicked(KickedFromServerEvent event) {
        if (isLobby(event.getServer())) return;
        Optional<Component> reason = event.getServerKickReason();
        Optional<RegisteredServer> lobby = server("lobby");
        if (lobby.isEmpty() || !parkable(reason)) {
            event.setResult(
                    KickedFromServerEvent.DisconnectPlayer.create(
                            reason.orElse(Component.text("Игровой сервер недоступен."))));
        } else if (event.getPlayer()
                .getCurrentServer()
                .map(c -> isLobby(c.getServer()))
                .orElse(false)) {
            event.setResult(
                    KickedFromServerEvent.Notify.create(
                            target(event.getPlayer()).isPresent()
                                    ? Component.text(
                                            "Игровой сервер ещё запускается…", NamedTextColor.GRAY)
                                    : Component.text(gate.message(), NamedTextColor.GOLD)));
        } else {
            // Без своего текста Velocity показывает английское «You were kicked from game».
            event.setResult(
                    KickedFromServerEvent.RedirectPlayer.create(
                            lobby.get(),
                            Component.text(waitingText(event.getPlayer()), NamedTextColor.GOLD)));
        }
    }

    /**
     * Нет причины (отказ в соединении), обрыв соединения (Velocity подставляет свою причину) или
     * штатная остановка — это рестарт, а не кик за дело.
     */
    static boolean parkable(Optional<Component> reason) {
        return reason.isEmpty()
                || reason.get() instanceof TranslatableComponent t
                        && (t.key().equals("multiplayer.disconnect.server_shutdown")
                                || t.key()
                                        .equals("velocity.error.internal-server-connection-error"));
    }

    void tick() {
        long now = System.currentTimeMillis();
        server("game")
                .ifPresent(
                        game ->
                                game.getPlayersConnected()
                                        .forEach(p -> lastOnGame.put(p.getUniqueId(), now)));
        lastOnGame.values().removeIf(seen -> now - seen > GAME_MEMBER_MS);
        reloadGate();
        Optional<RegisteredServer> lobby = server("lobby");
        if (lobby.isEmpty()) return;
        if (!gate.equals(enforced)) {
            enforced = gate;
            // Отзыв доступа действует сразу: всех, кому новый gate не разрешает текущий backend, —
            // в лобби.
            for (Player player : proxy.getAllPlayers()) {
                player.getCurrentServer()
                        .filter(c -> !isLobby(c.getServer()) && !allowed(player, c.getServer()))
                        .ifPresent(
                                c -> {
                                    player.sendMessage(
                                            Component.text(
                                                    gate.open()
                                                            ? "Мастерская закрыта."
                                                            : gate.message(),
                                                    NamedTextColor.GOLD));
                                    player.createConnectionRequest(lobby.get()).fireAndForget();
                                });
            }
        }
        Optional<RegisteredServer> backend = server(gate.backend());
        if (backend.isEmpty()) return;
        var waiting =
                lobby.get().getPlayersConnected().stream()
                        .filter(p -> allowed(p, backend.get()))
                        .toList();
        if (waiting.isEmpty()) return;
        backend.get()
                .ping()
                .thenRun(
                        () ->
                                waiting.forEach(
                                        p ->
                                                p.createConnectionRequest(backend.get())
                                                        .fireAndForget()));
    }

    private Optional<RegisteredServer> server(String name) {
        return proxy.getServer(name);
    }

    private static boolean isLobby(RegisteredServer server) {
        return server.getServerInfo().getName().equals("lobby");
    }
}

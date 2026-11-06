package io.blockfield.proxy;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.proxy.InboundConnection;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.format.NamedTextColor;

import org.slf4j.Logger;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
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
    private final String workshopHost;

    /** Inbound RakNet UDP port of the Workshop profile; -1 disables port routing. */
    private final int workshopPort;

    /** Default Workshop port; bound by raknetify via start-proxy.sh defaults. */
    static final int DEFAULT_WORKSHOP_PORT = 25567;

    private static final Duration LOGIN_TIMEOUT = Duration.ofMinutes(5);
    // ponytail: участник доигрываемой Кампании = был на игровом сервере за 10 минут; точный резерв
    // проверяет мод. Передавать резервы в gate.json, если окна не хватит.
    private static final long GAME_MEMBER_MS = TimeUnit.MINUTES.toMillis(10);
    private static final String RESTARTING =
            "Игровой сервер перезапускается. Вернём вас автоматически, как только он поднимется.";
    static final String WORKSHOP_WAKING =
            "Мастерская просыпается, это займёт 1–2 минуты. Переподключитесь позже.";
    static final String DEPLOYING_GAME =
            "Игровой сервер обновляется, это займёт несколько минут. Переподключитесь позже.";
    private static final long WAKE_INTERVAL_MS = TimeUnit.MINUTES.toMillis(1);

    private final Path gateFile =
            Optional.ofNullable(System.getenv("BLOCKFIELD_GATE_FILE"))
                    .filter(s -> !s.isBlank())
                    .map(Path::of)
                    .orElse(null);
    private volatile Gate fileGate = Gate.load(gateFile);
    private volatile Gate gate = fileGate;
    private volatile FileTime gateStamp;
    // U1: с BLOCKFIELD_CP_URL вход читается из control-plane (файл — запасной путь лишь без CP).
    // Никогда не null из-за битого окружения: fromEnv не бросает, а битый хост мастерской
    // лишь отключает мастерскую (как пустой), а не роняет весь плагин.
    private GatePoller gatePoller;
    private volatile ControlSubscriber controlPlane;
    private volatile boolean controlProbed;

    /**
     * Gate whose evictions tick() last applied. A ping or connect may reload the file first; tick
     * must still evict once.
     */
    private Gate enforced = gate;

    private final Map<UUID, Long> lastOnGame = new ConcurrentHashMap<>();
    private final Path inboxDir;

    /** Last wake request, epoch ms; at most one inbox file per minute per proxy. */
    private long lastWakeMs;

    BackendRouting(Object plugin, ProxyServer proxy, Logger logger, Authentication authentication) {
        this(plugin, proxy, logger, authentication, System.getenv("BLOCKFIELD_WORKSHOP_HOST"));
    }

    BackendRouting(
            Object plugin,
            ProxyServer proxy,
            Logger logger,
            Authentication authentication,
            String workshopHost) {
        this(plugin, proxy, logger, authentication, workshopHost, inboxDirFromEnv());
    }

    BackendRouting(
            Object plugin,
            ProxyServer proxy,
            Logger logger,
            Authentication authentication,
            String workshopHost,
            Path inboxDir) {
        this(plugin, proxy, logger, authentication, workshopHost, workshopPortFromEnv(), inboxDir);
    }

    BackendRouting(
            Object plugin,
            ProxyServer proxy,
            Logger logger,
            Authentication authentication,
            String workshopHost,
            int workshopPort,
            Path inboxDir) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.logger = logger;
        this.authentication = authentication;
        String host = workshopHost == null ? "" : workshopHost.strip();
        if (!host.isEmpty() && !validWorkshopHost(host)) {
            // Fail closed, never throw: опечатка не должна ронять плагин (и вместе с ним
            // gate-хуки, после чего /server пускает всех без авторизации). Мастерская
            // отключается, как при пустом значении.
            logger.error(
                    "BLOCKFIELD_WORKSHOP_HOST is not a plain hostname or IP ({});"
                            + " Workshop entry is disabled",
                    host);
            host = "";
        }
        this.workshopHost = normalizeHost(host);
        if (workshopPort != -1 && (workshopPort <= 0 || workshopPort >= 65535)) {
            // Fail closed, never throw: an invalid value disables Workshop routing, so every
            // login falls through to Game instead of failing open to Workshop.
            logger.error(
                    "BLOCKFIELD_RAKNET_WORKSHOP_PORT is not a valid port ({}); Workshop entry is"
                            + " disabled",
                    workshopPort);
            workshopPort = -1;
        }
        this.workshopPort = workshopPort;
        this.inboxDir = inboxDir;
        this.gatePoller = GatePoller.fromEnv(logger);
        String source =
                gateSource(
                        System.getenv("BLOCKFIELD_CP_URL"),
                        System.getenv("BLOCKFIELD_CP_TOKEN"),
                        gateFile);
        if (source.startsWith("file (") || source.startsWith("control-plane URL invalid"))
            logger.warn("gate source: {}", source);
        else logger.info("gate source: {}", source);
    }

    /** Plain hostname or IP, without a port: the constructor never throws, this decides. */
    static boolean validWorkshopHost(String host) {
        try {
            return new URI("minecraft", null, host, -1, null, null, null).getHost() != null;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /**
     * Active gate source for the startup log; pure for tests. A half-configured CP (URL without
     * token or vice versa) silently falls back to the file: the log names it so a maintenance
     * toggle that "doesn't take" points at the env, not at the gate.
     */
    static String gateSource(String cpUrl, String cpToken, Path gateFile) {
        boolean hasUrl = cpUrl != null && !cpUrl.isBlank();
        boolean hasToken = cpToken != null && !cpToken.isBlank();
        if (hasUrl && hasToken) {
            try {
                GatePoller.gateUri(cpUrl);
                return "control-plane";
            } catch (IllegalArgumentException e) {
                return "control-plane URL invalid, entry stays closed: " + e.getMessage();
            }
        }
        if (hasUrl || hasToken)
            return "file (BLOCKFIELD_CP_URL/TOKEN half-configured, control-plane disabled)";
        return "file (" + (gateFile == null ? "bundled default" : gateFile.toString()) + ")";
    }

    private static Path inboxDirFromEnv() {
        String dir = System.getenv("BLOCKFIELD_WORKSHOP_INBOX_DIR");
        return dir == null || dir.isBlank() ? null : Path.of(dir);
    }

    private static int workshopPortFromEnv() {
        String raw = System.getenv("BLOCKFIELD_RAKNET_WORKSHOP_PORT");
        if (raw == null || raw.isBlank()) return DEFAULT_WORKSHOP_PORT;
        try {
            return Integer.parseInt(raw.strip());
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    static String normalizeHost(String value) {
        String host = value.strip().toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        // Only numeric IPv6 literals reach InetAddress: routing never performs a DNS lookup.
        if (host.contains(":") && host.matches("[0-9a-f:.]+")) {
            try {
                return InetAddress.getByName(host).getHostAddress();
            } catch (UnknownHostException ignored) {
                // A malformed handshake host cannot match the validated workshop host.
            }
        }
        return host;
    }

    /**
     * Deploy in progress reads as such instead of "server dead". An explicit operator message still
     * wins; admission and limbo/lobby fallback are unchanged.
     */
    static String displayText(Gate selected, String backend, String blankText) {
        if (backend.equals("game")
                && selected.deploying()
                && !selected.open()
                && (selected.message().isBlank()
                        || selected.message().equals(Gate.DEFAULT_MESSAGE))) return DEPLOYING_GAME;
        return selected.message().isBlank() ? blankText : selected.message();
    }

    private String requestedBackend(InboundConnection connection) {
        return !workshopHost.isEmpty()
                        && connection
                                .getVirtualHost()
                                .map(address -> normalizeHost(address.getHostString()))
                                .filter(workshopHost::equals)
                                .isPresent()
                ? "workshop"
                : "game";
    }

    /**
     * Login profile. 1.21.1 clients handshake with the resolved address rDNS/IP, never the typed
     * hostname, so the vhost host cannot select the profile: the inbound RakNet port does (Workshop
     * port → Workshop, any other RakNet port → Game). A login with no recorded port falls back to
     * the legacy vhost rule.
     */
    private String requestedBackend(Player player) {
        Integer port = authentication.inboundPort(player.getUsername());
        if (port != null) return port == workshopPort ? "workshop" : "game";
        return requestedBackend((InboundConnection) player);
    }

    void admit(Player player) {
        reloadGate();
        target(player)
                .ifPresentOrElse(
                        backend ->
                                backend.ping()
                                        .thenRun(
                                                () -> {
                                                    if (allowed(player, backend))
                                                        player.createConnectionRequest(backend)
                                                                .fireAndForget();
                                                }),
                        () -> {
                            maybeRequestWake(player);
                            player.sendMessage(
                                    Component.text(waitingText(player), NamedTextColor.GOLD));
                        });
    }

    private void reloadGate() {
        boolean fileChanged = reloadGateFile();
        if (gatePoller == null && !fileChanged) return;
        // CP owns game entry only; the Workshop keeps the controller's gate.json rules.
        Gate next = mergeGate();
        if (!next.equals(gate))
            logger.info(
                    "Gate game={} workshop={} ({} workshop members)",
                    next.forBackend("game").state(),
                    next.forBackend("workshop").state(),
                    next.forBackend("workshop").allowed().size());
        gate = next;
    }

    /**
     * v2 push consumer (CP-S5). Lazy so the constructor chain stays untouched; separate from the U1
     * poller. Null means v2 is off (the U1 poll/file path applies); non-null but closed means
     * half-configured, and then the game leg stays closed instead of falling back to the file.
     */
    private ControlSubscriber controlPlane() {
        if (!controlProbed) {
            controlProbed = true;
            controlPlane = ControlSubscriber.fromEnv(logger);
        }
        return controlPlane;
    }

    /** Game leg source: v2 push wins when configured, otherwise the U1 poll/file path. */
    private Gate mergeGate() {
        ControlSubscriber v2 = controlPlane();
        if (v2 != null) return fileGate.withGame(v2.current());
        return gatePoller == null ? fileGate : fileGate.withGame(gatePoller.current());
    }

    private boolean reloadGateFile() {
        if (gateFile == null) return false;
        FileTime stamp;
        try {
            stamp = Files.getLastModifiedTime(gateFile);
        } catch (java.io.IOException e) {
            stamp = null;
        }
        if (stamp != null && stamp.equals(gateStamp)) return false;
        gateStamp = stamp;
        fileGate = Gate.load(gateFile);
        return true;
    }

    /**
     * Cold Workshop wake-on-connect: a sleeping workshop keeps members in the lobby and takes one
     * inbox wake request per minute per proxy. No inbox mount means message only; STARTING means
     * the request is already in flight, so only sleeping writes.
     */
    void maybeRequestWake(Player player) {
        Path dir = inboxDir;
        if (dir == null || !authentication.authenticated(player)) return;
        if (!requestedBackend(player).equals("workshop")) return;
        Gate workshop = gate.forBackend("workshop");
        if (!workshop.sleeping()) return;
        String draslId = authentication.profileId(player.getUniqueId());
        String name = player.getUsername();
        if (!workshop.member(draslId, name)) return;
        long now = System.currentTimeMillis();
        synchronized (this) {
            if (now - lastWakeMs < WAKE_INTERVAL_MS) return;
            lastWakeMs = now;
        }
        // Off the event thread: the scheduler runs this async, a tiny write is all it does.
        proxy.getScheduler().buildTask(plugin, () -> writeWake(dir, draslId, name)).schedule();
    }

    private void writeWake(Path dir, String draslId, String name) {
        try {
            writeWakeRequest(dir, draslId, name, System.currentTimeMillis());
        } catch (IOException | RuntimeException e) {
            logger.warn("Workshop wake request failed", e);
        }
    }

    /** Controller inbox envelope, exactly as other kinds; file name matches id; no secrets. */
    static void writeWakeRequest(Path dir, String draslId, String name, long nowMs)
            throws IOException {
        String id = UUID.randomUUID().toString();
        // draslId is hex, name matches Authentication.NAME, id is a UUID: no escaping needed.
        String json =
                "{\"schema\":1,\"id\":\""
                        + id
                        + "\",\"kind\":\"wake\",\"actor\":{\"draslId\":\""
                        + draslId
                        + "\",\"name\":\""
                        + name
                        + "\"},\"createdAt\":"
                        + nowMs
                        + "}";
        Path tmp = dir.resolve("." + id + ".tmp");
        Path dst = dir.resolve(id + ".json");
        Files.writeString(
                tmp,
                json,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
        try {
            Files.move(tmp, dst, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, dst);
        }
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
                && server.getServerInfo().getName().equals(requestedBackend(player))
                && gate.allows(
                        server.getServerInfo().getName(),
                        authentication.profileId(id),
                        player.getUsername(),
                        member);
    }

    /** The address selects a profile; gate and account roles still control admission. */
    private Optional<RegisteredServer> target(Player player) {
        return server(requestedBackend(player)).filter(server -> allowed(player, server));
    }

    private String waitingText(Player player) {
        String backend = requestedBackend(player);
        if (target(player).isPresent())
            return backend.equals("workshop")
                    ? "Мастерская запускается. Вернём вас автоматически, когда она поднимется."
                    : RESTARTING;
        Gate selected = gate.forBackend(backend);
        if (backend.equals("workshop")
                && selected.waking()
                && selected.member(
                        authentication.profileId(player.getUniqueId()), player.getUsername()))
            return WORKSHOP_WAKING;
        return displayText(
                selected,
                backend,
                backend.equals("workshop")
                        ? "Мастерская сейчас недоступна."
                        : "Игровой сервер недоступен.");
    }

    @Subscribe
    public void onPing(ProxyPingEvent event) {
        reloadGate();
        String backend = requestedBackend(event.getConnection());
        Gate selected = gate.forBackend(backend);
        if (!selected.open() || backend.equals("workshop")) {
            event.setPing(
                    event.getPing()
                            .asBuilder()
                            .description(
                                    Component.text(
                                            displayText(selected, backend, "Мастерская Blockfield"),
                                            NamedTextColor.GOLD))
                            .build());
        }
    }

    @Subscribe
    public void onChooseServer(PlayerChooseInitialServerEvent event) {
        reloadGate();
        Player player = event.getPlayer();
        boolean ok = authentication.authenticated(player);
        Optional<RegisteredServer> target = ok ? target(player) : Optional.empty();
        logger.info(
                "entry {} profile={} port={} vhost={}",
                player.getUsername(),
                requestedBackend(player),
                inboundPort(player),
                player.getVirtualHost()
                        .map(address -> address.getHostString() + ":" + address.getPort())
                        .orElse("-"));
        if (ok) maybeRequestWake(player);
        (target.isPresent() ? target : server("lobby")).ifPresent(event::setInitialServer);
        if (!ok) {
            proxy.getScheduler()
                    .buildTask(
                            plugin,
                            () -> {
                                Player pending = event.getPlayer();
                                if (pending.isActive() && !authentication.authenticated(pending)) {
                                    pending.disconnect(Component.text("Время на вход истекло."));
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
            if (event.getOriginalServer().getServerInfo().getName().equals("workshop"))
                maybeRequestWake(player);
            if (event.getPreviousServer() != null) {
                String text =
                        event.getOriginalServer()
                                        .getServerInfo()
                                        .getName()
                                        .equals(requestedBackend(player))
                                ? waitingText(player)
                                : requestedBackend(player).equals("workshop")
                                        ? "Профиль мастерской подключается только к мастерской."
                                        : "Игровой профиль подключается только к игровому серверу.";
                player.sendMessage(Component.text(text, NamedTextColor.GOLD));
            }
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
                            Component.text(waitingText(event.getPlayer()), NamedTextColor.GOLD)));
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
                                                    waitingText(player), NamedTextColor.GOLD));
                                    player.createConnectionRequest(lobby.get()).fireAndForget();
                                });
            }
        }
        if (gate.forBackend("workshop").sleeping()) {
            // Wake on connect: members parked in the lobby trigger one inbox request.
            for (Player parked : lobby.get().getPlayersConnected()) maybeRequestWake(parked);
        }
        for (String name : List.of("game", "workshop")) {
            Optional<RegisteredServer> backend = server(name);
            if (backend.isEmpty()) continue;
            var waiting =
                    lobby.get().getPlayersConnected().stream()
                            .filter(p -> allowed(p, backend.get()))
                            .toList();
            if (waiting.isEmpty()) continue;
            backend.get()
                    .ping()
                    .thenRun(
                            () ->
                                    waiting.stream()
                                            .filter(p -> allowed(p, backend.get()))
                                            .forEach(
                                                    p ->
                                                            p.createConnectionRequest(backend.get())
                                                                    .fireAndForget()));
        }
    }

    private String inboundPort(Player player) {
        Integer port = authentication.inboundPort(player.getUsername());
        return port == null ? "unknown" : String.valueOf(port);
    }

    private Optional<RegisteredServer> server(String name) {
        return proxy.getServer(name);
    }

    private static boolean isLobby(RegisteredServer server) {
        return server.getServerInfo().getName().equals("lobby");
    }
}

package io.blockfield.proxy;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.inject.Inject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerLoginPluginMessageEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.LoginPhaseConnection;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Вход через Drasl и парковка в лобби. Сервер остаётся offline-mode: личность подтверждает либо
 * клиентский мод (Drasl join/hasJoined с одноразовым serverId), либо /login и /register в лобби.
 * Пока игровой сервер недоступен, вошедшие ждут в лобби и возвращаются автоматически.
 */
@Plugin(id = "blockfield-proxy", name = "Blockfield Proxy", version = "1.0.0")
public final class BlockfieldProxy {
    static final MinecraftChannelIdentifier AUTH = MinecraftChannelIdentifier.create("blockfield", "auth");
    static final MinecraftChannelIdentifier PACK = MinecraftChannelIdentifier.create("blockfield", "pack_version");
    /** play-пакет клиенту: writeUtf(jwt) + writeUtf(ключ); пустые строки — забыть вход. */
    static final MinecraftChannelIdentifier SESSION = MinecraftChannelIdentifier.create("blockfield", "session");
    // TaCZ Tweaks шлёт конфиг сервера login-запросом и кикает за «не понят»; после входа клиента
    // login-фаза уже прошла, поэтому отвечаем за него и досылаем тот же конфиг play-пакетом.
    static final MinecraftChannelIdentifier TACZ_TWEAKS = MinecraftChannelIdentifier.create("tacztweaks", "server_sync_config");
    static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");
    private static final Duration LOGIN_TIMEOUT = Duration.ofMinutes(5);
    private static final int MAX_FAILURES = 5;
    private static final long IP_WINDOW_MS = TimeUnit.MINUTES.toMillis(10);
    private static final int IP_MAX_FAILURES = 15;
    // ponytail: участник доигрываемой Кампании = был на игровом сервере за 10 минут; точный резерв проверяет мод. Передавать резервы в gate.json, если окна не хватит.
    private static final long GAME_MEMBER_MS = TimeUnit.MINUTES.toMillis(10);
    private static final String RESTARTING = "Игровой сервер перезапускается. Вернём вас автоматически, как только он поднимется.";

    private final ProxyServer proxy;
    private final Logger logger;
    private final String drasl = System.getenv().getOrDefault("BLOCKFIELD_DRASL_URL", "https://skins.blockfield.pro");
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final SecureRandom random = new SecureRandom();
    // Секрет сессий выводится из секрета форвардинга: его смена разлогинивает всех, отдельной переменной не нужно.
    private final byte[] sessionSecret = Optional.ofNullable(System.getenv("VELOCITY_FORWARDING_SECRET"))
            .filter(s -> !s.isBlank()).map(Sessions::secret).orElse(null);

    private final Path gateFile = Optional.ofNullable(System.getenv("BLOCKFIELD_GATE_FILE"))
            .filter(s -> !s.isBlank()).map(Path::of).orElse(null);
    private volatile Gate gate = Gate.load(gateFile);
    private volatile FileTime gateStamp;
    /** Gate whose evictions tick() last applied. A ping or connect may reload the file first; tick must still evict once. */
    private Gate enforced = gate;

    private record Pending(long createdAt, CompletableFuture<Boolean> auth, CompletableFuture<byte[]> pack, AtomicReference<String> drasl) {}

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final Set<UUID> authed = ConcurrentHashMap.newKeySet();
    /** Drasl profile id подтверждённого аккаунта: ACL мастерской сверяет id и ник вместе. */
    private final Map<UUID, String> draslIds = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastOnGame = new ConcurrentHashMap<>();
    private final Map<UUID, byte[]> packReplies = new ConcurrentHashMap<>();
    private final Map<UUID, byte[]> taczConfig = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> failures = new ConcurrentHashMap<>();
    private final Map<InetAddress, long[]> ipFailures = new ConcurrentHashMap<>();

    @Inject
    public BlockfieldProxy(ProxyServer proxy, Logger logger) {
        this.proxy = proxy;
        this.logger = logger;
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent event) {
        var commands = proxy.getCommandManager();
        commands.register(commands.metaBuilder("login").aliases("l").plugin(this).build(), new AuthCommand(false));
        commands.register(commands.metaBuilder("register").aliases("reg").plugin(this).build(), new AuthCommand(true));
        commands.register(commands.metaBuilder("logout").plugin(this).build(), new LogoutCommand());
        proxy.getChannelRegistrar().register(TACZ_TWEAKS, SESSION);
        proxy.getScheduler().buildTask(this, this::tick).repeat(3, TimeUnit.SECONDS).schedule();
    }

    @Subscribe
    public void onPreLogin(PreLoginEvent event) {
        if (!NAME.matcher(event.getUsername()).matches()) {
            event.setResult(PreLoginEvent.PreLoginComponentResult.denied(
                    Component.text("Ник: 3–16 символов, латиница, цифры и _")));
            return;
        }
        if (!(event.getConnection() instanceof LoginPhaseConnection connection)) return;
        var auth = new CompletableFuture<Boolean>();
        var pack = new CompletableFuture<byte[]>();
        var drasl = new AtomicReference<String>();
        pending.put(key(event.getConnection().getRemoteAddress(), event.getUsername()),
                new Pending(System.currentTimeMillis(), auth, pack, drasl));
        connection.sendLoginPluginMessage(PACK, new byte[0], pack::complete);
        String serverId = HexFormat.of().formatHex(bytes(20));
        connection.sendLoginPluginMessage(AUTH, utf(serverId), response -> {
            if (response != null && response.length == 1 && response[0] == 1) {
                hasJoined(event.getUsername(), serverId).whenComplete((id, error) -> {
                    if (error == null && id != null) drasl.set(id);
                    auth.complete(error == null && id != null);
                });
            } else if (response != null && response.length > 1 && sessionSecret != null) {
                auth.complete(Sessions.verify(sessionSecret, event.getUsername(), serverId, response, System.currentTimeMillis() / 1000));
            } else {
                auth.complete(false);
            }
        });
    }

    @Subscribe
    public EventTask onLogin(LoginEvent event) {
        Player player = event.getPlayer();
        Pending entry = pending.remove(key(player.getRemoteAddress(), player.getUsername()));
        if (entry == null) return null;
        UUID id = player.getUniqueId();
        return EventTask.resumeWhenComplete(CompletableFuture.allOf(
                entry.pack().completeOnTimeout(null, 10, TimeUnit.SECONDS).thenAccept(reply -> {
                    if (reply != null) packReplies.put(id, reply);
                }),
                entry.auth().completeOnTimeout(false, 15, TimeUnit.SECONDS).thenCompose(ok -> {
                    if (!ok) return CompletableFuture.completedFuture(null);
                    authed.add(id);
                    return resolveDrasl(id, player.getUsername(), entry.drasl().get());
                })));
    }

    /** Запомненный вход (JWT) несёт только ник: id берём у Drasl по уже подтверждённому нику; сбой — без доступа в мастерскую. */
    private CompletableFuture<Void> resolveDrasl(UUID player, String name, String known) {
        CompletableFuture<String> id = known != null ? CompletableFuture.completedFuture(known)
                : profileId(name).exceptionally(error -> null).completeOnTimeout(null, 5, TimeUnit.SECONDS);
        return id.thenAccept(value -> {
            if (value != null) draslIds.put(player, value);
        });
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
        if (!next.equals(gate)) logger.info("Gate {}:{} -> {}:{} ({} allowed)", gate.state(), gate.backend(), next.state(), next.backend(), next.allowed().size());
        gate = next;
    }

    private boolean allowed(Player player, RegisteredServer server) {
        UUID id = player.getUniqueId();
        Long seen = lastOnGame.get(id);
        boolean member = player.getCurrentServer().map(c -> c.getServerInfo().getName().equals("game")).orElse(false)
                || seen != null && System.currentTimeMillis() - seen <= GAME_MEMBER_MS;
        return authed.contains(id) && gate.allows(server.getServerInfo().getName(), draslIds.get(id), player.getUsername(), member);
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
            event.setPing(event.getPing().asBuilder().description(Component.text(gate.message(), NamedTextColor.GOLD)).build());
        }
    }

    @Subscribe
    public void onChooseServer(PlayerChooseInitialServerEvent event) {
        reloadGate();
        boolean ok = authed.contains(event.getPlayer().getUniqueId());
        Optional<RegisteredServer> target = ok ? target(event.getPlayer()) : Optional.empty();
        (target.isPresent() ? target : server("lobby")).ifPresent(event::setInitialServer);
        if (!ok) {
            proxy.getScheduler().buildTask(this, () -> {
                Player player = event.getPlayer();
                if (player.isActive() && !authed.contains(player.getUniqueId())) {
                    player.disconnect(Component.text("Время на вход истекло."));
                }
            }).delay(LOGIN_TIMEOUT).schedule();
        }
    }

    @Subscribe
    public void onPreConnect(ServerPreConnectEvent event) {
        if (isLobby(event.getOriginalServer())) return;
        reloadGate();
        Player player = event.getPlayer();
        if (!authed.contains(player.getUniqueId())) {
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            if (event.getPreviousServer() != null) {
                player.sendMessage(Component.text("Сначала войдите: /login или /register.", NamedTextColor.RED));
            }
        } else if (!allowed(player, event.getOriginalServer())) {
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            if (event.getPreviousServer() != null) player.sendMessage(Component.text(gate.message(), NamedTextColor.GOLD));
        }
    }

    @Subscribe
    public void onBackendLoginMessage(ServerLoginPluginMessageEvent event) {
        UUID id = event.getConnection().getPlayer().getUniqueId();
        if (event.getIdentifier().getId().equals(TACZ_TWEAKS.getId())) {
            taczConfig.put(id, event.getContents());
            event.setResult(ServerLoginPluginMessageEvent.ResponseResult.reply(new byte[0]));
        } else if (event.getIdentifier().getId().equals(PACK.getId())) {
            byte[] reply = packReplies.get(id);
            // Нет ответа от клиента (ванильный или устаревший пак): сервер сам кикнет с понятным текстом.
            event.setResult(reply == null ? ServerLoginPluginMessageEvent.ResponseResult.unknown()
                    : ServerLoginPluginMessageEvent.ResponseResult.reply(reply));
        }
    }

    @Subscribe
    public void onPostConnect(ServerPostConnectEvent event) {
        Player player = event.getPlayer();
        RegisteredServer current = player.getCurrentServer().map(c -> c.getServer()).orElse(null);
        if (current == null) return;
        if (!isLobby(current)) {
            byte[] config = taczConfig.remove(player.getUniqueId());
            if (config != null) player.sendPluginMessage(TACZ_TWEAKS, config);
            return;
        }
        if (authed.contains(player.getUniqueId())) {
            // После кика с игрового сервера этот текст уже пришёл в RedirectPlayer.
            if (event.getPreviousServer() != null) return;
            player.sendMessage(Component.text(waitingText(player), NamedTextColor.GOLD));
        } else {
            player.sendMessage(Component.text("Войдите в аккаунт Blockfield:\n", NamedTextColor.GOLD)
                    .append(Component.text("/login <пароль>", NamedTextColor.YELLOW))
                    .append(Component.text(" — если аккаунт уже есть\n", NamedTextColor.GRAY))
                    .append(Component.text("/register <пароль> <пароль>", NamedTextColor.YELLOW))
                    .append(Component.text(" — новый аккаунт для ника " + player.getUsername(), NamedTextColor.GRAY)));
        }
    }

    @Subscribe
    public void onKicked(KickedFromServerEvent event) {
        if (isLobby(event.getServer())) return;
        Optional<Component> reason = event.getServerKickReason();
        Optional<RegisteredServer> lobby = server("lobby");
        if (lobby.isEmpty() || !parkable(reason)) {
            event.setResult(KickedFromServerEvent.DisconnectPlayer.create(
                    reason.orElse(Component.text("Игровой сервер недоступен."))));
        } else if (event.getPlayer().getCurrentServer().map(c -> isLobby(c.getServer())).orElse(false)) {
            event.setResult(KickedFromServerEvent.Notify.create(target(event.getPlayer()).isPresent()
                    ? Component.text("Игровой сервер ещё запускается…", NamedTextColor.GRAY)
                    : Component.text(gate.message(), NamedTextColor.GOLD)));
        } else {
            // Без своего текста Velocity показывает английское «You were kicked from game».
            event.setResult(KickedFromServerEvent.RedirectPlayer.create(lobby.get(),
                    Component.text(waitingText(event.getPlayer()), NamedTextColor.GOLD)));
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        authed.remove(id);
        draslIds.remove(id);
        packReplies.remove(id);
        taczConfig.remove(id);
        failures.remove(id);
    }

    /** Нет причины (отказ в соединении), обрыв соединения (Velocity подставляет свою причину) или штатная остановка — это рестарт, а не кик за дело. */
    static boolean parkable(Optional<Component> reason) {
        return reason.isEmpty() || reason.get() instanceof TranslatableComponent t
                && (t.key().equals("multiplayer.disconnect.server_shutdown") || t.key().equals("velocity.error.internal-server-connection-error"));
    }

    private void tick() {
        long now = System.currentTimeMillis();
        pending.values().removeIf(p -> now - p.createdAt() > 60_000);
        ipFailures.values().removeIf(w -> now - w[1] > IP_WINDOW_MS);
        server("game").ifPresent(game -> game.getPlayersConnected().forEach(p -> lastOnGame.put(p.getUniqueId(), now)));
        lastOnGame.values().removeIf(seen -> now - seen > GAME_MEMBER_MS);
        reloadGate();
        Optional<RegisteredServer> lobby = server("lobby");
        if (lobby.isEmpty()) return;
        if (!gate.equals(enforced)) {
            enforced = gate;
            // Отзыв доступа действует сразу: всех, кому новый gate не разрешает текущий backend, — в лобби.
            for (Player player : proxy.getAllPlayers()) {
                player.getCurrentServer().filter(c -> !isLobby(c.getServer()) && !allowed(player, c.getServer())).ifPresent(c -> {
                    player.sendMessage(Component.text(gate.open() ? "Мастерская закрыта." : gate.message(), NamedTextColor.GOLD));
                    player.createConnectionRequest(lobby.get()).fireAndForget();
                });
            }
        }
        Optional<RegisteredServer> backend = server(gate.backend());
        if (backend.isEmpty()) return;
        var waiting = lobby.get().getPlayersConnected().stream().filter(p -> allowed(p, backend.get())).toList();
        if (waiting.isEmpty()) return;
        backend.get().ping().thenRun(() -> waiting.forEach(p -> p.createConnectionRequest(backend.get()).fireAndForget()));
    }

    private final class AuthCommand implements SimpleCommand {
        private final boolean register;

        AuthCommand(boolean register) {
            this.register = register;
        }

        @Override
        public boolean hasPermission(Invocation invocation) {
            return invocation.source() instanceof Player player && !authed.contains(player.getUniqueId());
        }

        @Override
        public void execute(Invocation invocation) {
            if (!(invocation.source() instanceof Player player)) return;
            String[] args = invocation.arguments();
            if (register ? args.length != 2 : args.length != 1) {
                player.sendMessage(Component.text(register ? "/register <пароль> <пароль>" : "/login <пароль>", NamedTextColor.RED));
                return;
            }
            if (register && !args[0].equals(args[1])) {
                player.sendMessage(Component.text("Пароли не совпадают.", NamedTextColor.RED));
                return;
            }
            InetAddress ip = player.getRemoteAddress().getAddress();
            long[] window = ipFailures.get(ip);
            if (window != null && window[0] >= IP_MAX_FAILURES) {
                player.disconnect(Component.text("Слишком много неудачных попыток. Попробуйте позже."));
                return;
            }
            (register ? register(player.getUsername(), args[0]) : authenticate(player.getUsername(), args[0]))
                    .whenComplete((error, failure) -> {
                        if (failure != null) {
                            logger.warn("Drasl request failed for {}", player.getUsername(), failure);
                            player.sendMessage(Component.text("Сервер аккаунтов недоступен, попробуйте ещё раз.", NamedTextColor.RED));
                        } else if (error != null) {
                            fail(player, ip, error);
                        } else {
                            admit(player);
                        }
                    });
        }
    }

    private final class LogoutCommand implements SimpleCommand {
        @Override
        public boolean hasPermission(Invocation invocation) {
            return invocation.source() instanceof Player player && authed.contains(player.getUniqueId());
        }

        @Override
        public void execute(Invocation invocation) {
            if (!(invocation.source() instanceof Player player)) return;
            player.sendPluginMessage(SESSION, concat(utf(""), utf("")));
            // Пауза, чтобы сброс сессии дошёл до клиента раньше разрыва соединения.
            proxy.getScheduler().buildTask(BlockfieldProxy.this, () -> player.disconnect(
                    Component.text("Вы вышли. В следующий раз понадобится /login."))).delay(Duration.ofMillis(500)).schedule();
        }
    }

    private void fail(Player player, InetAddress ip, String message) {
        ipFailures.compute(ip, (k, w) -> w == null ? new long[]{1, System.currentTimeMillis()} : new long[]{w[0] + 1, w[1]});
        int count = failures.merge(player.getUniqueId(), 1, Integer::sum);
        if (count >= MAX_FAILURES) {
            player.disconnect(Component.text(message));
        } else {
            player.sendMessage(Component.text(message, NamedTextColor.RED));
        }
    }

    private void admit(Player player) {
        authed.add(player.getUniqueId());
        failures.remove(player.getUniqueId());
        if (sessionSecret != null) {
            String[] session = Sessions.issue(sessionSecret, player.getUsername(), System.currentTimeMillis() / 1000);
            player.sendPluginMessage(SESSION, concat(utf(session[0]), utf(session[1])));
        }
        player.sendMessage(Component.text("Вход выполнен. На этом компьютере игра запомнит его на 30 дней, выйти — /logout.", NamedTextColor.GREEN));
        resolveDrasl(player.getUniqueId(), player.getUsername(), null).thenRun(() -> target(player).ifPresentOrElse(
                backend -> backend.ping().thenRun(() -> player.createConnectionRequest(backend).fireAndForget()),
                () -> player.sendMessage(Component.text(gate.message(), NamedTextColor.GOLD))));
    }

    /** null — пароль верный и ник совпадает с аккаунтом; иначе текст ошибки для игрока. */
    CompletableFuture<String> authenticate(String name, String password) {
        JsonObject body = new JsonObject();
        JsonObject agent = new JsonObject();
        agent.addProperty("name", "Minecraft");
        agent.addProperty("version", 1);
        body.add("agent", agent);
        body.addProperty("username", name);
        body.addProperty("password", password);
        return post("/auth/authenticate", body).thenApply(response -> {
            if (response.statusCode() != 200) return "Неверный пароль.";
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonObject invalidate = new JsonObject();
            invalidate.add("accessToken", json.get("accessToken"));
            invalidate.add("clientToken", json.get("clientToken"));
            post("/auth/invalidate", invalidate);
            String profile = json.has("selectedProfile") ? json.getAsJsonObject("selectedProfile").get("name").getAsString() : "";
            return profile.equals(name) ? null : "Этот аккаунт играет под ником " + profile + " — зайдите с ним.";
        });
    }

    CompletableFuture<String> register(String name, String password) {
        JsonObject body = new JsonObject();
        body.addProperty("username", name);
        body.addProperty("password", password);
        body.addProperty("playerName", name);
        return post("/drasl/api/v3/users", body).thenApply(response -> {
            if (response.statusCode() / 100 == 2) return null;
            try {
                return "Не удалось зарегистрироваться: " + JsonParser.parseString(response.body()).getAsJsonObject().get("message").getAsString();
            } catch (RuntimeException e) {
                return "Не удалось зарегистрироваться (" + response.statusCode() + ").";
            }
        });
    }

    /** Drasl profile id, если клиент действительно вошёл под этим ником; иначе null. */
    CompletableFuture<String> hasJoined(String name, String serverId) {
        return profile(URI.create(drasl + "/session/minecraft/hasJoined?username=" + URLEncoder.encode(name, StandardCharsets.UTF_8)
                + "&serverId=" + serverId), name);
    }

    CompletableFuture<String> profileId(String name) {
        return profile(URI.create(drasl + "/account/users/profiles/minecraft/" + URLEncoder.encode(name, StandardCharsets.UTF_8)), name);
    }

    private CompletableFuture<String> profile(URI uri, String name) {
        return http.sendAsync(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
                    if (response.statusCode() != 200) return null;
                    JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                    return json.get("name").getAsString().equals(name) ? json.get("id").getAsString() : null;
                });
    }

    private CompletableFuture<HttpResponse<String>> post(String path, JsonObject body) {
        return http.sendAsync(HttpRequest.newBuilder(URI.create(drasl + path)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
    }

    private Optional<RegisteredServer> server(String name) {
        return proxy.getServer(name);
    }

    private static boolean isLobby(RegisteredServer server) {
        return server.getServerInfo().getName().equals("lobby");
    }

    private static String key(java.net.InetSocketAddress address, String name) {
        return address + "/" + name;
    }

    private byte[] bytes(int count) {
        byte[] value = new byte[count];
        random.nextBytes(value);
        return value;
    }

    /** Строка в формате FriendlyByteBuf#writeUtf: VarInt длины + UTF-8. */
    static byte[] utf(String value) {
        byte[] data = value.getBytes(StandardCharsets.UTF_8);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(data.length + 3);
        int length = data.length;
        while ((length & ~0x7f) != 0) {
            out.write((length & 0x7f) | 0x80);
            length >>>= 7;
        }
        out.write(length);
        out.writeBytes(data);
        return out.toByteArray();
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] out = java.util.Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}

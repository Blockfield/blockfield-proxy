package io.blockfield.proxy;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.proxy.LoginPhaseConnection;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import org.slf4j.Logger;

import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Drasl login, remembered sessions, and login rate limits. */
final class Authentication {
    static final MinecraftChannelIdentifier AUTH =
            MinecraftChannelIdentifier.create("blockfield", "auth");

    /** play-пакет клиенту: writeUtf(jwt) + writeUtf(ключ); пустые строки — забыть вход. */
    static final MinecraftChannelIdentifier SESSION =
            MinecraftChannelIdentifier.create("blockfield", "session");

    static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");
    private static final int MAX_FAILURES = 5;
    private static final long IP_WINDOW_MS = TimeUnit.MINUTES.toMillis(10);
    private static final int IP_MAX_FAILURES = 15;
    private final ProxyServer proxy;
    private final Logger logger;
    private final String drasl;
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final SecureRandom random = new SecureRandom();
    // Смена секрета форвардинга разлогинивает сохранённые сессии.
    private final byte[] sessionSecret;

    private final Object plugin;
    private final LoginForwarding forwarding;

    private record Pending(
            long createdAt,
            CompletableFuture<Boolean> auth,
            CompletableFuture<byte[]> pack,
            CompletableFuture<byte[]> contract,
            AtomicReference<String> drasl) {}

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final Set<UUID> authed = ConcurrentHashMap.newKeySet();

    /** Drasl profile id подтверждённого аккаунта: ACL мастерской сверяет id и ник вместе. */
    private final Map<UUID, String> draslIds = new ConcurrentHashMap<>();

    private final Map<UUID, Integer> failures = new ConcurrentHashMap<>();
    private final Map<InetAddress, long[]> ipFailures = new ConcurrentHashMap<>();

    Authentication(Object plugin, ProxyServer proxy, Logger logger, LoginForwarding forwarding) {
        this(
                plugin,
                proxy,
                logger,
                forwarding,
                System.getenv()
                        .getOrDefault("BLOCKFIELD_DRASL_URL", "https://skins.blockfield.pro"),
                Optional.ofNullable(System.getenv("VELOCITY_FORWARDING_SECRET"))
                        .filter(s -> !s.isBlank())
                        .map(Sessions::secret)
                        .orElse(null));
    }

    Authentication(
            Object plugin,
            ProxyServer proxy,
            Logger logger,
            LoginForwarding forwarding,
            String drasl,
            byte[] sessionSecret) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.logger = logger;
        this.forwarding = forwarding;
        this.drasl = drasl;
        this.sessionSecret = sessionSecret;
    }

    void registerCommands(Consumer<Player> admitted) {
        var commands = proxy.getCommandManager();
        commands.register(
                commands.metaBuilder("login").aliases("l").plugin(plugin).build(),
                new AuthCommand(false, admitted));
        commands.register(
                commands.metaBuilder("register").aliases("reg").plugin(plugin).build(),
                new AuthCommand(true, admitted));
        commands.register(
                commands.metaBuilder("logout").plugin(plugin).build(), new LogoutCommand());
    }

    boolean authenticated(Player player) {
        return authed.contains(player.getUniqueId());
    }

    String profileId(UUID player) {
        return draslIds.get(player);
    }

    void expire() {
        long now = System.currentTimeMillis();
        pending.values().removeIf(p -> now - p.createdAt() > 60_000);
        ipFailures.values().removeIf(w -> now - w[1] > IP_WINDOW_MS);
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        authed.remove(id);
        draslIds.remove(id);
        failures.remove(id);
    }

    @Subscribe
    public void onPreLogin(PreLoginEvent event) {
        if (!NAME.matcher(event.getUsername()).matches()) {
            event.setResult(
                    PreLoginEvent.PreLoginComponentResult.denied(
                            Component.text("Ник: 3–16 символов, латиница, цифры и _")));
            return;
        }
        if (!(event.getConnection() instanceof LoginPhaseConnection connection)) return;
        var auth = new CompletableFuture<Boolean>();
        var pack = new CompletableFuture<byte[]>();
        var contract = new CompletableFuture<byte[]>();
        var drasl = new AtomicReference<String>();
        pending.put(
                key(event.getConnection().getRemoteAddress(), event.getUsername()),
                new Pending(System.currentTimeMillis(), auth, pack, contract, drasl));
        connection.sendLoginPluginMessage(LoginForwarding.PACK, new byte[0], pack::complete);
        connection.sendLoginPluginMessage(
                LoginForwarding.CONTRACT, new byte[0], contract::complete);
        String serverId = HexFormat.of().formatHex(bytes(20));
        connection.sendLoginPluginMessage(
                AUTH,
                utf(serverId),
                response -> {
                    if (response != null && response.length == 1 && response[0] == 1) {
                        hasJoined(event.getUsername(), serverId)
                                .whenComplete(
                                        (id, error) -> {
                                            if (error == null && id != null) drasl.set(id);
                                            auth.complete(error == null && id != null);
                                        });
                    } else if (response != null && response.length > 1 && sessionSecret != null) {
                        auth.complete(
                                Sessions.verify(
                                        sessionSecret,
                                        event.getUsername(),
                                        serverId,
                                        response,
                                        System.currentTimeMillis() / 1000));
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
        return EventTask.resumeWhenComplete(
                CompletableFuture.allOf(
                        entry.pack()
                                .completeOnTimeout(null, 10, TimeUnit.SECONDS)
                                .thenAccept(
                                        reply -> {
                                            if (reply != null) forwarding.rememberPack(id, reply);
                                        }),
                        entry.contract()
                                .completeOnTimeout(null, 10, TimeUnit.SECONDS)
                                .thenAccept(
                                        reply -> {
                                            if (reply != null)
                                                forwarding.rememberContract(id, reply);
                                        }),
                        entry.auth()
                                .completeOnTimeout(false, 15, TimeUnit.SECONDS)
                                .thenCompose(
                                        ok -> {
                                            if (!ok) return CompletableFuture.completedFuture(null);
                                            authed.add(id);
                                            return resolveDrasl(
                                                    id, player.getUsername(), entry.drasl().get());
                                        })));
    }

    /**
     * Запомненный вход (JWT) несёт только ник: id берём у Drasl по уже подтверждённому нику; сбой —
     * без доступа в мастерскую.
     */
    private CompletableFuture<Void> resolveDrasl(UUID player, String name, String known) {
        CompletableFuture<String> id =
                known != null
                        ? CompletableFuture.completedFuture(known)
                        : profileId(name)
                                .exceptionally(error -> null)
                                .completeOnTimeout(null, 5, TimeUnit.SECONDS);
        return id.thenAccept(
                value -> {
                    if (value != null) draslIds.put(player, value);
                });
    }

    private final class AuthCommand implements SimpleCommand {
        private final boolean register;

        private final Consumer<Player> admitted;

        AuthCommand(boolean register, Consumer<Player> admitted) {
            this.register = register;
            this.admitted = admitted;
        }

        @Override
        public boolean hasPermission(Invocation invocation) {
            return invocation.source() instanceof Player player
                    && !authed.contains(player.getUniqueId());
        }

        @Override
        public void execute(Invocation invocation) {
            if (!(invocation.source() instanceof Player player)) return;
            String[] args = invocation.arguments();
            if (register ? args.length != 2 : args.length != 1) {
                player.sendMessage(
                        Component.text(
                                register ? "/register <пароль> <пароль>" : "/login <пароль>",
                                NamedTextColor.RED));
                return;
            }
            if (register && !args[0].equals(args[1])) {
                player.sendMessage(Component.text("Пароли не совпадают.", NamedTextColor.RED));
                return;
            }
            InetAddress ip = player.getRemoteAddress().getAddress();
            long[] window = ipFailures.get(ip);
            if (window != null && window[0] >= IP_MAX_FAILURES) {
                player.disconnect(
                        Component.text("Слишком много неудачных попыток. Попробуйте позже."));
                return;
            }
            (register
                            ? register(player.getUsername(), args[0])
                            : authenticate(player.getUsername(), args[0]))
                    .whenComplete(
                            (error, failure) -> {
                                if (failure != null) {
                                    logger.warn(
                                            "Drasl request failed for {}",
                                            player.getUsername(),
                                            failure);
                                    player.sendMessage(
                                            Component.text(
                                                    "Сервер аккаунтов недоступен, попробуйте ещё"
                                                            + " раз.",
                                                    NamedTextColor.RED));
                                } else if (error != null) {
                                    fail(player, ip, error);
                                } else {
                                    admit(player, admitted);
                                }
                            });
        }
    }

    private final class LogoutCommand implements SimpleCommand {
        @Override
        public boolean hasPermission(Invocation invocation) {
            return invocation.source() instanceof Player player
                    && authed.contains(player.getUniqueId());
        }

        @Override
        public void execute(Invocation invocation) {
            if (!(invocation.source() instanceof Player player)) return;
            player.sendPluginMessage(SESSION, concat(utf(""), utf("")));
            // Пауза, чтобы сброс сессии дошёл до клиента раньше разрыва соединения.
            proxy.getScheduler()
                    .buildTask(
                            plugin,
                            () ->
                                    player.disconnect(
                                            Component.text(
                                                    "Вы вышли. В следующий раз понадобится"
                                                            + " /login.")))
                    .delay(Duration.ofMillis(500))
                    .schedule();
        }
    }

    private void fail(Player player, InetAddress ip, String message) {
        ipFailures.compute(
                ip,
                (k, w) ->
                        w == null
                                ? new long[] {1, System.currentTimeMillis()}
                                : new long[] {w[0] + 1, w[1]});
        int count = failures.merge(player.getUniqueId(), 1, Integer::sum);
        if (count >= MAX_FAILURES) {
            player.disconnect(Component.text(message));
        } else {
            player.sendMessage(Component.text(message, NamedTextColor.RED));
        }
    }

    private void admit(Player player, Consumer<Player> admitted) {
        authed.add(player.getUniqueId());
        failures.remove(player.getUniqueId());
        if (sessionSecret != null) {
            String[] session =
                    Sessions.issue(
                            sessionSecret, player.getUsername(), System.currentTimeMillis() / 1000);
            player.sendPluginMessage(SESSION, concat(utf(session[0]), utf(session[1])));
        }
        player.sendMessage(
                Component.text(
                        "Вход выполнен. На этом компьютере игра запомнит его на 30 дней, выйти —"
                                + " /logout.",
                        NamedTextColor.GREEN));
        resolveDrasl(player.getUniqueId(), player.getUsername(), null)
                .thenRun(() -> admitted.accept(player));
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
        return post("/auth/authenticate", body)
                .thenApply(
                        response -> {
                            if (response.statusCode() != 200) return "Неверный пароль.";
                            JsonObject json =
                                    JsonParser.parseString(response.body()).getAsJsonObject();
                            JsonObject invalidate = new JsonObject();
                            invalidate.add("accessToken", json.get("accessToken"));
                            invalidate.add("clientToken", json.get("clientToken"));
                            post("/auth/invalidate", invalidate);
                            String profile =
                                    json.has("selectedProfile")
                                            ? json.getAsJsonObject("selectedProfile")
                                                    .get("name")
                                                    .getAsString()
                                            : "";
                            return profile.equals(name)
                                    ? null
                                    : "Этот аккаунт играет под ником "
                                            + profile
                                            + " — зайдите с ним.";
                        });
    }

    CompletableFuture<String> register(String name, String password) {
        JsonObject body = new JsonObject();
        body.addProperty("username", name);
        body.addProperty("password", password);
        body.addProperty("playerName", name);
        return post("/drasl/api/v3/users", body)
                .thenApply(
                        response -> {
                            if (response.statusCode() / 100 == 2) return null;
                            try {
                                return "Не удалось зарегистрироваться: "
                                        + JsonParser.parseString(response.body())
                                                .getAsJsonObject()
                                                .get("message")
                                                .getAsString();
                            } catch (RuntimeException e) {
                                return "Не удалось зарегистрироваться ("
                                        + response.statusCode()
                                        + ").";
                            }
                        });
    }

    /** Drasl profile id, если клиент действительно вошёл под этим ником; иначе null. */
    CompletableFuture<String> hasJoined(String name, String serverId) {
        return profile(
                URI.create(
                        drasl
                                + "/session/minecraft/hasJoined?username="
                                + URLEncoder.encode(name, StandardCharsets.UTF_8)
                                + "&serverId="
                                + serverId),
                name);
    }

    CompletableFuture<String> profileId(String name) {
        return profile(
                URI.create(
                        drasl
                                + "/account/users/profiles/minecraft/"
                                + URLEncoder.encode(name, StandardCharsets.UTF_8)),
                name);
    }

    private CompletableFuture<String> profile(URI uri, String name) {
        return http.sendAsync(
                        HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build(),
                        HttpResponse.BodyHandlers.ofString())
                .thenApply(
                        response -> {
                            if (response.statusCode() != 200) return null;
                            JsonObject json =
                                    JsonParser.parseString(response.body()).getAsJsonObject();
                            return json.get("name").getAsString().equals(name)
                                    ? json.get("id").getAsString()
                                    : null;
                        });
    }

    private CompletableFuture<HttpResponse<String>> post(String path, JsonObject body) {
        return http.sendAsync(
                HttpRequest.newBuilder(URI.create(drasl + path))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
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

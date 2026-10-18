package io.blockfield.proxy;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.player.GameProfileRequestEvent;
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
            CompletableFuture<String> auth,
            CompletableFuture<byte[]> pack,
            CompletableFuture<byte[]> contract) {}

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final Set<UUID> authed = ConcurrentHashMap.newKeySet();
    private final Set<UUID> loggingOut = ConcurrentHashMap.newKeySet();

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
        Player player = event.getPlayer();
        synchronized (player) {
            UUID id = player.getUniqueId();
            authed.remove(id);
            loggingOut.remove(id);
            draslIds.remove(id);
            failures.remove(id);
        }
    }

    @Subscribe
    public void onPreLogin(PreLoginEvent event) {
        try {
            if (!RaknetOnly.raknet(event.getConnection())) {
                event.setResult(PreLoginEvent.PreLoginComponentResult.denied(RaknetOnly.DENIED));
                return;
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            // Fail open: a Velocity/Raknetify bump must not lock every player out.
            logger.error(
                    "Cannot detect the login transport; allowing {}", event.getUsername(), error);
        }
        if (!NAME.matcher(event.getUsername()).matches()) {
            event.setResult(
                    PreLoginEvent.PreLoginComponentResult.denied(
                            Component.text("Ник: 3–16 символов, латиница, цифры и _")));
            return;
        }
        if (!(event.getConnection() instanceof LoginPhaseConnection connection)) return;
        var auth = new CompletableFuture<String>();
        var pack = new CompletableFuture<byte[]>();
        var contract = new CompletableFuture<byte[]>();
        pending.put(
                key(event.getConnection().getRemoteAddress(), event.getUsername()),
                new Pending(System.currentTimeMillis(), auth, pack, contract));
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
                                            auth.complete(error == null ? id : null);
                                        });
                    } else if (response != null && response.length > 1 && sessionSecret != null) {
                        String expected =
                                Sessions.verify(
                                        sessionSecret,
                                        event.getUsername(),
                                        serverId,
                                        response,
                                        System.currentTimeMillis() / 1000);
                        if (expected == null) {
                            auth.complete(null);
                        } else {
                            resolveDrasl(event.getUsername())
                                    .thenAccept(
                                            current ->
                                                    auth.complete(
                                                            expected.equals(current)
                                                                    ? current
                                                                    : null));
                        }
                    } else {
                        auth.complete(null);
                    }
                });
    }

    /** UUID becomes immutable at LoginSuccess; resolve it before Velocity creates the player. */
    @Subscribe
    public EventTask onProfile(GameProfileRequestEvent event) {
        Pending entry =
                pending.get(key(event.getConnection().getRemoteAddress(), event.getUsername()));
        if (entry == null) return null;
        return EventTask.resumeWhenComplete(
                entry.auth()
                        .completeOnTimeout(null, 15, TimeUnit.SECONDS)
                        .exceptionally(error -> null)
                        .thenAccept(
                                id -> {
                                    if (id == null) return;
                                    event.setGameProfile(event.getGameProfile().withUndashedId(id));
                                }));
    }

    @Subscribe
    public EventTask onLogin(LoginEvent event) {
        Player player = event.getPlayer();
        Pending entry = pending.remove(key(player.getRemoteAddress(), player.getUsername()));
        if (entry == null) return null;
        UUID id = player.getUniqueId();
        String account = entry.auth().getNow(null);
        synchronized (player) {
            if (!loggingOut.contains(id)
                    && account != null
                    && id.toString().replace("-", "").equals(account)) {
                draslIds.put(id, account);
                authed.add(id);
            }
        }
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
                                        })));
    }

    /** Called only after a password or challenge proof; a lookup alone never authenticates. */
    private CompletableFuture<String> resolveDrasl(String name) {
        return profileId(name)
                .exceptionally(error -> null)
                .completeOnTimeout(null, 5, TimeUnit.SECONDS);
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
                    && !authed.contains(player.getUniqueId())
                    && !loggingOut.contains(player.getUniqueId());
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
                                    .thenCompose(
                                            error ->
                                                    error == null
                                                            ? authenticate(
                                                                    player.getUsername(), args[0])
                                                            : CompletableFuture.completedFuture(
                                                                    new PasswordProof(null, error)))
                            : authenticate(player.getUsername(), args[0]))
                    .whenComplete(
                            (proof, failure) -> {
                                if (!player.isActive() || loggingOut.contains(player.getUniqueId()))
                                    return;
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
                                } else if (proof.error() != null) {
                                    fail(player, ip, proof.error());
                                } else {
                                    admit(player, proof.id(), admitted);
                                }
                            });
        }
    }

    private final class LogoutCommand implements SimpleCommand {
        @Override
        public boolean hasPermission(Invocation invocation) {
            return invocation.source() instanceof Player;
        }

        @Override
        public void execute(Invocation invocation) {
            if (!(invocation.source() instanceof Player player)) return;
            synchronized (player) {
                loggingOut.add(player.getUniqueId());
                authed.remove(player.getUniqueId());
                draslIds.remove(player.getUniqueId());
                player.sendPluginMessage(SESSION, concat(utf(""), utf("")));
            }
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

    private void admit(Player player, String expected, Consumer<Player> admitted) {
        resolveDrasl(player.getUsername())
                .thenAccept(
                        account -> {
                            // Logout must be the last credential/auth write even when HTTP finishes
                            // concurrently.
                            synchronized (player) {
                                if (!player.isActive() || loggingOut.contains(player.getUniqueId()))
                                    return;
                                if (expected == null || !expected.equals(account)) {
                                    player.sendMessage(
                                            Component.text(
                                                    "Не удалось подтвердить профиль аккаунта."
                                                            + " Попробуйте ещё раз.",
                                                    NamedTextColor.RED));
                                    return;
                                }
                                failures.remove(player.getUniqueId());
                                boolean sameIdentity =
                                        player.getUniqueId()
                                                .toString()
                                                .replace("-", "")
                                                .equals(account);
                                if (sessionSecret == null && !sameIdentity) {
                                    player.sendMessage(
                                            Component.text(
                                                    "Сохранённый вход недоступен. Войдите через"
                                                            + " лаунчер Blockfield.",
                                                    NamedTextColor.RED));
                                    return;
                                }
                                if (sessionSecret != null) {
                                    String[] session =
                                            Sessions.issue(
                                                    sessionSecret,
                                                    player.getUsername(),
                                                    account,
                                                    System.currentTimeMillis() / 1000);
                                    player.sendPluginMessage(
                                            SESSION, concat(utf(session[0]), utf(session[1])));
                                }
                                if (sameIdentity) {
                                    draslIds.put(player.getUniqueId(), account);
                                    authed.add(player.getUniqueId());
                                    player.sendMessage(
                                            Component.text(
                                                    "Вход выполнен. Выйти — /logout.",
                                                    NamedTextColor.GREEN));
                                    admitted.accept(player);
                                    return;
                                }
                                // A play-phase player still has its offline UUID: never forward it
                                // to a
                                // backend.
                                player.sendMessage(
                                        Component.text(
                                                "Вход сохранён на 30 дней. Переподключитесь к этому"
                                                        + " же адресу сервера, чтобы войти с UUID"
                                                        + " аккаунта. Выйти — /logout.",
                                                NamedTextColor.GREEN));
                            }
                        });
    }

    record PasswordProof(String id, String error) {}

    /** UUID comes from the password-proven selectedProfile, never from a nickname lookup. */
    CompletableFuture<PasswordProof> authenticate(String name, String password) {
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
                            if (response.statusCode() != 200)
                                return new PasswordProof(null, "Неверный пароль.");
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
                            if (!profile.equals(name))
                                return new PasswordProof(
                                        null,
                                        "Этот аккаунт играет под ником "
                                                + profile
                                                + " — зайдите с ним.");
                            String id =
                                    profileIdentity(json.getAsJsonObject("selectedProfile"), name);
                            return id == null
                                    ? new PasswordProof(
                                            null, "Не удалось подтвердить UUID аккаунта.")
                                    : new PasswordProof(id, null);
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
                            return profileIdentity(json, name);
                        });
    }

    private static String profileIdentity(JsonObject json, String name) {
        if (!json.has("id") || !json.has("name")) return null;
        String id = json.get("id").getAsString();
        return json.get("name").getAsString().equals(name)
                        && (id.matches("[0-9a-fA-F]{32}")
                                || id.matches(
                                        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
                ? Gate.normalizeId(id)
                : null;
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

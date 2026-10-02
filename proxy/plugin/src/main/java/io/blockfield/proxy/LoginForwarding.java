package io.blockfield.proxy;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerLoginPluginMessageEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Login-to-play compatibility forwarding for pack version, pack contract and TaCZ Tweaks. */
final class LoginForwarding {
    static final MinecraftChannelIdentifier PACK =
            MinecraftChannelIdentifier.create("blockfield", "pack_version");

    // Контракт совместимости: сервер принимает по нему клиентов, которые отвечают на этот канал;
    // без ответа он проверяет отпечаток пака (PACK).
    static final MinecraftChannelIdentifier CONTRACT =
            MinecraftChannelIdentifier.create("blockfield", "pack_contract");

    // TaCZ Tweaks шлёт конфиг сервера login-запросом и кикает за «не понят»; после входа клиента
    // login-фаза уже прошла, поэтому отвечаем за него и досылаем тот же конфиг play-пакетом.
    static final MinecraftChannelIdentifier TACZ_TWEAKS =
            MinecraftChannelIdentifier.create("tacztweaks", "server_sync_config");
    private final Map<UUID, byte[]> packReplies = new ConcurrentHashMap<>();
    private final Map<UUID, byte[]> contractReplies = new ConcurrentHashMap<>();
    private final Map<UUID, byte[]> taczConfig = new ConcurrentHashMap<>();

    void rememberPack(UUID player, byte[] reply) {
        packReplies.put(player, reply);
    }

    void rememberContract(UUID player, byte[] reply) {
        contractReplies.put(player, reply);
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        packReplies.remove(id);
        contractReplies.remove(id);
        taczConfig.remove(id);
    }

    @Subscribe
    public void onBackendLoginMessage(ServerLoginPluginMessageEvent event) {
        UUID id = event.getConnection().getPlayer().getUniqueId();
        if (event.getIdentifier().getId().equals(TACZ_TWEAKS.getId())) {
            taczConfig.put(id, event.getContents());
            event.setResult(ServerLoginPluginMessageEvent.ResponseResult.reply(new byte[0]));
        } else if (event.getIdentifier().getId().equals(PACK.getId())
                || event.getIdentifier().getId().equals(CONTRACT.getId())) {
            var replies =
                    event.getIdentifier().getId().equals(PACK.getId())
                            ? packReplies
                            : contractReplies;
            byte[] reply = replies.get(id);
            // Нет ответа от клиента (ванильный или устаревший пак): сервер сам кикнет с понятным
            // текстом или проверит отпечаток пака.
            event.setResult(
                    reply == null
                            ? ServerLoginPluginMessageEvent.ResponseResult.unknown()
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
    }

    private static boolean isLobby(RegisteredServer server) {
        return server.getServerInfo().getName().equals("lobby");
    }
}

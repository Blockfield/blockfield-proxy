package io.blockfield.proxy;

import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class BlockfieldProxyTest {
    @Test
    void onlyRestartsParkPlayers() {
        assertTrue(BlockfieldProxy.parkable(Optional.empty()));
        assertTrue(BlockfieldProxy.parkable(Optional.of(Component.translatable("multiplayer.disconnect.server_shutdown"))));
        assertTrue(BlockfieldProxy.parkable(Optional.of(Component.translatable("velocity.error.internal-server-connection-error"))));
        assertFalse(BlockfieldProxy.parkable(Optional.of(Component.text("Требуется обновление пака."))));
        assertFalse(BlockfieldProxy.parkable(Optional.of(Component.translatable("multiplayer.disconnect.kicked"))));
    }

    @Test
    void serverIdIsEncodedLikeWriteUtf() {
        assertArrayEquals(new byte[]{2, 'a', 'b'}, BlockfieldProxy.utf("ab"));
        assertEquals(41, BlockfieldProxy.utf("0".repeat(40)).length);
        assertTrue(BlockfieldProxy.NAME.matcher("Player_115").matches());
        assertFalse(BlockfieldProxy.NAME.matcher("ab").matches());
    }
}

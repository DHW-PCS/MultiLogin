package moe.caa.multilogin.core.handle;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlayerHandlerTest {

    @Test
    void pendingCleanupDoesNotRemoveAnExistingOnlineSession() {
        PlayerHandler handler = new PlayerHandler(null);
        UUID playerId = UUID.randomUUID();
        PlayerHandler.Entry activeEntry = new PlayerHandler.Entry(
                null,
                null,
                System.currentTimeMillis()
        );
        PlayerHandler.Entry pendingEntry = new PlayerHandler.Entry(
                null,
                null,
                System.currentTimeMillis()
        );

        handler.getLoginCache().put(playerId, activeEntry);
        handler.pushPlayerJoinGame(playerId, "player");
        handler.getLoginCache().put(playerId, pendingEntry);

        handler.discardPendingPlayerData(playerId);

        assertNull(handler.getLoginCache().get(playerId));
        assertSame(activeEntry, handler.getPlayerData(playerId));
    }

    @Test
    void fullCleanupRemovesPendingAndOnlineSessions() {
        PlayerHandler handler = new PlayerHandler(null);
        UUID playerId = UUID.randomUUID();
        PlayerHandler.Entry entry = new PlayerHandler.Entry(
                null,
                null,
                System.currentTimeMillis()
        );

        handler.getLoginCache().put(playerId, entry);
        handler.pushPlayerJoinGame(playerId, "player");
        handler.getLoginCache().put(playerId, entry);

        handler.discardPlayerData(playerId);

        assertNull(handler.getLoginCache().get(playerId));
        assertNull(handler.getPlayerData(playerId));
    }
}

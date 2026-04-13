package org.enthusia.playtime.activity;

import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks the start time of each player's current session.
 * Only counts time since last join (not lifetime).
 */
public final class SessionManager {

    private final Map<UUID, Long> sessionStart = new ConcurrentHashMap<>();

    public void handleJoin(Player player) {
        sessionStart.put(player.getUniqueId(), System.currentTimeMillis());
    }

    public void handleQuit(Player player) {
        sessionStart.remove(player.getUniqueId());
    }

    /**
     * @return current session length in milliseconds, or 0 if unknown
     */
    public long getCurrentSessionMillis(UUID uuid, long nowMillis) {
        Long start = sessionStart.get(uuid);
        if (start == null) return 0L;
        long diff = nowMillis - start;
        return Math.max(0L, diff);
    }

    public long getSessionLengthMillis(UUID uuid) {
        return 0;
    }
}

package org.enthusia.playtime.activity;

import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SessionManager {

    private final ConcurrentHashMap<UUID, Long> sessionStart = new ConcurrentHashMap<>();

    public SessionManager() {
    }

    public SessionManager(Map<UUID, Long> initialState) {
        if (initialState != null && !initialState.isEmpty()) {
            sessionStart.putAll(initialState);
        }
    }

    public void handleJoin(Player player) {
        handleJoin(player.getUniqueId(), System.currentTimeMillis());
    }

    public void handleJoin(UUID uuid, long joinedAtMillis) {
        sessionStart.putIfAbsent(uuid, joinedAtMillis);
    }

    public void handleQuit(Player player) {
        handleQuit(player.getUniqueId());
    }

    public void handleQuit(UUID uuid) {
        sessionStart.remove(uuid);
    }

    public long getCurrentSessionMillis(UUID uuid, long nowMillis) {
        Long start = sessionStart.get(uuid);
        if (start == null) {
            return 0L;
        }
        return Math.max(0L, nowMillis - start);
    }

    public long getSessionLengthMillis(UUID uuid) {
        return getCurrentSessionMillis(uuid, System.currentTimeMillis());
    }

    public Map<UUID, Long> snapshot() {
        return Collections.unmodifiableMap(new HashMap<>(sessionStart));
    }
}

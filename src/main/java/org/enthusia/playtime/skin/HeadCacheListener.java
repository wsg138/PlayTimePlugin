package org.enthusia.playtime.skin;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.enthusia.playtime.PlayTimePlugin;

/**
 * Captures player heads when they join so we cache their real skin
 * (and last known name) for offline display and after restarts.
 */
public final class HeadCacheListener implements Listener {

    private final PlayTimePlugin plugin;
    private final HeadCache headCache;

    public HeadCacheListener(PlayTimePlugin plugin, HeadCache headCache) {
        this.plugin = plugin;
        this.headCache = headCache;
    }

    @EventHandler(ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // Delay a tick to ensure the skin/profile is fully available.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player online = Bukkit.getPlayer(player.getUniqueId());
            if (online != null && online.isOnline()) {
                headCache.updateHead(online);
            }
        }, 2L);
    }
}

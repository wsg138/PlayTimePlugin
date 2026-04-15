package org.enthusia.playtime.skin;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.enthusia.playtime.PlayTimePlugin;
import org.enthusia.playtime.service.PlaytimeRuntime;

public final class HeadCacheListener implements Listener {

    private final PlayTimePlugin plugin;

    public HeadCacheListener(PlayTimePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            PlaytimeRuntime runtime = plugin.runtime();
            Player player = Bukkit.getPlayer(event.getPlayer().getUniqueId());
            if (runtime != null && player != null && player.isOnline()) {
                runtime.noteHead(player);
            }
        }, 2L);
    }
}

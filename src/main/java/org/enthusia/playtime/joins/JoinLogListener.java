package org.enthusia.playtime.joins;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.enthusia.playtime.PlayTimePlugin;
import org.enthusia.playtime.service.PlaytimeRuntime;

import java.time.Instant;

public final class JoinLogListener implements Listener {

    private final PlayTimePlugin plugin;

    public JoinLogListener(PlayTimePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        PlaytimeRuntime runtime = plugin.runtime();
        if (runtime != null) {
            runtime.handleJoinRecorded(event.getPlayer().getUniqueId(), Instant.now());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onQuit(PlayerQuitEvent event) {
        PlaytimeRuntime runtime = plugin.runtime();
        if (runtime != null) {
            runtime.handleQuitRecorded(event.getPlayer().getUniqueId(), Instant.now());
        }
    }
}

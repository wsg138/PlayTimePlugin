package org.enthusia.playtime.joins;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.enthusia.playtime.PlayTimePlugin;
import org.enthusia.playtime.data.PlaytimeRepository;

import java.sql.SQLException;
import java.util.logging.Level;

/**
 * Logs player joins into the canonical joins_log table used by the repository.
 */
public final class JoinLogListener implements Listener {

    private final PlayTimePlugin plugin;
    private final PlaytimeRepository repository;

    public JoinLogListener(PlayTimePlugin plugin) {
        this.plugin = plugin;
        this.repository = plugin.getRepository();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        try {
            repository.recordJoin(event.getPlayer().getUniqueId(), java.time.Instant.now());
        } catch (SQLException ex) {
            plugin.getLogger().log(
                    Level.WARNING,
                    "[EnthusiaPlaytime] Failed to record join for " + event.getPlayer().getName(),
                    ex
            );
        }
    }
}

package org.enthusia.playtime.joins;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.enthusia.playtime.PlayTimePlugin;
import org.enthusia.playtime.config.PlaytimeConfig;
import org.enthusia.playtime.service.PlaytimeRuntime;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class FirstJoinWelcomeListener implements Listener {

    private static final Pattern HEX_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");

    private final PlayTimePlugin plugin;

    public FirstJoinWelcomeListener(PlayTimePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        PlaytimeRuntime runtime = plugin.runtime();
        if (runtime == null) {
            return;
        }

        PlaytimeConfig config = runtime.config();
        if (!config.isFirstJoinEnabled()) {
            return;
        }

        Player player = event.getPlayer();
        boolean firstKnownJoin = !runtime.isKnownPlayer(player.getUniqueId());
        if (!firstKnownJoin) {
            return;
        }

        String broadcast = format(config.getFirstJoinBroadcast(), player, runtime);
        if (!broadcast.isBlank()) {
            Bukkit.broadcastMessage(broadcast);
        }

        for (String line : config.getFirstJoinPlayerLines()) {
            String formatted = format(line, player, runtime);
            if (!formatted.isBlank()) {
                player.sendMessage(formatted);
            }
        }

        if (config.isFirstJoinPingEnabled()) {
            Sound sound = resolveSound(config.getFirstJoinPingSound());
            if (sound != null) {
                player.playSound(player.getLocation(), sound, config.getFirstJoinPingVolume(), config.getFirstJoinPingPitch());
            }
        }
    }

    private String format(String message, Player player, PlaytimeRuntime runtime) {
        if (message == null) {
            return "";
        }
        int uniqueNumber = runtime.repository().countKnownPlayers() + 1;
        String replaced = message.replace("%player%", player.getName())
                .replace("{USERNAME}", player.getName())
                .replace("{UNIQUE}", String.valueOf(uniqueNumber));
        String withHex = applyHexColors(replaced);
        return ChatColor.translateAlternateColorCodes('&', withHex);
    }

    private Sound resolveSound(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return Sound.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("Unknown first-join ping sound '" + name + "'. Sound disabled.");
            return null;
        }
    }

    private String applyHexColors(String message) {
        Matcher matcher = HEX_PATTERN.matcher(message);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String replacement = net.md_5.bungee.api.ChatColor.of("#" + matcher.group(1)).toString();
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }
}

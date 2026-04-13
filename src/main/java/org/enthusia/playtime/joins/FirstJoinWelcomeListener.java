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
import org.enthusia.playtime.data.PlaytimeRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Announces first-time joins and pings the joining player with a configurable message/sound.
 */
public final class FirstJoinWelcomeListener implements Listener {

    private final PlayTimePlugin plugin;
    private final PlaytimeRepository repository;

    private static final Pattern HEX_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");

    private final boolean enabled;
    private final String broadcastMessage;
    private final List<String> playerMessageLines;
    private final boolean pingEnabled;
    private final Sound pingSound;
    private final float pingVolume;
    private final float pingPitch;

    public FirstJoinWelcomeListener(PlayTimePlugin plugin, PlaytimeConfig config) {
        this.plugin = plugin;
        this.repository = plugin.getRepository();

        this.enabled = config.isFirstJoinEnabled();
        this.broadcastMessage = config.getFirstJoinBroadcast();

        List<String> rawLines = config.getFirstJoinPlayerLines();
        this.playerMessageLines = new ArrayList<>(rawLines);

        this.pingEnabled = config.isFirstJoinPingEnabled();
        String soundName = config.getFirstJoinPingSound();
        this.pingSound = resolveSound(soundName, plugin.getLogger());
        this.pingVolume = config.getFirstJoinPingVolume();
        this.pingPitch = config.getFirstJoinPingPitch();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        if (!enabled) {
            return;
        }

        Player player = event.getPlayer();
        if (player.hasPlayedBefore()) {
            return; // only fire for first-ever joins
        }

        String broadcast = format(broadcastMessage, player);
        if (!broadcast.isBlank()) {
            Bukkit.broadcastMessage(broadcast);
        }

        for (String line : playerMessageLines) {
            String formatted = format(line, player);
            if (!formatted.isBlank()) {
                player.sendMessage(formatted);
            }
        }

        if (pingEnabled && pingSound != null) {
            player.playSound(player.getLocation(), pingSound, pingVolume, pingPitch);
        }
    }

    private String format(String msg, Player player) {
        if (msg == null) {
            return "";
        }
        String replaced = msg.replace("%player%", player.getName())
                .replace("{USERNAME}", player.getName())
                .replace("{UNIQUE}", String.valueOf(getUniqueJoinNumber(player)));
        String withHex = applyHexColors(replaced);
        return ChatColor.translateAlternateColorCodes('&', withHex);
    }

    private Sound resolveSound(String name, Logger logger) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return Sound.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            logger.warning("[EnthusiaPlaytime] Unknown first-join ping sound '" + name + "'. Sound disabled.");
            return null;
        }
    }

    private String applyHexColors(String msg) {
        Matcher matcher = HEX_PATTERN.matcher(msg);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String color = matcher.group(1);
            String replacement = net.md_5.bungee.api.ChatColor.of("#" + color).toString();
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private int getUniqueJoinNumber(Player player) {
        try {
            int count = repository.getServerUniquePlayers("ALL", java.time.Instant.now());
            boolean exists = repository.getLifetime(player.getUniqueId()).isPresent();
            return exists ? count : (count + 1);
        } catch (Exception ex) {
            return 0;
        }
    }
}

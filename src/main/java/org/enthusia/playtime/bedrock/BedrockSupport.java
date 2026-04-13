package org.enthusia.playtime.bedrock;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.UUID;

public final class BedrockSupport {

    private final JavaPlugin plugin;
    private final boolean floodgatePresent;
    private Object floodgateApi;
    private Method fgGetInstance;
    private Method fgIsFloodgatePlayer;

    public BedrockSupport(JavaPlugin plugin) {
        this.plugin = plugin;
        this.floodgatePresent = Bukkit.getPluginManager().getPlugin("floodgate") != null;
        initReflection();
    }

    private void initReflection() {
        if (!floodgatePresent) return;
        try {
            Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            fgGetInstance = apiClass.getMethod("getInstance");
            fgIsFloodgatePlayer = apiClass.getMethod("isFloodgatePlayer", UUID.class);
            floodgateApi = fgGetInstance.invoke(null);
            plugin.getLogger().info("Floodgate detected, Bedrock-aware GUIs enabled.");
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to hook Floodgate API via reflection: " + e.getMessage());
        }
    }

    public boolean isBedrock(Player player) {
        if (floodgateApi == null || fgIsFloodgatePlayer == null) return false;
        try {
            return (boolean) fgIsFloodgatePlayer.invoke(floodgateApi, player.getUniqueId());
        } catch (Exception e) {
            return false;
        }
    }
}

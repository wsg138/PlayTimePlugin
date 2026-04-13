package org.enthusia.playtime.util;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class HeadUtils {

    private static final Map<UUID, ItemStack> CACHE = new ConcurrentHashMap<>();

    private HeadUtils() {
    }

    /**
     * Returns a player head item for the given UUID, using a small cache so
     * heads keep their correct textures even after the player logs out.
     *
     * Logic:
     *  - If player is online, always rebuild from the online player and refresh cache.
     *  - Else if we already have a cached head, reuse it.
     *  - Else create from OfflinePlayer (may be default skin) and cache it.
     *
     * fallbackName is only used if it looks like a real name (not just a UUID chunk).
     */
    public static ItemStack getPlayerHead(UUID uuid, String fallbackName) {
        // Prefer online player -> always refresh cache from online profile
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) skull.getItemMeta();
            meta.setOwningPlayer(online);
            meta.setDisplayName(online.getName());
            skull.setItemMeta(meta);

            CACHE.put(uuid, skull.clone());
            return skull;
        }

        // If we have a cached version (from when they were online), reuse that.
        ItemStack cached = CACHE.get(uuid);
        if (cached != null) {
            return cached.clone();
        }

        // Fallback: offline player (uses Bukkit's profile cache if available)
        ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) skull.getItemMeta();
        OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
        meta.setOwningPlayer(offline);

        String name = offline.getName();
        if (name != null && !name.isEmpty()) {
            meta.setDisplayName(name);
        } else if (fallbackName != null && !looksLikeUuidChunk(fallbackName)) {
            // Only use the fallback if it doesn't look like "5e91b996"
            meta.setDisplayName(fallbackName);
        }
        skull.setItemMeta(meta);

        CACHE.put(uuid, skull.clone());
        return skull;
    }

    private static boolean looksLikeUuidChunk(String s) {
        // 8 hex characters e.g. "5e91b996"
        if (s.length() != 8) return false;
        for (int i = 0; i < 8; i++) {
            char c = s.charAt(i);
            boolean hex =
                    (c >= '0' && c <= '9') ||
                            (c >= 'a' && c <= 'f') ||
                            (c >= 'A' && c <= 'F');
            if (!hex) return false;
        }
        return true;
    }
}

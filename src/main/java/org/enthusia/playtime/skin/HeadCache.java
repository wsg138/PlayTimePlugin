package org.enthusia.playtime.skin;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.enthusia.playtime.PlayTimePlugin;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Caches player heads (with baked-in skin NBT) and last known names.
 *
 * - On join: we capture a PLAYER_HEAD with their current skin.
 * - We keep it in memory and also store it in skins.yml.
 * - GUIs then call createHead(uuid) so heads keep the right skin even
 *   across restarts and when players are offline.
 *
 * Works fine with Bedrock (Geyser/Floodgate) too because we just
 * copy whatever skin the server exposes for that Player.
 */
public final class HeadCache {

    private final PlayTimePlugin plugin;

    private final Map<UUID, ItemStack> heads = new ConcurrentHashMap<>();
    private final Map<UUID, String> names = new ConcurrentHashMap<>();

    private final File file;

    public HeadCache(PlayTimePlugin plugin) {
        this.plugin = plugin;
        if (!plugin.getDataFolder().exists()) {
            //noinspection ResultOfMethodCallIgnored
            plugin.getDataFolder().mkdirs();
        }
        this.file = new File(plugin.getDataFolder(), "skins.yml");
        load();
    }

    // -------- Public API --------

    /**
     * Capture the player's current head (with skin) and store it.
     */
    public void updateHead(Player player) {
        UUID uuid = player.getUniqueId();
        String name = player.getName();

        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta != null) {
            // This bakes the current skin into the item NBT.
            meta.setOwningPlayer(player);
            meta.setDisplayName(name);
            head.setItemMeta(meta);
        }

        heads.put(uuid, head);
        names.put(uuid, name);
    }

    /**
     * Return a cloned player head for this UUID. If we have a cached head,
     * its skin is already baked in. If not, we fall back to
     * Bukkit.getOfflinePlayer(uuid).
     */
    public ItemStack createHead(UUID uuid) {
        ItemStack cached = heads.get(uuid);
        if (cached != null) {
            return cached.clone();
        }

        // Fallback: generic head resolved from offline player
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta != null) {
            OfflinePlayer off = Bukkit.getOfflinePlayer(uuid);
            meta.setOwningPlayer(off);
            head.setItemMeta(meta);
        }
        return head;
    }

    /**
     * Last known name for this UUID (from join cache), or null if unknown.
     */
    public String getLastKnownName(UUID uuid) {
        return names.get(uuid);
    }

    /**
     * Save all cached heads + names to skins.yml
     * (typically called onDisable).
     */
    public void save() {
        YamlConfiguration out = new YamlConfiguration();

        for (Map.Entry<UUID, ItemStack> entry : heads.entrySet()) {
            UUID uuid = entry.getKey();
            ItemStack head = entry.getValue();
            String name = names.get(uuid);

            String base = "heads." + uuid;
            out.set(base + ".item", head);
            if (name != null) {
                out.set(base + ".name", name);
            }
        }

        try {
            out.save(file);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING,
                    "[EnthusiaPlaytime] Failed to save skins.yml", ex);
        }
    }

    // -------- Internal load --------

    private void load() {
        if (!file.exists()) {
            return; // nothing yet
        }

        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection sec = cfg.getConfigurationSection("heads");
        if (sec == null) {
            return;
        }

        for (String key : sec.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                ItemStack head = sec.getItemStack(key + ".item");
                String name = sec.getString(key + ".name");

                if (head != null) {
                    heads.put(uuid, head);
                }
                if (name != null && !name.isEmpty()) {
                    names.put(uuid, name);
                }
            } catch (IllegalArgumentException ex) {
                // bad UUID, ignore
                plugin.getLogger().log(Level.FINE,
                        "[EnthusiaPlaytime] Skipping invalid UUID in skins.yml: " + key, ex);
            }
        }

        plugin.getLogger().info("[EnthusiaPlaytime] Loaded " + heads.size() +
                " cached player heads from skins.yml");
    }
}

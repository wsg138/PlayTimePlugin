package org.enthusia.playtime.skin;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.enthusia.playtime.PlayTimePlugin;
import org.enthusia.playtime.util.PerformanceCounters;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private final PerformanceCounters counters;

    private final Map<UUID, ItemStack> heads = new ConcurrentHashMap<>();
    private final Map<UUID, String> names = new ConcurrentHashMap<>();

    private final File file;
    private final AtomicBoolean saveQueued = new AtomicBoolean(false);

    public HeadCache(PlayTimePlugin plugin, PerformanceCounters counters) {
        this.plugin = plugin;
        this.counters = counters;
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
        queueSave();
    }

    public void updateHeadDebounced(Player player) {
        updateHead(player);
    }

    /**
     * Return a cloned player head for this UUID. If we have a cached head,
     * its skin is already baked in. If not, we fall back to
     * Bukkit.getOfflinePlayer(uuid).
     */
    public ItemStack createHead(UUID uuid) {
        ItemStack cached = heads.get(uuid);
        if (cached != null) {
            counters.headCacheHits.increment();
            return cached.clone();
        }
        counters.headCacheMisses.increment();

        return new ItemStack(Material.PLAYER_HEAD);
    }

    /**
     * Last known name for this UUID (from join cache), or null if unknown.
     */
    public String getLastKnownName(UUID uuid) {
        return names.get(uuid);
    }

    public UUID findUuidByName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (Map.Entry<UUID, String> entry : names.entrySet()) {
            if (entry.getValue() != null && entry.getValue().equalsIgnoreCase(name)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * Save all cached heads + names to skins.yml
     * (typically called onDisable).
     */
    public void save() {
        saveQueued.set(false);
        saveSnapshot(new HashMap<>(heads), new HashMap<>(names));
    }

    private void queueSave() {
        if (!plugin.isEnabled() || !saveQueued.compareAndSet(false, true)) {
            return;
        }
        try {
            Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
                saveQueued.set(false);
                saveSnapshot(new HashMap<>(heads), new HashMap<>(names));
            }, 20L * 60L);
        } catch (IllegalPluginAccessException exception) {
            saveQueued.set(false);
            if (plugin.isEnabled()) {
                throw exception;
            }
        }
    }

    private void saveSnapshot(Map<UUID, ItemStack> headSnapshot, Map<UUID, String> nameSnapshot) {
        YamlConfiguration out = new YamlConfiguration();
        out.set("meta.version", 1);

        for (Map.Entry<UUID, ItemStack> entry : headSnapshot.entrySet()) {
            UUID uuid = entry.getKey();
            ItemStack head = entry.getValue();
            String name = nameSnapshot.get(uuid);

            String base = "heads." + uuid;
            out.set(base + ".item", head);
            if (name != null) {
                out.set(base + ".name", name);
            }
        }

        try {
            File temp = new File(file.getParentFile(), file.getName() + ".tmp");
            out.save(temp);
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveFailure) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            counters.headCacheSaves.increment();
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

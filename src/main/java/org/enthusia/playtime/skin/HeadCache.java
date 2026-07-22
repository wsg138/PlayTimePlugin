package org.enthusia.playtime.skin;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.enthusia.playtime.PlayTimePlugin;
import org.enthusia.playtime.data.PlaytimeRepository;
import org.enthusia.playtime.util.PerformanceCounters;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Keeps player skin texture properties in memory and persists them asynchronously.
 * Legacy skins.yml data is imported once into the configured SQL backend.
 */
public final class HeadCache implements AutoCloseable {

    private record CachedSkin(SkinProfile profile, long version) {
    }

    private static final String TEXTURES_PROPERTY = "textures";

    private final PlayTimePlugin plugin;
    private final PerformanceCounters counters;
    private final PlaytimeRepository repository;
    private final Map<UUID, CachedSkin> profiles = new ConcurrentHashMap<>();
    private final Set<UUID> dirtyKeys = ConcurrentHashMap.newKeySet();
    private final Object flushLock = new Object();
    private final ArrayList<CompletableFuture<Void>> pendingFlushes = new ArrayList<>();
    private final ExecutorService writerExecutor = Executors.newSingleThreadExecutor(new SkinWriterThreadFactory());

    private volatile boolean flushQueued;
    private volatile boolean closed;

    public HeadCache(PlayTimePlugin plugin, PerformanceCounters counters, PlaytimeRepository repository) throws Exception {
        this.plugin = plugin;
        this.counters = counters;
        this.repository = repository;
        load();
    }

    public void updateHead(Player player) {
        cacheProfile(player.getUniqueId(), player.getName(), player.getPlayerProfile());
    }

    public void updateHeadDebounced(Player player) {
        updateHead(player);
    }

    public ItemStack createHead(UUID uuid) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        CachedSkin cachedSkin = profiles.get(uuid);
        if (cachedSkin == null || isBlank(cachedSkin.profile().textureValue())) {
            counters.headCacheMisses.increment();
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(uuid));
        } else {
            counters.headCacheHits.increment();
            PlayerProfile profile = Bukkit.getServer().createProfile(uuid);
            String signature = cachedSkin.profile().textureSignature();
            ProfileProperty property = isBlank(signature)
                    ? new ProfileProperty(TEXTURES_PROPERTY, cachedSkin.profile().textureValue())
                    : new ProfileProperty(TEXTURES_PROPERTY, cachedSkin.profile().textureValue(), signature);
            profile.setProperty(property);
            meta.setPlayerProfile(profile);
        }
        head.setItemMeta(meta);
        return head;
    }

    public String getLastKnownName(UUID uuid) {
        CachedSkin cachedSkin = profiles.get(uuid);
        return cachedSkin == null ? null : cachedSkin.profile().lastKnownName();
    }

    public UUID findUuidByName(String name) {
        if (isBlank(name)) {
            return null;
        }
        for (Map.Entry<UUID, CachedSkin> entry : profiles.entrySet()) {
            String knownName = entry.getValue().profile().lastKnownName();
            if (knownName != null && knownName.equalsIgnoreCase(name)) {
                return entry.getKey();
            }
        }
        return null;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        flushBlocking();
        closed = true;
        writerExecutor.shutdown();
        try {
            if (!writerExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("Player skin writer did not stop cleanly within 10 seconds.");
                writerExecutor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            writerExecutor.shutdownNow();
        }
    }

    private void load() throws Exception {
        for (Map.Entry<UUID, SkinProfile> entry : repository.loadSkinProfiles().entrySet()) {
            profiles.put(entry.getKey(), new CachedSkin(entry.getValue(), 0L));
        }
        if (profiles.isEmpty()) {
            migrateLegacyYaml();
        }
        plugin.getLogger().info("[EnthusiaPlaytime] Loaded " + profiles.size() + " cached player skin profile(s).");
    }

    private void migrateLegacyYaml() throws Exception {
        File yamlFile = new File(plugin.getDataFolder(), "skins.yml");
        if (!yamlFile.exists()) {
            return;
        }

        Map<UUID, SkinProfile> migrated = new ConcurrentHashMap<>();
        YamlConfiguration config = YamlConfiguration.loadConfiguration(yamlFile);
        ConfigurationSection section = config.getConfigurationSection("heads");
        if (section == null) {
            plugin.getLogger().warning("[EnthusiaPlaytime] skins.yml has no heads section; leaving it unchanged for recovery.");
            return;
        }

        for (String key : section.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                ItemStack head = section.getItemStack(key + ".item");
                String name = section.getString(key + ".name");
                PlayerProfile playerProfile = head != null && head.getItemMeta() instanceof SkullMeta meta
                        ? meta.getPlayerProfile()
                        : null;
                migrated.put(uuid, skinProfile(uuid, name, playerProfile));
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().fine("[EnthusiaPlaytime] Skipping invalid UUID in skins.yml: " + key);
            }
        }

        if (migrated.isEmpty()) {
            plugin.getLogger().warning("[EnthusiaPlaytime] No usable player skin entries were found in skins.yml; leaving it unchanged for recovery.");
            return;
        }

        repository.batchUpsertSkinProfiles(new ArrayList<>(migrated.values()));
        for (Map.Entry<UUID, SkinProfile> entry : migrated.entrySet()) {
            profiles.put(entry.getKey(), new CachedSkin(entry.getValue(), 0L));
        }
        File migratedFile = new File(yamlFile.getParentFile(), yamlFile.getName() + ".migrated");
        Files.move(yamlFile.toPath(), migratedFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        plugin.getLogger().info("[EnthusiaPlaytime] Migrated " + migrated.size() + " player skin profile(s) from skins.yml to SQL.");
    }

    private void cacheProfile(UUID uuid, String name, PlayerProfile playerProfile) {
        if (closed) {
            return;
        }
        SkinProfile profile = skinProfile(uuid, name, playerProfile);
        CachedSkin previous = profiles.get(uuid);
        if (previous != null && equivalent(previous.profile(), profile)) {
            return;
        }

        long version = previous == null ? 1L : previous.version() + 1L;
        profiles.put(uuid, new CachedSkin(profile, version));
        dirtyKeys.add(uuid);
        flushAsync();
    }

    private SkinProfile skinProfile(UUID uuid, String name, PlayerProfile playerProfile) {
        String textureValue = null;
        String textureSignature = null;
        if (playerProfile != null) {
            for (ProfileProperty property : playerProfile.getProperties()) {
                if (TEXTURES_PROPERTY.equals(property.getName()) && !isBlank(property.getValue())) {
                    textureValue = property.getValue();
                    textureSignature = property.getSignature();
                    break;
                }
            }
        }
        return new SkinProfile(uuid, textureValue, textureSignature, blankToNull(name), Instant.now());
    }

    private void flushAsync() {
        synchronized (flushLock) {
            if (!flushQueued && !closed) {
                flushQueued = true;
                writerExecutor.execute(this::runFlushLoop);
            }
        }
    }

    private void runFlushLoop() {
        Throwable failure = null;
        try {
            while (true) {
                Map<UUID, CachedSkin> snapshot = snapshotDirtyProfiles();
                if (snapshot.isEmpty()) {
                    break;
                }
                repository.batchUpsertSkinProfiles(profileValues(snapshot));
                clearCleanDirtyKeys(snapshot);
            }
        } catch (Exception ex) {
            failure = ex;
            plugin.getLogger().warning("[EnthusiaPlaytime] Failed to flush player skin profiles: " + ex.getMessage());
        } finally {
            completePendingFlushes(failure);
        }
    }

    private Map<UUID, CachedSkin> snapshotDirtyProfiles() {
        Map<UUID, CachedSkin> snapshot = new ConcurrentHashMap<>();
        for (UUID uuid : dirtyKeys) {
            CachedSkin cachedSkin = profiles.get(uuid);
            if (cachedSkin != null) {
                snapshot.put(uuid, cachedSkin);
            }
        }
        return snapshot;
    }

    private List<SkinProfile> profileValues(Map<UUID, CachedSkin> snapshot) {
        List<SkinProfile> values = new ArrayList<>(snapshot.size());
        for (CachedSkin cachedSkin : snapshot.values()) {
            values.add(cachedSkin.profile());
        }
        return values;
    }

    private void clearCleanDirtyKeys(Map<UUID, CachedSkin> snapshot) {
        for (Map.Entry<UUID, CachedSkin> entry : snapshot.entrySet()) {
            CachedSkin current = profiles.get(entry.getKey());
            if (current != null && current.version() == entry.getValue().version()) {
                dirtyKeys.remove(entry.getKey());
            }
        }
    }

    private void completePendingFlushes(Throwable failure) {
        List<CompletableFuture<Void>> futures;
        synchronized (flushLock) {
            flushQueued = false;
            futures = new ArrayList<>(pendingFlushes);
            pendingFlushes.clear();
            if (failure == null && !dirtyKeys.isEmpty() && !closed) {
                flushQueued = true;
                writerExecutor.execute(this::runFlushLoop);
            }
        }
        for (CompletableFuture<Void> future : futures) {
            if (failure == null) {
                future.complete(null);
            } else {
                future.completeExceptionally(failure);
            }
        }
    }

    private void flushBlocking() {
        if (dirtyKeys.isEmpty() && !flushQueued) {
            return;
        }
        try {
            CompletableFuture<Void> future = new CompletableFuture<>();
            synchronized (flushLock) {
                pendingFlushes.add(future);
                if (!flushQueued) {
                    flushQueued = true;
                    writerExecutor.execute(this::runFlushLoop);
                }
            }
            future.get(15, TimeUnit.SECONDS);
        } catch (Exception ex) {
            plugin.getLogger().warning("[EnthusiaPlaytime] Failed to flush player skin profiles during shutdown: " + ex.getMessage());
        }
    }

    private boolean equivalent(SkinProfile left, SkinProfile right) {
        return same(left.textureValue(), right.textureValue())
                && same(left.textureSignature(), right.textureSignature())
                && same(left.lastKnownName(), right.lastKnownName());
    }

    private boolean same(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String blankToNull(String value) {
        return isBlank(value) ? null : value;
    }

    private static final class SkinWriterThreadFactory implements ThreadFactory {

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "EnthusiaPlaytime-SkinWriter");
            thread.setDaemon(true);
            return thread;
        }
    }
}

package org.enthusia.playtime.service;

import org.bukkit.Bukkit;
import org.enthusia.playtime.PlayTimePlugin;
import org.enthusia.playtime.data.PlaytimeRepository;
import org.enthusia.playtime.data.model.AdminServerStats;
import org.enthusia.playtime.data.model.LeaderboardEntry;
import org.enthusia.playtime.data.model.PublicLeaderboardEntry;
import org.enthusia.playtime.data.model.PlaytimeSnapshot;
import org.enthusia.playtime.data.model.RangeTotals;
import org.enthusia.playtime.util.AsyncWriteQueue;
import org.enthusia.playtime.util.PerformanceCounters;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Level;

public final class PlaytimeReadService {

    private final PlayTimePlugin plugin;
    private final PlaytimeRepository repository;
    private final AsyncWriteQueue writeQueue;
    private final PerformanceCounters counters;
    private final long ttlMillis;

    private final Map<UUID, CacheEntry<Optional<PlaytimeSnapshot>>> lifetimeCache = new ConcurrentHashMap<>();
    private final Map<String, CacheEntry<RangeTotals>> rangeCache = new ConcurrentHashMap<>();
    private final Map<String, CacheEntry<List<LeaderboardEntry>>> leaderboardCache = new ConcurrentHashMap<>();
    private final Map<String, CacheEntry<List<PublicLeaderboardEntry>>> publicLeaderboardCache = new ConcurrentHashMap<>();
    private final Map<String, CacheEntry<AdminServerStats>> adminStatsCache = new ConcurrentHashMap<>();

    public PlaytimeReadService(PlayTimePlugin plugin, PlaytimeRepository repository, AsyncWriteQueue writeQueue, PerformanceCounters counters, int ttlSeconds) {
        this.plugin = plugin;
        this.repository = repository;
        this.writeQueue = writeQueue;
        this.counters = counters;
        this.ttlMillis = Math.max(1, ttlSeconds) * 1000L;
    }

    public Optional<PlaytimeSnapshot> getLifetime(UUID uuid) {
        CacheEntry<Optional<PlaytimeSnapshot>> cached = getCached(lifetimeCache, uuid, Optional.empty(), () -> repository.getLifetime(uuid));

        Optional<PlaytimeSnapshot> base = cached.value();
        RangeTotals pending = writeQueue.getPendingTotals(uuid);
        if (base.isEmpty()) {
            if (pending.totalMinutes <= 0L) {
                return Optional.empty();
            }
            return Optional.of(new PlaytimeSnapshot(pending.activeMinutes, pending.afkMinutes, pending.totalMinutes));
        }

        PlaytimeSnapshot snapshot = base.get();
        return Optional.of(new PlaytimeSnapshot(
                snapshot.activeMinutes + pending.activeMinutes,
                snapshot.afkMinutes + pending.afkMinutes,
                snapshot.totalMinutes + pending.totalMinutes
        ));
    }

    public RangeTotals getRangeTotals(UUID uuid, String rangeId) {
        String normalizedRange = normalizeRange(rangeId);
        String key = uuid + ":" + normalizedRange;
        CacheEntry<RangeTotals> cached = getCached(rangeCache, key, new RangeTotals(0, 0, 0),
                () -> repository.getRangeTotals(uuid, Instant.now(), normalizedRange));

        RangeTotals base = cached.value();
        if (!rangeIncludesPending(normalizedRange)) {
            return base;
        }

        RangeTotals pending = writeQueue.getPendingTotals(uuid);
        return new RangeTotals(
                base.activeMinutes + pending.activeMinutes,
                base.afkMinutes + pending.afkMinutes,
                base.totalMinutes + pending.totalMinutes
        );
    }

    public List<LeaderboardEntry> getLeaderboard(String metric, String range, int limit, int offset) {
        String normalizedMetric = normalizeMetric(metric);
        String normalizedRange = normalizeRange(range);
        String key = normalizedMetric + ":" + normalizedRange + ":" + limit + ":" + offset;
        CacheEntry<List<LeaderboardEntry>> cached = getCached(leaderboardCache, key, List.of(),
                () -> repository.getLeaderboard(normalizedMetric, normalizedRange, Instant.now(), limit, offset));
        return cached.value();
    }

    public List<PublicLeaderboardEntry> getPublicLeaderboard(String metric, String range, int limit) {
        String normalizedMetric = normalizeMetric(metric);
        String normalizedRange = normalizeRange(range);
        int safeLimit = Math.max(1, Math.min(limit, 100));
        String key = normalizedMetric + ":" + normalizedRange + ":" + safeLimit;
        CacheEntry<List<PublicLeaderboardEntry>> cached = getCached(publicLeaderboardCache, key, List.of(),
                () -> repository.getPublicLeaderboard(normalizedMetric, normalizedRange, Instant.now(), safeLimit));
        return cached.value();
    }

    public AdminServerStats getAdminServerStats(String range) {
        String normalizedRange = normalizeRange(range);
        CacheEntry<AdminServerStats> cached = getCached(adminStatsCache, normalizedRange, new AdminServerStats(),
                () -> repository.getAdminServerStats(normalizedRange, Instant.now()));

        AdminServerStats base = copyAdminStats(cached.value());
        if (rangeIncludesPending(normalizedRange)) {
            base.applyPending(writeQueue.getPendingTotalsForServer());
        }
        return base;
    }

    public void invalidatePlayer(UUID uuid) {
        expire(lifetimeCache.get(uuid));
        rangeCache.forEach((key, entry) -> {
            if (key.startsWith(uuid.toString() + ":")) {
                expire(entry);
            }
        });
        expireAll(adminStatsCache);
        expireAll(leaderboardCache);
        expireAll(publicLeaderboardCache);
    }

    public void invalidateAll() {
        expireAll(lifetimeCache);
        expireAll(rangeCache);
        expireAll(leaderboardCache);
        expireAll(publicLeaderboardCache);
        expireAll(adminStatsCache);
    }

    public boolean isLoading() {
        return hasLoading(lifetimeCache) || hasLoading(rangeCache) || hasLoading(leaderboardCache)
                || hasLoading(publicLeaderboardCache) || hasLoading(adminStatsCache);
    }

    public boolean isLeaderboardLoading(String metric, String range, int limit, int offset) {
        String key = normalizeMetric(metric) + ":" + normalizeRange(range) + ":" + limit + ":" + offset;
        CacheEntry<List<LeaderboardEntry>> entry = leaderboardCache.get(key);
        return entry != null && entry.refreshing.get();
    }

    public boolean isRangeLoading(UUID uuid, String range) {
        CacheEntry<RangeTotals> entry = rangeCache.get(uuid + ":" + normalizeRange(range));
        return entry != null && entry.refreshing.get();
    }

    private <K, T> CacheEntry<T> getCached(Map<K, CacheEntry<T>> cache, K key, T emptyValue, Supplier<T> loader) {
        CacheEntry<T> current = cache.get(key);
        if (current == null) {
            CacheEntry<T> created = new CacheEntry<>(emptyValue, 0L);
            CacheEntry<T> existing = cache.putIfAbsent(key, created);
            current = existing == null ? created : existing;
            if (existing == null) {
                counters.dbReadCacheMisses.increment();
            }
        } else if (!current.isExpired(ttlMillis)) {
            counters.dbReadCacheHits.increment();
            return current;
        } else {
            counters.dbReadCacheMisses.increment();
        }

        if (current.isExpired(ttlMillis)) {
            refreshAsync(cache, key, current, loader);
        }
        return current;
    }

    private <K, T> void refreshAsync(Map<K, CacheEntry<T>> cache, K key, CacheEntry<T> entry, Supplier<T> loader) {
        if (!plugin.isEnabled() || !entry.refreshing.compareAndSet(false, true)) {
            return;
        }
        counters.asyncRefreshesStarted.increment();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                T value = loader.get();
                cache.put(key, new CacheEntry<>(value, System.currentTimeMillis()));
                counters.asyncRefreshesCompleted.increment();
            } catch (Exception exception) {
                entry.refreshing.set(false);
                counters.asyncRefreshesFailed.increment();
                plugin.getLogger().log(Level.WARNING, "Failed to refresh playtime display cache for " + key + ".", exception);
            }
        });
    }

    private <T> void expire(CacheEntry<T> entry) {
        if (entry != null) {
            entry.loadedAtMillis = 0L;
        }
    }

    private <K, T> void expireAll(Map<K, CacheEntry<T>> cache) {
        cache.values().forEach(this::expire);
    }

    private <K, T> boolean hasLoading(Map<K, CacheEntry<T>> cache) {
        for (CacheEntry<T> entry : cache.values()) {
            if (entry.refreshing.get()) {
                return true;
            }
        }
        return false;
    }

    private boolean rangeIncludesPending(String range) {
        return range.equals("TODAY") || range.equals("7D") || range.equals("30D") || range.equals("ALL");
    }

    private String normalizeRange(String rangeId) {
        if (rangeId == null) {
            return "ALL";
        }
        return switch (rangeId.toUpperCase()) {
            case "TODAY", "7D", "30D", "ALL" -> rangeId.toUpperCase();
            default -> "ALL";
        };
    }

    private String normalizeMetric(String metricId) {
        if (metricId == null) {
            return "TOTAL";
        }
        return switch (metricId.toUpperCase()) {
            case "ACTIVE", "AFK", "TOTAL" -> metricId.toUpperCase();
            default -> "TOTAL";
        };
    }

    private AdminServerStats copyAdminStats(AdminServerStats source) {
        AdminServerStats copy = new AdminServerStats();
        copy.playersWithPlaytime = source.playersWithPlaytime;
        copy.totalMinutes = source.totalMinutes;
        copy.activeMinutes = source.activeMinutes;
        copy.afkMinutes = source.afkMinutes;
        copy.uniquePlayersJoined = source.uniquePlayersJoined;
        copy.totalJoins = source.totalJoins;
        copy.newPlayers = source.newPlayers;
        copy.returningPlayers = source.returningPlayers;
        copy.retainedNewPlayers = source.retainedNewPlayers;
        copy.avgUniquePlayersPerDay = source.avgUniquePlayersPerDay;
        copy.maxUniquePlayersPerDay = source.maxUniquePlayersPerDay;
        return copy;
    }

    private static final class CacheEntry<T> {
        private final T value;
        private volatile long loadedAtMillis;
        private final AtomicBoolean refreshing = new AtomicBoolean(false);

        private CacheEntry(T value, long loadedAtMillis) {
            this.value = value;
            this.loadedAtMillis = loadedAtMillis;
        }

        private T value() {
            return value;
        }

        private boolean isExpired(long ttlMillis) {
            return System.currentTimeMillis() - loadedAtMillis >= ttlMillis;
        }
    }
}

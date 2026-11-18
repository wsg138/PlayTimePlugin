package org.enthusia.playtime.service;

import org.bukkit.Bukkit;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.enthusia.playtime.PlayTimePlugin;
import org.enthusia.playtime.data.PlaytimeRepository;
import org.enthusia.playtime.data.model.AdminServerStats;
import org.enthusia.playtime.data.model.LeaderboardEntry;
import org.enthusia.playtime.data.model.PlaytimeSnapshot;
import org.enthusia.playtime.data.model.PublicLeaderboardEntry;
import org.enthusia.playtime.data.model.RangeTotals;
import org.enthusia.playtime.util.AsyncWriteQueue;
import org.enthusia.playtime.util.PerformanceCounters;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.function.LongSupplier;
import java.util.logging.Level;

public final class PlaytimeReadService {
    private static final long MIN_PENDING_TOTAL = 1L;
    private static final String RANGE_TODAY = "TODAY";
    private static final String RANGE_7D = "7D";
    private static final String RANGE_30D = "30D";
    private static final String RANGE_ALL = "ALL";
    private static final int MAX_QUERY_LIMIT = 100;
    private static final int DEFAULT_MAX_PAGES = 100;
    private static final int DEFAULT_MAX_CACHE_ENTRIES = 512;
    private static final long PLAYER_GENERATION = -1L;
    private static final long FAILURE_RETRY_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final long WRITE_RETRY_NANOS = TimeUnit.MILLISECONDS.toNanos(100);

    private final PlayTimePlugin plugin;
    private final PlaytimeRepository repository;
    private final AsyncWriteQueue writeQueue;
    private final PerformanceCounters counters;
    private final long ttlMillis;
    private final int maxPages;
    private final int maxCacheEntries;
    private final LongSupplier nanoTime;
    private final AtomicLong sharedGeneration = new AtomicLong();
    private final AtomicLong accessSequence = new AtomicLong();

    private final Map<UUID, CacheEntry<Optional<PlaytimeSnapshot>>> lifetimeCache = new ConcurrentHashMap<>();
    private final Map<String, CacheEntry<RangeTotals>> rangeCache = new ConcurrentHashMap<>();
    private final Map<String, CacheEntry<List<LeaderboardEntry>>> leaderboardCache = new ConcurrentHashMap<>();
    private final Map<String, CacheEntry<List<PublicLeaderboardEntry>>> publicLeaderboardCache = new ConcurrentHashMap<>();
    private final Map<String, CacheEntry<AdminServerStats>> adminStatsCache = new ConcurrentHashMap<>();

    public PlaytimeReadService(PlayTimePlugin plugin, PlaytimeRepository repository, AsyncWriteQueue writeQueue,
                               PerformanceCounters counters, int ttlSeconds) {
        this(plugin, repository, writeQueue, counters, ttlSeconds,
                DEFAULT_MAX_PAGES, DEFAULT_MAX_CACHE_ENTRIES);
    }

    public PlaytimeReadService(PlayTimePlugin plugin, PlaytimeRepository repository, AsyncWriteQueue writeQueue,
                               PerformanceCounters counters, int ttlSeconds,
                               int maxPages, int maxCacheEntries) {
        this(plugin, repository, writeQueue, counters, ttlSeconds, maxPages, maxCacheEntries, System::nanoTime);
    }

    PlaytimeReadService(PlayTimePlugin plugin, PlaytimeRepository repository, AsyncWriteQueue writeQueue,
                        PerformanceCounters counters, int ttlSeconds, int maxPages, int maxCacheEntries,
                        LongSupplier nanoTime) {
        this.plugin = plugin;
        this.repository = repository;
        this.writeQueue = writeQueue;
        this.counters = counters;
        this.ttlMillis = Math.max(1, ttlSeconds) * 1000L;
        this.maxPages = Math.max(1, maxPages);
        this.maxCacheEntries = Math.max(16, maxCacheEntries);
        this.nanoTime = nanoTime;
    }

    public int maxLeaderboardPages() {
        return maxPages;
    }

    public Optional<PlaytimeSnapshot> getLifetime(UUID uuid) {
        return getCached(lifetimeCache, uuid, Optional.<PlaytimeSnapshot>empty(),
                () -> writeQueue.readPlayerDisplaySnapshot(uuid, pending -> loadLifetime(uuid, pending)),
                PLAYER_GENERATION).value();
    }

    private Optional<PlaytimeSnapshot> loadLifetime(UUID uuid, RangeTotals pending) {
        PlaytimeRepository.LifetimeRead read = repository.readLifetimeStrict(uuid);
        if (read.status() == PlaytimeRepository.LifetimeReadStatus.FAILED) {
            throw new IllegalStateException("Lifetime playtime read failed for " + uuid);
        }
        Optional<PlaytimeSnapshot> base = Optional.ofNullable(read.snapshot());
        if (base.isEmpty()) {
            if (pending.totalMinutes < MIN_PENDING_TOTAL) return Optional.empty();
            return Optional.of(new PlaytimeSnapshot(pending.activeMinutes, pending.afkMinutes, pending.totalMinutes));
        }
        PlaytimeSnapshot snapshot = base.get();
        return Optional.of(new PlaytimeSnapshot(
                snapshot.activeMinutes + pending.activeMinutes,
                snapshot.afkMinutes + pending.afkMinutes,
                snapshot.totalMinutes + pending.totalMinutes));
    }

    public RangeTotals getRangeTotals(UUID uuid, String rangeId) {
        String normalizedRange = normalizeRange(rangeId);
        String key = uuid + ":" + normalizedRange;
        return getCached(rangeCache, key, new RangeTotals(0, 0, 0),
                () -> writeQueue.readPlayerDisplaySnapshot(uuid, pending -> {
                    RangeTotals base = query(() -> repository.getRangeTotalsStrict(uuid, Instant.now(), normalizedRange));
                    return new RangeTotals(base.activeMinutes + pending.activeMinutes,
                            base.afkMinutes + pending.afkMinutes, base.totalMinutes + pending.totalMinutes);
                }), PLAYER_GENERATION).value();
    }

    public LeaderboardPage getLeaderboardPage(String metric, String range, int page, int pageSize) {
        int safePageSize = Math.max(1, Math.min(pageSize, MAX_QUERY_LIMIT - 1));
        if (page < 1 || page > maxPages) return new LeaderboardPage(List.of(), false);
        int offset = checkedOffset(page, safePageSize);
        if (offset < 0) return new LeaderboardPage(List.of(), false);
        List<LeaderboardEntry> loaded = getLeaderboard(metric, range, safePageSize + 1, offset);
        boolean hasNext = loaded.size() > safePageSize && page < maxPages;
        List<LeaderboardEntry> rows = loaded.size() > safePageSize
                ? List.copyOf(loaded.subList(0, safePageSize)) : loaded;
        return new LeaderboardPage(rows, hasNext);
    }

    public List<LeaderboardEntry> getLeaderboard(String metric, String range, int limit, int offset) {
        String normalizedMetric = normalizeMetric(metric);
        String normalizedRange = normalizeRange(range);
        int safeLimit = Math.max(1, Math.min(limit, MAX_QUERY_LIMIT));
        int safeOffset = Math.max(0, offset);
        String key = normalizedMetric + ":" + normalizedRange + ":" + safeLimit + ":" + safeOffset;
        long generation = sharedGeneration.get();
        return getCached(leaderboardCache, key, List.of(),
                () -> Optional.of(query(() -> repository.getLeaderboardStrict(normalizedMetric, normalizedRange, Instant.now(), safeLimit, safeOffset))),
                generation).value();
    }

    public List<PublicLeaderboardEntry> getPublicLeaderboard(String metric, String range, int limit) {
        String normalizedMetric = normalizeMetric(metric);
        String normalizedRange = normalizeRange(range);
        int safeLimit = Math.max(1, Math.min(limit, MAX_QUERY_LIMIT));
        String key = normalizedMetric + ":" + normalizedRange + ":" + safeLimit;
        long generation = sharedGeneration.get();
        return getCached(publicLeaderboardCache, key, List.of(),
                () -> Optional.of(query(() -> repository.getPublicLeaderboardStrict(normalizedMetric, normalizedRange, Instant.now(), safeLimit))),
                generation).value();
    }

    public AdminServerStats getAdminServerStats(String range) {
        String normalizedRange = normalizeRange(range);
        long generation = sharedGeneration.get();
        CacheEntry<AdminServerStats> cached = getCached(adminStatsCache, normalizedRange, new AdminServerStats(),
                () -> writeQueue.readServerDisplaySnapshot(pending -> {
                    AdminServerStats stats = query(() -> repository.getAdminServerStatsStrict(normalizedRange, Instant.now()));
                    stats.applyPending(pending);
                    return stats;
                }), generation);
        return copyAdminStats(cached.value());
    }

    private static <T> T query(java.util.concurrent.Callable<T> loader) {
        try {
            return loader.call();
        } catch (Exception exception) {
            throw new IllegalStateException("Playtime display query failed", exception);
        }
    }

    public void invalidatePlayer(UUID uuid) {
        expire(lifetimeCache.get(uuid));
        for (String range : List.of(RANGE_TODAY, RANGE_7D, RANGE_30D, RANGE_ALL)) {
            expire(rangeCache.get(uuid + ":" + range));
        }
        sharedGeneration.incrementAndGet();
    }

    public void invalidateAll() {
        lifetimeCache.values().forEach(this::expire);
        rangeCache.values().forEach(this::expire);
        sharedGeneration.incrementAndGet();
    }

    private void expire(CacheEntry<?> entry) {
        if (entry == null) return;
        synchronized (entry) {
            entry.loadedAtMillis = 0L;
            entry.revision++;
        }
    }

    public boolean isLoading() {
        return hasLoading(lifetimeCache) || hasLoading(rangeCache) || hasLoading(leaderboardCache)
                || hasLoading(publicLeaderboardCache) || hasLoading(adminStatsCache);
    }

    public boolean isLeaderboardLoading(String metric, String range, int limit, int offset) {
        int safeLimit = Math.max(1, Math.min(limit, MAX_QUERY_LIMIT));
        int safeOffset = Math.max(0, offset);
        String key = normalizeMetric(metric) + ":" + normalizeRange(range) + ":" + safeLimit + ":" + safeOffset;
        CacheEntry<List<LeaderboardEntry>> entry = leaderboardCache.get(key);
        return entry != null && entry.refreshing.get();
    }

    public boolean isLeaderboardPageLoading(String metric, String range, int page, int pageSize) {
        int safeSize = Math.max(1, Math.min(pageSize, MAX_QUERY_LIMIT - 1));
        int offset = checkedOffset(page, safeSize);
        return offset >= 0 && isLeaderboardLoading(metric, range, safeSize + 1, offset);
    }

    public boolean isRangeLoading(UUID uuid, String range) {
        CacheEntry<RangeTotals> entry = rangeCache.get(uuid + ":" + normalizeRange(range));
        return entry != null && entry.refreshing.get();
    }

    public boolean isLifetimeLoading(UUID uuid) {
        CacheEntry<Optional<PlaytimeSnapshot>> entry = lifetimeCache.get(uuid);
        return entry != null && (entry.refreshing.get() || entry.waitingForWrite);
    }

    public boolean isLifetimeFailed(UUID uuid) {
        CacheEntry<Optional<PlaytimeSnapshot>> entry = lifetimeCache.get(uuid);
        return entry != null && entry.failed;
    }

    int leaderboardCacheSizeForTesting() {
        return leaderboardCache.size();
    }

    private int checkedOffset(int page, int pageSize) {
        if (page < 1 || page > maxPages) return -1;
        try {
            long offset = Math.multiplyExact((long) page - 1L, (long) pageSize);
            return offset > Integer.MAX_VALUE ? -1 : (int) offset;
        } catch (ArithmeticException overflow) {
            return -1;
        }
    }

    private <K, T> CacheEntry<T> getCached(Map<K, CacheEntry<T>> cache, K key, T emptyValue,
                                            Supplier<Optional<T>> loader, long generation) {
        CacheEntry<T> current = cache.computeIfAbsent(key, ignored -> new CacheEntry<>(emptyValue, 0L, generation));
        synchronized (current) {
            current.lastAccess = accessSequence.incrementAndGet();
            if (current.generation < generation) {
                current.generation = generation;
                current.loadedAtMillis = 0L;
                current.revision++;
            }
        }
        if (!current.isExpired(ttlMillis)) {
            counters.dbReadCacheHits.increment();
            return current;
        } else {
            counters.dbReadCacheMisses.increment();
        }
        if (current.isExpired(ttlMillis)) refreshAsync(cache, key, current, loader, generation);
        evictIfOversize(cache);
        return current;
    }

    private <K, T> void refreshAsync(Map<K, CacheEntry<T>> cache, K key, CacheEntry<T> entry,
                                      Supplier<Optional<T>> loader, long generation) {
        final long revision;
        synchronized (entry) {
            if ((entry.failed || entry.waitingForWrite) && nanoTime.getAsLong() - entry.retryAtNanos < 0) return;
            if (!plugin.isEnabled() || !entry.refreshing.compareAndSet(false, true)) return;
            revision = entry.revision;
        }
        counters.asyncRefreshesStarted.increment();
        try {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    Optional<T> value = loader.get();
                    synchronized (entry) {
                        entry.failed = false;
                        entry.waitingForWrite = value.isEmpty();
                        if (entry.waitingForWrite) entry.retryAtNanos = nanoTime.getAsLong() + WRITE_RETRY_NANOS;
                        // A delayed read must not replace a newer invalidation or resurrect an evicted key.
                        if (value.isPresent() && entry.revision == revision && entry.generation == generation && cache.get(key) == entry
                                && (generation == PLAYER_GENERATION || sharedGeneration.get() == generation)) {
                            entry.cachedValue = value.get();
                            entry.loadedAtMillis = System.currentTimeMillis();
                        }
                    }
                    evictIfOversize(cache);
                    counters.asyncRefreshesCompleted.increment();
                } catch (Exception exception) {
                    synchronized (entry) {
                        entry.retryAtNanos = nanoTime.getAsLong() + FAILURE_RETRY_NANOS;
                        entry.failed = true;
                        entry.waitingForWrite = false;
                    }
                    counters.asyncRefreshesFailed.increment();
                    plugin.getLogger().log(Level.WARNING,
                            "Failed to refresh playtime display cache for " + key + ".", exception);
                } finally {
                    entry.refreshing.set(false);
                }
            });
        } catch (IllegalPluginAccessException exception) {
            entry.refreshing.set(false);
            counters.asyncRefreshesFailed.increment();
            if (plugin.isEnabled()) throw exception;
        }
    }

    private <K, T> void evictIfOversize(Map<K, CacheEntry<T>> cache) {
        while (cache.size() > maxCacheEntries) {
            Map.Entry<K, CacheEntry<T>> oldestEntry = null;
            long oldest = Long.MAX_VALUE;
            for (Map.Entry<K, CacheEntry<T>> candidate : cache.entrySet()) {
                long lastAccess = candidate.getValue().lastAccess;
                if (lastAccess < oldest) {
                    oldest = lastAccess;
                    oldestEntry = candidate;
                }
            }
            if (oldestEntry == null) return;
            CacheEntry<T> entry = oldestEntry.getValue();
            synchronized (entry) {
                if (entry.lastAccess == oldest) cache.remove(oldestEntry.getKey(), entry);
            }
        }
    }

    private <K, T> boolean hasLoading(Map<K, CacheEntry<T>> cache) {
        for (CacheEntry<T> entry : cache.values()) if (entry.refreshing.get()) return true;
        return false;
    }

    private String normalizeRange(String rangeId) {
        if (rangeId == null) return RANGE_ALL;
        return switch (rangeId.toUpperCase(Locale.ROOT)) {
            case RANGE_TODAY, RANGE_7D, RANGE_30D, RANGE_ALL -> rangeId.toUpperCase(Locale.ROOT);
            default -> RANGE_ALL;
        };
    }

    private String normalizeMetric(String metricId) {
        if (metricId == null) return "TOTAL";
        return switch (metricId.toUpperCase(Locale.ROOT)) {
            case "ACTIVE", "AFK", "TOTAL" -> metricId.toUpperCase(Locale.ROOT);
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

    public record LeaderboardPage(List<LeaderboardEntry> rows, boolean hasNext) {
        public LeaderboardPage {
            rows = List.copyOf(rows);
        }
    }

    private static final class CacheEntry<T> {
        private volatile T cachedValue;
        private volatile long generation;
        private long revision;
        private volatile long loadedAtMillis;
        private volatile long lastAccess;
        private final AtomicBoolean refreshing = new AtomicBoolean(false);
        private volatile boolean failed;
        private volatile boolean waitingForWrite;
        private long retryAtNanos;

        private CacheEntry(T value, long loadedAtMillis, long generation) {
            this.cachedValue = value;
            this.loadedAtMillis = loadedAtMillis;
            this.generation = generation;
        }

        private T value() { return cachedValue; }
        private boolean isExpired(long ttlMillis) {
            return System.currentTimeMillis() - loadedAtMillis >= ttlMillis;
        }
    }
}

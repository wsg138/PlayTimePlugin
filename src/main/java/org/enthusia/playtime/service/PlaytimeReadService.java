package org.enthusia.playtime.service;

import org.enthusia.playtime.data.PlaytimeRepository;
import org.enthusia.playtime.data.model.AdminServerStats;
import org.enthusia.playtime.data.model.LeaderboardEntry;
import org.enthusia.playtime.data.model.PublicLeaderboardEntry;
import org.enthusia.playtime.data.model.PlaytimeSnapshot;
import org.enthusia.playtime.data.model.RangeTotals;
import org.enthusia.playtime.util.AsyncWriteQueue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PlaytimeReadService {

    private final PlaytimeRepository repository;
    private final AsyncWriteQueue writeQueue;
    private final long ttlMillis;

    private final Map<UUID, CacheEntry<Optional<PlaytimeSnapshot>>> lifetimeCache = new ConcurrentHashMap<>();
    private final Map<String, CacheEntry<RangeTotals>> rangeCache = new ConcurrentHashMap<>();
    private final Map<String, CacheEntry<List<LeaderboardEntry>>> leaderboardCache = new ConcurrentHashMap<>();
    private final Map<String, CacheEntry<List<PublicLeaderboardEntry>>> publicLeaderboardCache = new ConcurrentHashMap<>();
    private final Map<String, CacheEntry<AdminServerStats>> adminStatsCache = new ConcurrentHashMap<>();

    public PlaytimeReadService(PlaytimeRepository repository, AsyncWriteQueue writeQueue, int ttlSeconds) {
        this.repository = repository;
        this.writeQueue = writeQueue;
        this.ttlMillis = Math.max(1, ttlSeconds) * 1000L;
    }

    public Optional<PlaytimeSnapshot> getLifetime(UUID uuid) {
        CacheEntry<Optional<PlaytimeSnapshot>> cached = lifetimeCache.compute(uuid, (ignored, current) -> {
            if (current != null && !current.isExpired(ttlMillis)) {
                return current;
            }
            return new CacheEntry<>(repository.getLifetime(uuid), System.currentTimeMillis());
        });

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
        CacheEntry<RangeTotals> cached = rangeCache.compute(key, (ignored, current) -> {
            if (current != null && !current.isExpired(ttlMillis)) {
                return current;
            }
            return new CacheEntry<>(repository.getRangeTotals(uuid, Instant.now(), normalizedRange), System.currentTimeMillis());
        });

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
        CacheEntry<List<LeaderboardEntry>> cached = leaderboardCache.compute(key, (ignored, current) -> {
            if (current != null && !current.isExpired(ttlMillis)) {
                return current;
            }
            return new CacheEntry<>(repository.getLeaderboard(normalizedMetric, normalizedRange, Instant.now(), limit, offset),
                    System.currentTimeMillis());
        });
        return cached.value();
    }

    public List<PublicLeaderboardEntry> getPublicLeaderboard(String metric, String range, int limit) {
        String normalizedMetric = normalizeMetric(metric);
        String normalizedRange = normalizeRange(range);
        int safeLimit = Math.max(1, Math.min(limit, 100));
        String key = normalizedMetric + ":" + normalizedRange + ":" + safeLimit;
        CacheEntry<List<PublicLeaderboardEntry>> cached = publicLeaderboardCache.compute(key, (ignored, current) -> {
            if (current != null && !current.isExpired(ttlMillis)) {
                return current;
            }
            return new CacheEntry<>(repository.getPublicLeaderboard(normalizedMetric, normalizedRange, Instant.now(), safeLimit),
                    System.currentTimeMillis());
        });
        return cached.value();
    }

    public AdminServerStats getAdminServerStats(String range) {
        String normalizedRange = normalizeRange(range);
        CacheEntry<AdminServerStats> cached = adminStatsCache.compute(normalizedRange, (ignored, current) -> {
            if (current != null && !current.isExpired(ttlMillis)) {
                return current;
            }
            return new CacheEntry<>(repository.getAdminServerStats(normalizedRange, Instant.now()), System.currentTimeMillis());
        });

        AdminServerStats base = copyAdminStats(cached.value());
        if (rangeIncludesPending(normalizedRange)) {
            base.applyPending(writeQueue.getPendingTotalsForServer());
        }
        return base;
    }

    public void invalidatePlayer(UUID uuid) {
        lifetimeCache.remove(uuid);
        rangeCache.keySet().removeIf(key -> key.startsWith(uuid.toString() + ":"));
        adminStatsCache.clear();
        leaderboardCache.clear();
        publicLeaderboardCache.clear();
    }

    public void invalidateAll() {
        lifetimeCache.clear();
        rangeCache.clear();
        leaderboardCache.clear();
        publicLeaderboardCache.clear();
        adminStatsCache.clear();
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

    private record CacheEntry<T>(T value, long loadedAtMillis) {
        private boolean isExpired(long ttlMillis) {
            return System.currentTimeMillis() - loadedAtMillis >= ttlMillis;
        }
    }
}

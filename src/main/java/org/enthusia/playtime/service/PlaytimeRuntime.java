package org.enthusia.playtime.service;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.scheduler.BukkitTask;
import org.enthusia.playtime.PlayTimePlugin;
import org.enthusia.playtime.activity.ActivityState;
import org.enthusia.playtime.activity.ActivityTracker;
import org.enthusia.playtime.activity.SessionManager;
import org.enthusia.playtime.api.PlaytimeService;
import org.enthusia.playtime.api.impl.PlaytimeServiceImpl;
import org.enthusia.playtime.config.PlaytimeConfig;
import org.enthusia.playtime.data.DatabaseProvider;
import org.enthusia.playtime.data.PlaytimeRepository;
import org.enthusia.playtime.data.model.PlayerProfile;
import org.enthusia.playtime.event.PlayerPlaytimeTickEvent;
import org.enthusia.playtime.leaderboard.LeaderboardExportService;
import org.enthusia.playtime.skin.HeadCache;
import org.enthusia.playtime.util.AsyncWriteQueue;
import org.enthusia.playtime.util.PerformanceCounters;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public final class PlaytimeRuntime implements AutoCloseable {

    private final PlayTimePlugin plugin;
    private final PlaytimeConfig runtimeConfig;
    private final DatabaseProvider databaseProvider;
    private final PlaytimeRepository playtimeRepository;
    private final SessionManager sessions;
    private final ActivityTracker activities;
    private final AsyncWriteQueue storageQueue;
    private final PlaytimeReadService reads;
    private final HeadCache playerHeadCache;
    private final LeaderboardExportService exportService;
    private final PerformanceCounters performanceCounters = new PerformanceCounters();
    private final AutoCloseable planHook;
    private final PlaytimeServiceImpl serviceApi;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Map<UUID, Integer> suspiciousStreakMinutes = new ConcurrentHashMap<>();
    private final Map<UUID, Long> processedSuspicionResetMarkers = new ConcurrentHashMap<>();
    private final Map<UUID, JoinDecision> recentJoinDecisions = new ConcurrentHashMap<>();

    private BukkitTask minuteTickTask;
    private BukkitTask joinPurgeTask;
    private BukkitTask actionBarTask;
    private BukkitTask auditTask;
    private BukkitTask performanceLogTask;
    private BukkitTask initialLeaderboardExportTask;
    private BukkitTask leaderboardExportTask;
    private volatile int auditBatchSize;
    private volatile int auditRemaining;
    private volatile long nextAuditAtMillis;

    public PlaytimeRuntime(PlayTimePlugin plugin, PlaytimeConfig config, RuntimeState previousState) throws Exception {
        this.plugin = plugin;
        this.runtimeConfig = config;
        this.databaseProvider = new DatabaseProvider(plugin, config);
        this.databaseProvider.init(config.getStorageType());
        this.playtimeRepository = new PlaytimeRepository(plugin, databaseProvider, config);
        this.playtimeRepository.initSchema();
        this.sessions = new SessionManager(previousState == null ? Map.of() : previousState.sessionStarts());
        this.activities = new ActivityTracker(config, sessions, previousState == null ? Map.of() : previousState.activitySnapshots(), performanceCounters);

        this.playerHeadCache = new HeadCache(plugin, performanceCounters);
        this.storageQueue = new AsyncWriteQueue(plugin, playtimeRepository, performanceCounters, config.getFlushIntervalTicks());
        this.storageQueue.start();
        this.reads = new PlaytimeReadService(plugin, playtimeRepository, storageQueue, performanceCounters, config.leaderboards().cacheTtlSeconds());
        this.exportService = new LeaderboardExportService(plugin, playtimeRepository, config.leaderboards().export(), performanceCounters);
        this.planHook = createPlanHook();
        this.serviceApi = new PlaytimeServiceImpl(reads, playtimeRepository, activities, sessions);

        Bukkit.getPluginManager().registerEvents(activities, plugin);
        Bukkit.getServicesManager().register(PlaytimeService.class, serviceApi, plugin, org.bukkit.plugin.ServicePriority.Normal);

        long nowMillis = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            activities.bootstrapPlayer(player, nowMillis);
            playerHeadCache.updateHead(player);
            storageQueue.enqueuePlayerProfile(profileFor(player, Instant.now()));
        }

        startMinuteTickTask();
        startJoinPurgeTask();
        startActionBarTask();
        startAuditTask();
        startPerformanceLogTask();
        startLeaderboardExportTask();
    }

    public PlaytimeConfig config() {
        return runtimeConfig;
    }

    public PlaytimeRepository repository() {
        return playtimeRepository;
    }

    public SessionManager sessionManager() {
        return sessions;
    }

    public ActivityTracker activityTracker() {
        return activities;
    }

    public AsyncWriteQueue writeQueue() {
        return storageQueue;
    }

    public PlaytimeReadService readService() {
        return reads;
    }

    public HeadCache headCache() {
        return playerHeadCache;
    }

    public PlaytimeService playtimeService() {
        return serviceApi;
    }

    public LeaderboardExportService leaderboardExportService() {
        return exportService;
    }

    public PerformanceCounters counters() {
        return performanceCounters;
    }

    public String performanceSummary() {
        String auditStatus = runtimeConfig.playtimeAudit().enabled()
                ? "audit queue=" + auditRemaining + "/" + auditBatchSize
                + ", next audit in=" + Math.max(0L, nextAuditAtMillis - System.currentTimeMillis()) / 1000L + "s"
                : "audit disabled";
        String exportStatus = runtimeConfig.leaderboards().export().enabled()
                ? "export interval=" + runtimeConfig.leaderboards().export().intervalSeconds() + "s, R2="
                + (runtimeConfig.leaderboards().export().r2().enabled() ? "enabled" : "disabled")
                : "export disabled";
        return performanceCounters.summary() + ", " + auditStatus + ", " + exportStatus;
    }

    public boolean isKnownPlayer(UUID uuid) {
        return playtimeRepository.hasLifetimeRecord(uuid);
    }

    public boolean handleJoinRecorded(Player player, Instant joinedAt) {
        UUID uuid = player.getUniqueId();
        boolean firstKnownJoin = !playtimeRepository.hasLifetimeRecord(uuid);
        int uniqueNumber = firstKnownJoin ? playtimeRepository.countKnownPlayers() + 1 : 0;
        recentJoinDecisions.put(uuid, new JoinDecision(firstKnownJoin, uniqueNumber, System.currentTimeMillis()));
        storageQueue.enqueuePlayerProfile(profileFor(player, joinedAt));
        storageQueue.enqueueJoin(uuid, joinedAt);
        reads.invalidateAll();
        return firstKnownJoin;
    }

    public JoinDecision consumeJoinDecision(UUID uuid) {
        JoinDecision decision = recentJoinDecisions.remove(uuid);
        if (decision == null || System.currentTimeMillis() - decision.createdAtMillis() > 30_000L) {
            return new JoinDecision(!isKnownPlayer(uuid), playtimeRepository.countKnownPlayers() + 1, System.currentTimeMillis());
        }
        return decision;
    }

    public void handleQuitRecorded(UUID uuid, Instant quitAt) {
        resetSuspiciousTracking(uuid);
        recentJoinDecisions.remove(uuid);
        playtimeRepository.recordLastSeenAsync(plugin, uuid, quitAt);
    }

    public void noteHead(Player player) {
        playerHeadCache.updateHead(player);
    }

    public RuntimeState snapshotState() {
        return new RuntimeState(
                new HashMap<>(sessions.snapshot()),
                new HashMap<>(activities.snapshot())
        );
    }

    private void startMinuteTickTask() {
        minuteTickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::runMinuteTick, 20L * 60L, 20L * 60L);
    }

    private void startJoinPurgeTask() {
        long period = 20L * 60L * 60L;
        joinPurgeTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            try {
                playtimeRepository.purgeOldJoins(runtimeConfig.getJoinRetentionDays());
                playtimeRepository.purgeOldHourlyAggregates(runtimeConfig.getHourlyAnalyticsRetentionDays());
            } catch (Exception exception) {
                plugin.getLogger().log(Level.WARNING, "Failed to purge old playtime analytics rows.", exception);
            }
        }, period, period);
    }

    private void startActionBarTask() {
        if (!runtimeConfig.actionBar().enabled()) {
            return;
        }
        actionBarTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long nowMillis = System.currentTimeMillis();
            for (Player player : Bukkit.getOnlinePlayers()) {
                String message = activities.actionBarMessage(activities.getState(player.getUniqueId(), nowMillis));
                if (message != null && !message.isBlank()) {
                    player.sendActionBar(Component.text(message));
                }
            }
        }, 20L, 20L);
    }

    private void startAuditTask() {
        if (!runtimeConfig.playtimeAudit().enabled()) {
            return;
        }
        long periodTicks = Math.max(20L, runtimeConfig.playtimeAudit().intervalMinutes() * 60L * 20L);
        auditTask = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            private int cursor;
            private Player[] batch = new Player[0];

            @Override
            public void run() {
                long nowMillis = System.currentTimeMillis();
                if (cursor >= batch.length) {
                    if (nowMillis < nextAuditAtMillis) {
                        return;
                    }
                    batch = Bukkit.getOnlinePlayers().toArray(Player[]::new);
                    cursor = 0;
                    nextAuditAtMillis = nowMillis + runtimeConfig.playtimeAudit().intervalMinutes() * 60_000L;
                    auditBatchSize = batch.length;
                }
                if (batch.length == 0) {
                    auditRemaining = 0;
                    return;
                }
                auditRemaining = batch.length - cursor;
                int max = Math.min(runtimeConfig.playtimeAudit().maxPlayersPerTick(), batch.length - cursor);
                for (int i = 0; i < max; i++) {
                    Player player = batch[cursor++];
                    if (!player.isOnline()) {
                        continue;
                    }
                    auditPlayer(player, nowMillis);
                }
                auditRemaining = batch.length - cursor;
            }
        }, periodTicks, 1L);
        performanceCounters.reloadTaskRestarts.increment();
    }

    private void auditPlayer(Player player, long nowMillis) {
        performanceCounters.backstopScans.increment();
        boolean repaired = false;
        boolean repairMode = runtimeConfig.playtimeAudit().repairMode();
        if (repairMode && repairMissingSession(player, nowMillis)) {
            repaired = true;
        }
        if (repairMode && activities.ensureTracked(player, nowMillis)) {
            repaired = true;
        }
        if (repairMode) {
            storageQueue.enqueuePlayerProfile(profileFor(player, Instant.now()));
            playerHeadCache.updateHeadDebounced(player);
        }
        if (reads.isLoading()) {
            reads.invalidatePlayer(player.getUniqueId());
            repaired = true;
        }
        if (repaired) {
            performanceCounters.backstopRepairs.increment();
            if (runtimeConfig.playtimeAudit().debugLogRepairs()) {
                plugin.getLogger().info("Playtime audit repaired cached state for " + player.getName() + ".");
            }
        }
    }

    private boolean repairMissingSession(Player player, long nowMillis) {
        if (sessions.getCurrentSessionMillis(player.getUniqueId(), nowMillis) > 0L) {
            return false;
        }
        sessions.handleJoin(player.getUniqueId(), nowMillis);
        return true;
    }

    private void startPerformanceLogTask() {
        if (!runtimeConfig.debug().performance().enabled()) {
            return;
        }
        long periodTicks = Math.max(20L, runtimeConfig.debug().performance().logIntervalSeconds() * 20L);
        performanceLogTask = Bukkit.getScheduler().runTaskTimer(plugin,
                () -> plugin.getLogger().info("Playtime performance counters: " + performanceSummary()),
                periodTicks, periodTicks);
        performanceCounters.reloadTaskRestarts.increment();
    }

    private void runMinuteTick() {
        long nowMillis = System.currentTimeMillis();

        for (Player player : Bukkit.getOnlinePlayers()) {
            ActivityState state = activities.getState(player.getUniqueId(), nowMillis);
            int suspiciousStreak = updateSuspiciousStreak(player.getUniqueId(), state);
            MinuteCredit credit = minuteCredit(state);

            PlayerPlaytimeTickEvent event = new PlayerPlaytimeTickEvent(player, state, credit.activeMinutes(), credit.afkMinutes());
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled()) {
                continue;
            }

            logSuspiciousThreshold(player, state, suspiciousStreak);

            if (event.getActiveMinutes() <= 0 && event.getAfkMinutes() <= 0) {
                continue;
            }

            storageQueue.enqueueMinute(player.getUniqueId(), event.getActiveMinutes(), event.getAfkMinutes());
            reads.invalidatePlayer(player.getUniqueId());
        }
    }

    private MinuteCredit minuteCredit(ActivityState state) {
        return switch (state) {
            case ACTIVE -> new MinuteCredit(1, 0);
            case IDLE, AFK, SUSPICIOUS -> new MinuteCredit(0, 1);
            default -> new MinuteCredit(0, 0);
        };
    }

    private void logSuspiciousThreshold(Player player, ActivityState state, int suspiciousStreak) {
        if (!runtimeConfig.debug().enabled() || !runtimeConfig.debug().logSuspicious()) {
            return;
        }
        if (state != ActivityState.SUSPICIOUS || suspiciousStreak != runtimeConfig.sampling().suspicion().maxCountedConsecutiveMinutes()) {
            return;
        }
        plugin.getLogger().info("Suspicious activity threshold reached for " + player.getName()
                + "; suspicious minutes are being counted as AFK until the player returns to a non-suspicious state.");
    }

    private int updateSuspiciousStreak(UUID uuid, ActivityState state) {
        if (state != ActivityState.SUSPICIOUS) {
            resetSuspiciousTracking(uuid);
            return 0;
        }
        long resetMarker = activities.getSuspiciousResetMarker(uuid);
        Long processedMarker = processedSuspicionResetMarkers.get(uuid);
        if (processedMarker == null || resetMarker > processedMarker) {
            processedSuspicionResetMarkers.put(uuid, resetMarker);
            suspiciousStreakMinutes.put(uuid, 1);
            return 1;
        }
        return suspiciousStreakMinutes.merge(uuid, 1, Integer::sum);
    }

    public void resetSuspiciousTracking(UUID uuid) {
        suspiciousStreakMinutes.remove(uuid);
        processedSuspicionResetMarkers.remove(uuid);
    }

    @Override
    public void close() {
        close(false);
    }

    public void close(boolean reloadClose) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        cancelRuntimeTasks();
        unregisterRuntimeServices();
        persistOnlinePlayersForShutdown();
        closeStorageAndExport(reloadClose);
        playerHeadCache.save();
        databaseProvider.shutdown();
    }

    private void cancelRuntimeTasks() {
        if (minuteTickTask != null) {
            minuteTickTask.cancel();
        }
        if (joinPurgeTask != null) {
            joinPurgeTask.cancel();
        }
        if (actionBarTask != null) {
            actionBarTask.cancel();
        }
        if (auditTask != null) {
            auditTask.cancel();
        }
        if (performanceLogTask != null) {
            performanceLogTask.cancel();
        }
        if (initialLeaderboardExportTask != null) {
            initialLeaderboardExportTask.cancel();
        }
        if (leaderboardExportTask != null) {
            leaderboardExportTask.cancel();
        }
    }

    private void unregisterRuntimeServices() {
        Bukkit.getServicesManager().unregister(serviceApi);
        try {
            planHook.close();
        } catch (Exception exception) {
            plugin.getLogger().log(Level.FINE, "Failed to close Plan analytics integration.", exception);
        }
        HandlerList.unregisterAll(activities);
    }

    private void persistOnlinePlayersForShutdown() {
        Instant now = Instant.now();
        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
                playtimeRepository.recordLastSeen(player.getUniqueId(), now);
                storageQueue.enqueuePlayerProfileForShutdown(profileFor(player, now));
            } catch (Exception exception) {
                plugin.getLogger().log(Level.WARNING, "Failed to persist last seen during shutdown for " + player.getName(), exception);
            }
            resetSuspiciousTracking(player.getUniqueId());
        }
    }

    private void closeStorageAndExport(boolean reloadClose) {
        storageQueue.close(runtimeConfig.leaderboards().export().shutdownTimeoutSeconds());
        boolean exportOnClose = reloadClose
                ? runtimeConfig.leaderboards().export().runOnReloadClose()
                : runtimeConfig.leaderboards().export().runOnDisable();
        if (exportOnClose) {
            exportService.exportAll();
        }
    }

    private AutoCloseable createPlanHook() {
        if (!runtimeConfig.isPlanIntegrationEnabled() || Bukkit.getPluginManager().getPlugin("Plan") == null) {
            return () -> {
            };
        }

        try {
            org.enthusia.playtime.plan.PlanHook hook = new org.enthusia.playtime.plan.PlanHook(plugin, playtimeRepository, reads, sessions, runtimeConfig);
            hook.hook();
            return hook;
        } catch (NoClassDefFoundError ignored) {
            return () -> {
            };
        } catch (Exception exception) {
            plugin.getLogger().log(Level.WARNING, "Failed to initialize Plan analytics integration.", exception);
            return () -> {
            };
        }
    }

    private void startLeaderboardExportTask() {
        PlaytimeConfig.LeaderboardExport exportConfig = runtimeConfig.leaderboards().export();
        if (!exportConfig.enabled()) {
            return;
        }
        long periodTicks = Math.max(20L, exportConfig.intervalSeconds() * 20L);
        initialLeaderboardExportTask = Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            storageQueue.flushNow();
            exportService.exportAll();
        }, 20L);
        leaderboardExportTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            storageQueue.flushNow();
            exportService.exportAll();
        }, periodTicks, periodTicks);
    }

    private PlayerProfile profileFor(Player player, Instant seenAt) {
        String displayName = PlainTextComponentSerializer.plainText().serialize(player.displayName());
        String storedDisplayName = displayName.equals(player.getName()) ? null : displayName;
        return new PlayerProfile(player.getUniqueId(), player.getName(), storedDisplayName, seenAt);
    }

    public record RuntimeState(Map<UUID, Long> sessionStarts,
                               Map<UUID, ActivityTracker.ActivitySnapshot> activitySnapshots) {
    }

    public record JoinDecision(boolean firstKnownJoin, int uniqueNumber, long createdAtMillis) {
    }

    private record MinuteCredit(int activeMinutes, int afkMinutes) {
    }
}

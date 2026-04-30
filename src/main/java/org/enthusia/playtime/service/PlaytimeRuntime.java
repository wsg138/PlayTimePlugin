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

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class PlaytimeRuntime implements AutoCloseable {

    private final PlayTimePlugin plugin;
    private final PlaytimeConfig config;
    private final DatabaseProvider databaseProvider;
    private final PlaytimeRepository repository;
    private final SessionManager sessionManager;
    private final ActivityTracker activityTracker;
    private final AsyncWriteQueue writeQueue;
    private final PlaytimeReadService readService;
    private final HeadCache headCache;
    private final LeaderboardExportService leaderboardExportService;
    private final AutoCloseable planHook;
    private final PlaytimeServiceImpl playtimeService;
    private final Map<UUID, Integer> suspiciousStreakMinutes = new ConcurrentHashMap<>();
    private final Map<UUID, Long> processedSuspicionResetMarkers = new ConcurrentHashMap<>();

    private BukkitTask minuteTickTask;
    private BukkitTask joinPurgeTask;
    private BukkitTask actionBarTask;
    private BukkitTask initialLeaderboardExportTask;
    private BukkitTask leaderboardExportTask;

    public PlaytimeRuntime(PlayTimePlugin plugin, PlaytimeConfig config, RuntimeState previousState) throws Exception {
        this.plugin = plugin;
        this.config = config;
        this.databaseProvider = new DatabaseProvider(plugin, config);
        this.databaseProvider.init(config.getStorageType());
        this.repository = new PlaytimeRepository(plugin, databaseProvider, config);
        this.repository.initSchema();
        this.sessionManager = new SessionManager(previousState == null ? Map.of() : previousState.sessionStarts());
        this.activityTracker = new ActivityTracker(plugin, config, sessionManager, previousState == null ? Map.of() : previousState.activitySnapshots());

        this.headCache = new HeadCache(plugin);
        this.writeQueue = new AsyncWriteQueue(plugin, repository, config.getFlushIntervalTicks());
        this.writeQueue.start();
        this.readService = new PlaytimeReadService(repository, writeQueue, config.leaderboards().cacheTtlSeconds());
        this.leaderboardExportService = new LeaderboardExportService(plugin, repository, config.leaderboards().export());
        this.planHook = createPlanHook();
        this.playtimeService = new PlaytimeServiceImpl(readService, repository, activityTracker, sessionManager);

        Bukkit.getPluginManager().registerEvents(activityTracker, plugin);
        Bukkit.getServicesManager().register(PlaytimeService.class, playtimeService, plugin, org.bukkit.plugin.ServicePriority.Normal);

        long nowMillis = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            activityTracker.bootstrapPlayer(player, nowMillis);
            headCache.updateHead(player);
            writeQueue.enqueuePlayerProfile(profileFor(player, Instant.now()));
        }

        startMinuteTickTask();
        startJoinPurgeTask();
        startActionBarTask();
        startLeaderboardExportTask();
    }

    public PlaytimeConfig config() {
        return config;
    }

    public PlaytimeRepository repository() {
        return repository;
    }

    public SessionManager sessionManager() {
        return sessionManager;
    }

    public ActivityTracker activityTracker() {
        return activityTracker;
    }

    public AsyncWriteQueue writeQueue() {
        return writeQueue;
    }

    public PlaytimeReadService readService() {
        return readService;
    }

    public HeadCache headCache() {
        return headCache;
    }

    public PlaytimeService playtimeService() {
        return playtimeService;
    }

    public LeaderboardExportService leaderboardExportService() {
        return leaderboardExportService;
    }

    public boolean isKnownPlayer(UUID uuid) {
        return repository.hasLifetimeRecord(uuid);
    }

    public boolean handleJoinRecorded(Player player, Instant joinedAt) {
        UUID uuid = player.getUniqueId();
        boolean firstKnownJoin = !repository.hasLifetimeRecord(uuid);
        writeQueue.enqueuePlayerProfile(profileFor(player, joinedAt));
        writeQueue.enqueueJoin(uuid, joinedAt);
        readService.invalidateAll();
        return firstKnownJoin;
    }

    public void handleQuitRecorded(UUID uuid, Instant quitAt) {
        resetSuspiciousTracking(uuid);
        repository.recordLastSeenAsync(plugin, uuid, quitAt);
    }

    public void noteHead(Player player) {
        headCache.updateHead(player);
    }

    public RuntimeState snapshotState() {
        return new RuntimeState(
                new HashMap<>(sessionManager.snapshot()),
                new HashMap<>(activityTracker.snapshot())
        );
    }

    private void startMinuteTickTask() {
        minuteTickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::runMinuteTick, 20L * 60L, 20L * 60L);
    }

    private void startJoinPurgeTask() {
        long period = 20L * 60L * 60L;
        joinPurgeTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            try {
                repository.purgeOldJoins(config.getJoinRetentionDays());
                repository.purgeOldHourlyAggregates(config.getHourlyAnalyticsRetentionDays());
            } catch (Exception exception) {
                plugin.getLogger().log(Level.WARNING, "Failed to purge old playtime analytics rows.", exception);
            }
        }, period, period);
    }

    private void startActionBarTask() {
        if (!config.actionBar().enabled()) {
            return;
        }
        actionBarTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long nowMillis = System.currentTimeMillis();
            for (Player player : Bukkit.getOnlinePlayers()) {
                String message = activityTracker.actionBarMessage(activityTracker.getState(player.getUniqueId(), nowMillis));
                if (message != null && !message.isBlank()) {
                    player.sendActionBar(Component.text(message));
                }
            }
        }, 20L, 20L);
    }

    private void runMinuteTick() {
        Instant now = Instant.now();
        long nowMillis = System.currentTimeMillis();

        for (Player player : Bukkit.getOnlinePlayers()) {
            ActivityState state = activityTracker.getState(player.getUniqueId(), nowMillis);
            int suspiciousStreak = updateSuspiciousStreak(player.getUniqueId(), state);

            int activeMinutes = 0;
            int afkMinutes = 0;

            switch (state) {
                case ACTIVE -> activeMinutes = 1;
                case IDLE, AFK -> afkMinutes = 1;
                case SUSPICIOUS -> {
                    if (suspiciousStreak <= config.sampling().suspicion().maxCountedConsecutiveMinutes()) {
                        activeMinutes = 1;
                    }
                }
            }

            PlayerPlaytimeTickEvent event = new PlayerPlaytimeTickEvent(player, state, activeMinutes, afkMinutes);
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled()) {
                continue;
            }

            if (config.debug().enabled() && config.debug().logSuspicious() && state == ActivityState.SUSPICIOUS
                    && suspiciousStreak == config.sampling().suspicion().maxCountedConsecutiveMinutes()) {
                plugin.getLogger().info("Suspicious activity threshold reached for " + player.getName()
                        + "; further suspicious minutes will not count until the player returns to a non-suspicious state.");
            }

            if (event.getActiveMinutes() <= 0 && event.getAfkMinutes() <= 0) {
                continue;
            }

            writeQueue.enqueueMinute(player.getUniqueId(), event.getActiveMinutes(), event.getAfkMinutes());
            readService.invalidatePlayer(player.getUniqueId());
        }
    }

    private int updateSuspiciousStreak(UUID uuid, ActivityState state) {
        if (state != ActivityState.SUSPICIOUS) {
            resetSuspiciousTracking(uuid);
            return 0;
        }
        long resetMarker = activityTracker.getSuspiciousResetMarker(uuid);
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
        if (minuteTickTask != null) {
            minuteTickTask.cancel();
            minuteTickTask = null;
        }
        if (joinPurgeTask != null) {
            joinPurgeTask.cancel();
            joinPurgeTask = null;
        }
        if (actionBarTask != null) {
            actionBarTask.cancel();
            actionBarTask = null;
        }
        if (initialLeaderboardExportTask != null) {
            initialLeaderboardExportTask.cancel();
            initialLeaderboardExportTask = null;
        }
        if (leaderboardExportTask != null) {
            leaderboardExportTask.cancel();
            leaderboardExportTask = null;
        }

        Bukkit.getServicesManager().unregister(playtimeService);
        try {
            planHook.close();
        } catch (Exception exception) {
            plugin.getLogger().log(Level.FINE, "Failed to close Plan analytics integration.", exception);
        }
        HandlerList.unregisterAll(activityTracker);

        Instant now = Instant.now();
        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
                repository.recordLastSeen(player.getUniqueId(), now);
                writeQueue.enqueuePlayerProfile(profileFor(player, now));
            } catch (Exception exception) {
                plugin.getLogger().log(Level.WARNING, "Failed to persist last seen during shutdown for " + player.getName(), exception);
            }
            resetSuspiciousTracking(player.getUniqueId());
        }

        writeQueue.close();
        leaderboardExportService.exportAll();
        headCache.save();
        databaseProvider.shutdown();
    }

    private AutoCloseable createPlanHook() {
        if (!config.isPlanIntegrationEnabled() || Bukkit.getPluginManager().getPlugin("Plan") == null) {
            return () -> {
            };
        }

        try {
            org.enthusia.playtime.plan.PlanHook hook = new org.enthusia.playtime.plan.PlanHook(plugin, repository, readService, sessionManager, config);
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
        PlaytimeConfig.LeaderboardExport exportConfig = config.leaderboards().export();
        if (!exportConfig.enabled()) {
            return;
        }
        long periodTicks = Math.max(20L, exportConfig.intervalSeconds() * 20L);
        initialLeaderboardExportTask = Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            writeQueue.flushNow();
            leaderboardExportService.exportAll();
        }, 20L);
        leaderboardExportTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            writeQueue.flushNow();
            leaderboardExportService.exportAll();
        }, periodTicks, periodTicks);
    }

    private PlayerProfile profileFor(Player player, Instant seenAt) {
        String displayName = PlainTextComponentSerializer.plainText().serialize(player.displayName());
        if (displayName.equals(player.getName())) {
            displayName = null;
        }
        return new PlayerProfile(player.getUniqueId(), player.getName(), displayName, seenAt);
    }

    public record RuntimeState(Map<UUID, Long> sessionStarts,
                               Map<UUID, ActivityTracker.ActivitySnapshot> activitySnapshots) {
    }
}

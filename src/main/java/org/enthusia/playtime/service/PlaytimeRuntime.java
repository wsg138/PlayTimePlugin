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
import org.enthusia.playtime.data.PlaytimeRepository.LifetimeRead;
import org.enthusia.playtime.data.PlaytimeRepository.LifetimeReadStatus;
import org.enthusia.playtime.data.model.PlayerProfile;
import org.enthusia.playtime.event.PlayerPlaytimeTickEvent;
import org.enthusia.playtime.leaderboard.LeaderboardExportService;
import org.enthusia.playtime.skin.HeadCache;
import org.enthusia.playtime.util.AsyncWriteQueue;
import org.enthusia.playtime.util.PerformanceCounters;
import org.enthusia.playtime.util.NumeralTierCatalog;
import org.enthusia.playtime.util.TierProgressTracker;
import org.enthusia.playtime.util.ShutdownRecoveryJournal;
import org.bukkit.ChatColor;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.function.Consumer;

public final class PlaytimeRuntime implements AutoCloseable {
    enum CloseStage {
        TASKS, BUKKIT_SERVICE, PLAN, LISTENERS, HEAD_CACHE, QUEUE_AND_EXPORT, DATABASE
    }
    private static volatile Consumer<CloseStage> closeProbe = ignored -> { };
    enum TierReadStage { AFTER_CUTOFF_BEFORE_SQL, AFTER_SQL, MAIN_COMPLETION_SCHEDULED }
    private static volatile Consumer<TierReadStage> tierReadProbe = ignored -> { };
    private static volatile Consumer<String> announcementProbe = ignored -> { };
    private static volatile Consumer<Runnable> tierMainExecutor;
    enum CreationStage {
        DATABASE_ALLOCATED, DATABASE_INITIALIZED, REPOSITORY_CREATED, SCHEMA_INITIALIZED,
        HEAD_CACHE_CREATED, WRITE_QUEUE_CREATED, READ_SERVICE_CREATED, EXPORT_SERVICE_CREATED,
        QUEUE_STARTED, PLAN_HOOK_CREATED, LISTENER_REGISTERED, SERVICE_REGISTERED,
        ONLINE_PLAYERS_BOOTSTRAPPED, MINUTE_TASK_SCHEDULED, JOIN_PURGE_TASK_SCHEDULED,
        ACTION_BAR_TASK_SCHEDULED, AUDIT_TASK_SCHEDULED, PERFORMANCE_TASK_SCHEDULED,
        LEADERBOARD_TASKS_SCHEDULED
    }
    private static volatile Consumer<CreationStage> creationProbe = ignored -> { };

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
    private AutoCloseable planHook = () -> { };
    private final PlaytimeServiceImpl serviceApi;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean databaseCloseDeferred = new AtomicBoolean(false);
    private final ShutdownRecoveryJournal recoveryJournal;
    private final AtomicBoolean tierProgressHandedOff = new AtomicBoolean(false);
    private final AtomicBoolean handoffPrepared = new AtomicBoolean(false);
    private final Map<UUID, Integer> suspiciousStreakMinutes = new ConcurrentHashMap<>();
    private final Map<UUID, Long> processedSuspicionResetMarkers = new ConcurrentHashMap<>();
    private final Map<UUID, JoinDecision> recentJoinDecisions = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> tierInitializationRetries = new ConcurrentHashMap<>();
    private final TierProgressTracker tierProgress;

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

    private PlaytimeRuntime(PlayTimePlugin plugin, PlaytimeConfig config, RuntimeState previousState) throws Exception {
        this.plugin = plugin;
        this.runtimeConfig = config;
        DatabaseProvider allocatedDatabase = null;
        HeadCache allocatedHeadCache = null;
        try {
            allocatedDatabase = new DatabaseProvider(plugin, config);
            probe(CreationStage.DATABASE_ALLOCATED);
            allocatedDatabase.init(config.getStorageType());
            probe(CreationStage.DATABASE_INITIALIZED);
            PlaytimeRepository allocatedRepository = new PlaytimeRepository(plugin, allocatedDatabase, config);
            probe(CreationStage.REPOSITORY_CREATED);
            allocatedRepository.initSchema();
            probe(CreationStage.SCHEMA_INITIALIZED);
            SessionManager allocatedSessions = new SessionManager(
                    previousState == null ? Map.of() : previousState.sessionStarts());
            ActivityTracker allocatedActivities = new ActivityTracker(config, allocatedSessions,
                    previousState == null ? Map.of() : previousState.activitySnapshots(), performanceCounters);
            TierProgressTracker allocatedTierProgress = new TierProgressTracker(config.numerals().catalog(),
                    previousState == null ? Map.of() : previousState.tierProgress());
            allocatedHeadCache = new HeadCache(plugin, performanceCounters, allocatedRepository);
            probe(CreationStage.HEAD_CACHE_CREATED);
            AsyncWriteQueue allocatedQueue = new AsyncWriteQueue(
                    plugin, allocatedRepository, performanceCounters, config.getFlushIntervalTicks());
            probe(CreationStage.WRITE_QUEUE_CREATED);
            ShutdownRecoveryJournal allocatedRecoveryJournal = new ShutdownRecoveryJournal(plugin);
            allocatedRecoveryJournal.restoreInto(allocatedQueue);
            PlaytimeReadService allocatedReads = new PlaytimeReadService(plugin, allocatedRepository,
                    allocatedQueue, performanceCounters, config.leaderboards().cacheTtlSeconds());
            probe(CreationStage.READ_SERVICE_CREATED);
            LeaderboardExportService allocatedExport = new LeaderboardExportService(
                    plugin, allocatedRepository, config.leaderboards().export(), performanceCounters);
            probe(CreationStage.EXPORT_SERVICE_CREATED);
            PlaytimeServiceImpl allocatedService = new PlaytimeServiceImpl(
                    allocatedReads, allocatedRepository, allocatedActivities, allocatedSessions);

            this.databaseProvider = allocatedDatabase;
            this.playtimeRepository = allocatedRepository;
            this.sessions = allocatedSessions;
            this.activities = allocatedActivities;
            this.tierProgress = allocatedTierProgress;
            this.playerHeadCache = allocatedHeadCache;
            this.storageQueue = allocatedQueue;
            this.recoveryJournal = allocatedRecoveryJournal;
            this.reads = allocatedReads;
            this.exportService = allocatedExport;
            this.serviceApi = allocatedService;
        } catch (Exception | Error failure) {
            if (allocatedHeadCache != null) allocatedHeadCache.close();
            if (allocatedDatabase != null) allocatedDatabase.shutdown();
            throw failure;
        }
    }

    public static PlaytimeRuntime create(PlayTimePlugin plugin, PlaytimeConfig config,
                                         RuntimeState previousState) throws Exception {
        PlaytimeRuntime candidate = new PlaytimeRuntime(plugin, config, previousState);
        try {
            candidate.activate();
            return candidate;
        } catch (Exception | Error failure) {
            candidate.close(false);
            throw failure;
        }
    }

    private void activate() {
        storageQueue.start();
        probe(CreationStage.QUEUE_STARTED);
        planHook = createPlanHook();
        probe(CreationStage.PLAN_HOOK_CREATED);
        Bukkit.getPluginManager().registerEvents(activities, plugin);
        probe(CreationStage.LISTENER_REGISTERED);
        Bukkit.getServicesManager().register(PlaytimeService.class, serviceApi, plugin,
                org.bukkit.plugin.ServicePriority.Normal);
        probe(CreationStage.SERVICE_REGISTERED);

        long nowMillis = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            activities.bootstrapPlayer(player, nowMillis);
            playerHeadCache.updateHead(player);
            logRejectedWrite("bootstrap profile", player.getUniqueId(),
                    storageQueue.enqueuePlayerProfile(profileFor(player, Instant.now())));
            initializeTierProgress(player.getUniqueId());
        }
        tierProgress.uninitializedPlayers().forEach((uuid, connected) -> initializeTierProgress(uuid, connected));
        probe(CreationStage.ONLINE_PLAYERS_BOOTSTRAPPED);

        startMinuteTickTask();
        probe(CreationStage.MINUTE_TASK_SCHEDULED);
        startJoinPurgeTask();
        probe(CreationStage.JOIN_PURGE_TASK_SCHEDULED);
        startActionBarTask();
        probe(CreationStage.ACTION_BAR_TASK_SCHEDULED);
        startAuditTask();
        probe(CreationStage.AUDIT_TASK_SCHEDULED);
        startPerformanceLogTask();
        probe(CreationStage.PERFORMANCE_TASK_SCHEDULED);
        startLeaderboardExportTask();
        probe(CreationStage.LEADERBOARD_TASKS_SCHEDULED);
    }

    static void setCreationProbeForTesting(Consumer<CreationStage> probe) {
        creationProbe = probe == null ? ignored -> { } : probe;
    }

    static void setCloseProbeForTesting(Consumer<CloseStage> probe) {
        closeProbe = probe == null ? ignored -> { } : probe;
    }

    static void setTierReadProbeForTesting(Consumer<TierReadStage> probe) {
        tierReadProbe = probe == null ? ignored -> { } : probe;
    }

    static void setAnnouncementProbeForTesting(Consumer<String> probe) {
        announcementProbe = probe == null ? ignored -> { } : probe;
    }

    static void setTierMainExecutorForTesting(Consumer<Runnable> executor) {
        tierMainExecutor = executor;
    }

    private static void probe(CreationStage stage) {
        creationProbe.accept(stage);
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
        AsyncWriteQueue.EnqueueResult ownership = storageQueue.enqueueJoinWithProfile(
                uuid, joinedAt, profileFor(player, joinedAt));
        if (!owns(ownership)) {
            logRejectedWrite("join/profile", uuid, ownership);
            return false;
        }
        recentJoinDecisions.put(uuid, new JoinDecision(firstKnownJoin, uniqueNumber, System.currentTimeMillis()));
        reads.invalidateAll();
        tierProgress.reconnect(uuid);
        initializeTierProgress(uuid);
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
        tierProgress.disconnect(uuid);
        playtimeRepository.recordLastSeenAsync(plugin, uuid, quitAt);
    }

    public void noteHead(Player player) {
        playerHeadCache.updateHead(player);
    }

    public HandoffPreparation prepareRuntimeHandoff() {
        AsyncWriteQueue.TransitionResult result = storageQueue.prepareHandoff(runtimeConfig.leaderboards().export().shutdownTimeoutSeconds());
        if (result != AsyncWriteQueue.TransitionResult.SUCCESS) {
            storageQueue.abortHandoff();
            return new HandoffPreparation(result, null);
        }
        handoffPrepared.set(true);
        cancelRuntimeTasks();
        unregisterRuntimeServices();
        return new HandoffPreparation(result, new RuntimeState(
                new HashMap<>(sessions.snapshot()),
                new HashMap<>(activities.snapshot()),
                tierProgress.snapshot()
        ));
    }

    public AsyncWriteQueue.TransitionResult commitRuntimeHandoff() {
        AsyncWriteQueue.TransitionResult result = storageQueue.completeHandoff();
        if (result != AsyncWriteQueue.TransitionResult.SUCCESS) {
            return result;
        }
        tierProgressHandedOff.set(true);
        return result;
    }

    public void abortRuntimeHandoff() {
        tierProgressHandedOff.set(false);
        storageQueue.abortHandoff();
        if (handoffPrepared.compareAndSet(true, false) && !closed.get()) {
            planHook = createPlanHook();
            registerRuntimeBindings();
            startMinuteTickTask();
            startJoinPurgeTask();
            startActionBarTask();
            startAuditTask();
            startPerformanceLogTask();
            startLeaderboardExportTask();
        }
    }

    private void registerRuntimeBindings() {
        Bukkit.getPluginManager().registerEvents(activities, plugin);
        Bukkit.getServicesManager().register(PlaytimeService.class, serviceApi, plugin,
                org.bukkit.plugin.ServicePriority.Normal);
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
            logRejectedWrite("audit profile", player.getUniqueId(),
                    storageQueue.enqueuePlayerProfile(profileFor(player, Instant.now())));
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
        cleanupDisconnectedTierProgress();

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

            acceptMinute(player, event.getActiveMinutes(), event.getAfkMinutes());
        }
    }

    boolean acceptMinuteForTesting(Player player, int activeMinutes, int afkMinutes) {
        return acceptMinute(player, activeMinutes, afkMinutes);
    }

    private boolean acceptMinute(Player player, int activeMinutes, int afkMinutes) {
        AsyncWriteQueue.EnqueueResult ownership = storageQueue.enqueueMinute(
                player.getUniqueId(), activeMinutes, afkMinutes);
        if (!owns(ownership)) {
            logRejectedWrite("minute", player.getUniqueId(), ownership);
            return false;
        }
        TierProgressTracker.ActiveUpdate update =
                tierProgress.acceptActiveMinutes(player.getUniqueId(), Math.max(0, activeMinutes));
        announceTierAdvance(player, update.reachedTier());
        reads.invalidatePlayer(player.getUniqueId());
        return true;
    }

    TierProgressTracker.ProgressState tierProgressForTesting(UUID uuid) {
        return tierProgress.snapshot().get(uuid);
    }

    private boolean owns(AsyncWriteQueue.EnqueueResult result) {
        return result == AsyncWriteQueue.EnqueueResult.ACCEPTED;
    }

    private void logRejectedWrite(String type, UUID uuid, AsyncWriteQueue.EnqueueResult result) {
        if (!owns(result)) {
            plugin.getLogger().severe("Rejected " + type + " write for " + uuid
                    + " because queue state was " + result + "; runtime state was not advanced.");
        }
    }

    private void cleanupDisconnectedTierProgress() {
        tierProgress.disconnectedInitializedPlayers().forEach((uuid, ignored) -> {
            if (storageQueue.getAcceptedUncommittedTotals(uuid).activeMinutes <= 0L) {
                tierProgress.removeDisconnectedInitialized(uuid);
            }
        });
    }

    private void initializeTierProgress(UUID uuid) {
        initializeTierProgress(uuid, true);
    }

    void initializeTierProgressForTesting(UUID uuid, boolean connected) {
        initializeTierProgress(uuid, connected);
    }

    private void initializeTierProgress(UUID uuid, boolean connected) {
        Optional<TierProgressTracker.InitializationRequest> request = tierProgress.requestInitialization(uuid, connected,
                storageQueue.acceptedActiveSequence(uuid));
        if (request.isEmpty()) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> performTierInitializationRead(request.get()));
    }

    void performTierInitializationReadForTesting(UUID uuid, boolean connected) {
        tierProgress.requestInitialization(uuid, connected, storageQueue.acceptedActiveSequence(uuid))
                .ifPresent(this::performTierInitializationRead);
    }

    private void performTierInitializationRead(TierProgressTracker.InitializationRequest request) {
        AsyncWriteQueue.EffectiveActiveSnapshot effectiveSnapshot;
        try {
            Optional<AsyncWriteQueue.EffectiveActiveSnapshot> snapshot = storageQueue.readEffectiveActiveSnapshot(request.uuid(), () -> {
                tierReadProbe.accept(TierReadStage.AFTER_CUTOFF_BEFORE_SQL);
                LifetimeRead read = playtimeRepository.readLifetimeStrict(request.uuid());
                tierReadProbe.accept(TierReadStage.AFTER_SQL);
                if (read.status() == LifetimeReadStatus.FAILED) {
                    throw new TierInitializationReadException();
                }
                return read.status() == LifetimeReadStatus.FOUND ? read.snapshot().activeMinutes : 0L;
            });
            if (snapshot.isEmpty()) {
                scheduleTierInitializationRetry(request);
                return;
            }
            effectiveSnapshot = snapshot.get();
        } catch (TierInitializationReadException exception) {
            scheduleTierInitializationRetry(request);
            return;
        }
        if (closed.get() || tierProgressHandedOff.get()) {
            return;
        }
        Runnable completion = () -> finishTierProgressInitialization(request, effectiveSnapshot);
        Consumer<Runnable> executor = tierMainExecutor;
        if (executor == null) Bukkit.getScheduler().runTask(plugin, completion);
        else executor.accept(completion);
        tierReadProbe.accept(TierReadStage.MAIN_COMPLETION_SCHEDULED);
    }

    private void scheduleTierInitializationRetry(TierProgressTracker.InitializationRequest request) {
        if (!tierProgress.failInitialization(request) || closed.get() || tierProgressHandedOff.get()) {
            return;
        }
        int attempt = tierInitializationRetries.merge(request.uuid(), 1, Integer::sum);
        long delayTicks = Math.min(20L * 60L, 20L << Math.min(5, attempt));
        Bukkit.getScheduler().runTaskLater(plugin, () -> initializeTierProgress(request.uuid(), Bukkit.getPlayer(request.uuid()) != null), delayTicks);
    }

    private void finishTierProgressInitialization(TierProgressTracker.InitializationRequest request,
                                                  AsyncWriteQueue.EffectiveActiveSnapshot effectiveSnapshot) {
        if (closed.get() || tierProgressHandedOff.get()) {
            return;
        }
        if (storageQueue.acceptedActiveSequence(request.uuid()) != effectiveSnapshot.acceptedActiveSequence()) {
            scheduleTierInitializationRetry(request);
            return;
        }
        Optional<TierProgressTracker.InitializationResult> completed = tierProgress.finishInitialization(request, effectiveSnapshot.activeMinutes());
        if (completed.isEmpty()) {
            return;
        }
        TierProgressTracker.InitializationResult result = completed.get();
        tierInitializationRetries.remove(request.uuid());
        UUID uuid = request.uuid();
        Player player = Bukkit.getPlayer(uuid);
        if (player != null && result.connected()) {
            announceTierAdvance(player, result.reachedTier());
        }
    }

    private void announceTierAdvance(Player player, Optional<NumeralTierCatalog.Tier> reachedTier) {
        if (!runtimeConfig.numerals().enabled() || !runtimeConfig.numerals().announcement().enabled()) {
            return;
        }
        NumeralTierCatalog.Tier newTier = reachedTier.orElse(null);
        if (newTier == null) {
            return;
        }
        String message = runtimeConfig.numerals().announcement().message()
                .replace("%player%", player.getName())
                .replace("%tier_label%", newTier.label())
                .replace("%tier_color%", newTier.color())
                .replace("%tier_hours%", String.valueOf(newTier.requiredHours()));
        announcementProbe.accept(message);
        Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', message));
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
        cleanup(CloseStage.TASKS, this::cancelRuntimeTasks);
        cleanup(CloseStage.BUKKIT_SERVICE, () -> Bukkit.getServicesManager().unregister(serviceApi));
        cleanup(CloseStage.PLAN, this::closePlanHook);
        cleanup(CloseStage.LISTENERS, () -> HandlerList.unregisterAll(activities));
        cleanup(CloseStage.HEAD_CACHE, playerHeadCache::close);
        cleanup(CloseStage.QUEUE_AND_EXPORT, () -> {
            persistOnlinePlayersForShutdown();
            closeStorageAndExport(reloadClose);
        });
        if (!databaseCloseDeferred.get()) {
            cleanup(CloseStage.DATABASE, databaseProvider::shutdown);
        }
    }

    private void cleanup(CloseStage stage, Runnable action) {
        try {
            closeProbe.accept(stage);
        } catch (Exception exception) {
            plugin.getLogger().log(Level.WARNING, "Failed runtime cleanup stage " + stage + ".", exception);
        }
        try {
            action.run();
        } catch (Exception exception) {
            plugin.getLogger().log(Level.WARNING, "Failed runtime cleanup stage " + stage + ".", exception);
        }
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
        minuteTickTask = null;
        joinPurgeTask = null;
        actionBarTask = null;
        auditTask = null;
        performanceLogTask = null;
        initialLeaderboardExportTask = null;
        leaderboardExportTask = null;
    }

    private void closePlanHook() {
        try {
            planHook.close();
        } catch (Exception exception) {
            throw new IllegalStateException("Plan cleanup failed", exception);
        } finally {
            planHook = () -> { };
        }
    }

    private void unregisterRuntimeServices() {
        Bukkit.getServicesManager().unregister(serviceApi);
        try {
            closePlanHook();
        } catch (RuntimeException exception) {
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

    private AsyncWriteQueue.TransitionResult closeStorageAndExport(boolean reloadClose) {
        AsyncWriteQueue.TransitionResult result = storageQueue.shutdown(runtimeConfig.leaderboards().export().shutdownTimeoutSeconds());
        if (result != AsyncWriteQueue.TransitionResult.SUCCESS) {
            plugin.getLogger().severe("Playtime write queue shutdown did not durably flush all work: " + result);
            if (storageQueue.hasOutstandingWorkForShutdown() && storageQueue.isFlushInProgressForShutdown()) {
                databaseCloseDeferred.set(true);
                storageQueue.closeDatabaseAfterFlush(databaseProvider::shutdown,
                        Math.max(1, runtimeConfig.leaderboards().export().shutdownTimeoutSeconds()),
                        recoveryJournal::write);
            } else if (storageQueue.hasOutstandingWorkForShutdown()) {
                recoveryJournal.write(storageQueue.recoverySnapshot());
            }
        }
        boolean exportOnClose = reloadClose
                ? runtimeConfig.leaderboards().export().runOnReloadClose()
                : runtimeConfig.leaderboards().export().runOnDisable();
        if (exportOnClose) {
            exportService.exportAll();
        }
        return result;
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
                               Map<UUID, ActivityTracker.ActivitySnapshot> activitySnapshots,
                               Map<UUID, TierProgressTracker.ProgressState> tierProgress) {
    }

    public record HandoffPreparation(AsyncWriteQueue.TransitionResult result, RuntimeState state) {
    }

    public record JoinDecision(boolean firstKnownJoin, int uniqueNumber, long createdAtMillis) {
    }

    private record MinuteCredit(int activeMinutes, int afkMinutes) {
    }

    private static final class TierInitializationReadException extends RuntimeException {
    }
}

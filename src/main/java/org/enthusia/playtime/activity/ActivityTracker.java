package org.enthusia.playtime.activity;

import io.papermc.paper.event.player.PlayerArmSwingEvent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.plugin.Plugin;
import org.enthusia.playtime.config.PlaytimeConfig;
import org.enthusia.playtime.event.PlayerActivityStateChangeEvent;
import org.enthusia.playtime.util.PerformanceCounters;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks observed player input and determines whether it should be trusted as
 * genuine activity. SUSPICIOUS is deliberately an activity-confidence state, not
 * an anti-cheat verdict.
 */
public final class ActivityTracker implements Listener {
    private static final long UNINITIALIZED_ACTIVITY_TIME = 0L;
    private static final long ACTION_DEDUP_MILLIS = 75L;
    private static final long DUPLICATE_ACTION_DEDUP_MILLIS = 20L;
    private static final long EXTERNAL_VELOCITY_GRACE_MILLIS = 1_500L;
    private static final long MIN_SUSPICIOUS_RECOVERY_MILLIS = 180_000L;
    private static final long SUSPICIOUS_OFFLINE_RESET_MILLIS = 24L * 60L * 60L * 1000L;
    private static final double SERVER_CORRECTION_DISTANCE_SQUARED = 16.0D;
    private static final double PURE_FALL_HORIZONTAL_SQUARED = 0.0025D;
    private static final double FALLING_DELTA = -0.08D;
    private static final double JUMP_DELTA = 0.15D;
    private static final float HEAD_ROTATION_THRESHOLD = 2.0F;
    private static final double AUTOMATION_EVIDENCE_THRESHOLD = 0.55D;
    private static final float FULL_ROTATION_DEGREES = 360.0F;
    private static final float HALF_ROTATION_DEGREES = 180.0F;
    private static final String HIDDEN_ACTION_BAR_MESSAGE = "";

    private final PlaytimeConfig config;
    private final SessionManager sessionManager;
    private final PerformanceCounters counters;
    private final Map<UUID, ActivityData> data = new ConcurrentHashMap<>();
    private final Map<UUID, Long> suspiciousResetMarkers = new ConcurrentHashMap<>();
    private final long idleMillis;
    private final long afkMillis;
    private final boolean suspicionEnabled;
    private final long nonClickGraceMillis;
    private final long movementThrottleMs;
    private final boolean countHeadRotation;
    private final double tinyMovementThreshold;
    private final AdvancedDetectionSettings advanced;
    private final ActivityPatternAnalyzer analyzer;

    public ActivityTracker(PlaytimeConfig config,
                           SessionManager sessionManager,
                           Map<UUID, ActivitySnapshot> initialState,
                           PerformanceCounters counters) {
        this(config, sessionManager, initialState, Map.of(), counters);
    }

    public ActivityTracker(PlaytimeConfig config,
                           SessionManager sessionManager,
                           Map<UUID, ActivitySnapshot> initialState,
                           Map<UUID, Long> initialSuspiciousResetMarkers,
                           PerformanceCounters counters) {
        this.config = config;
        this.sessionManager = sessionManager;
        this.counters = counters;
        this.idleMillis = config.sampling().idleSeconds() * 1000L;
        this.afkMillis = config.sampling().afkSeconds() * 1000L;
        this.suspicionEnabled = config.sampling().suspicion().enabled();
        this.nonClickGraceMillis = config.sampling().suspicion().nonClickGraceSeconds() * 1000L;
        this.movementThrottleMs = config.activity().movementThrottleMs();
        this.countHeadRotation = config.activity().countHeadRotation();
        this.tinyMovementThreshold = config.activity().tinyMovementThreshold();
        this.advanced = AdvancedDetectionSettings.load(config);
        this.analyzer = new ActivityPatternAnalyzer(advanced);
        if (initialState != null) {
            initialState.forEach((uuid, snapshot) -> data.put(uuid, ActivityData.fromSnapshot(snapshot)));
        }
        if (initialSuspiciousResetMarkers != null) {
            suspiciousResetMarkers.putAll(initialSuspiciousResetMarkers);
        }
    }

    /**
     * Main-thread state sampling entry point. It publishes a Bukkit transition event
     * exactly once when the effective state changes.
     */
    public ActivityState getState(UUID uuid, long nowMillis) {
        ActivityData activityData = data.computeIfAbsent(uuid, ignored -> ActivityData.create(nowMillis));
        ActivityState state;
        ActivityState oldState = null;
        synchronized (activityData) {
            state = stateFor(uuid, activityData, nowMillis);
            if (Bukkit.isPrimaryThread() && activityData.online) {
                if (activityData.publishedState == null) {
                    activityData.publishedState = state;
                } else if (activityData.publishedState != state) {
                    oldState = activityData.publishedState;
                    activityData.publishedState = state;
                }
            }
        }
        if (oldState != null) {
            publishStateChange(uuid, oldState, state, nowMillis, activityData);
        }
        return state;
    }

    /** Read-only live state lookup for public API callers. Unknown/offline UUIDs are AFK. */
    public ActivityState peekState(UUID uuid, long nowMillis) {
        ActivityData activityData = data.get(uuid);
        if (activityData == null) {
            return ActivityState.AFK;
        }
        synchronized (activityData) {
            if (!activityData.online) {
                return ActivityState.AFK;
            }
            return stateFor(uuid, activityData, nowMillis);
        }
    }

    private ActivityState stateFor(UUID uuid, ActivityData activityData, long nowMillis) {
        analyzeIfDue(uuid, activityData, nowMillis);
        long sinceAny = Math.max(0L, nowMillis - activityData.lastGeneralActivity);
        if (sinceAny >= afkMillis) {
            return ActivityState.AFK;
        }
        if (sinceAny >= idleMillis) {
            return ActivityState.IDLE;
        }
        if (suspicionEnabled && activityData.suspicious) {
            return ActivityState.SUSPICIOUS;
        }
        return ActivityState.ACTIVE;
    }

    private void analyzeIfDue(UUID uuid, ActivityData activityData, long nowMillis) {
        if (!suspicionEnabled) {
            clearAnalysis(activityData);
            return;
        }
        if (!analysisDue(activityData, nowMillis)) {
            return;
        }

        long elapsed = analysisElapsed(activityData, nowMillis);
        activityData.lastAnalysisMillis = nowMillis;
        ActivityPatternAnalyzer.Analysis analysis = analyzeBehavior(activityData, nowMillis);
        activityData.analysis = analysis;
        double sparseEvidence = LongHorizonActivityAnalyzer.sparseKeepaliveEvidence(
                activityData.activityPulses, nowMillis, idleMillis);
        double evidence = Math.max(analysis.combinedEvidence(), sparseEvidence);
        updateAnalysisMarkers(activityData, analysis, evidence, nowMillis);
        updateSuspicionScore(activityData, evidence, elapsed);
        updateSuspicionState(uuid, activityData, nowMillis);
    }

    private static void clearAnalysis(ActivityData activityData) {
        activityData.suspicious = false;
        activityData.suspicionScore = 0.0D;
        activityData.variedRecoveryStartMillis = 0L;
        activityData.analysis = ActivityPatternAnalyzer.Analysis.EMPTY;
    }

    private boolean analysisDue(ActivityData activityData, long nowMillis) {
        long interval = advanced.scoring().analysisIntervalMillis();
        return activityData.lastAnalysisMillis <= 0L
                || nowMillis - activityData.lastAnalysisMillis >= interval;
    }

    private long analysisElapsed(ActivityData activityData, long nowMillis) {
        long interval = advanced.scoring().analysisIntervalMillis();
        return activityData.lastAnalysisMillis <= 0L
                ? interval
                : Math.max(1L, nowMillis - activityData.lastAnalysisMillis);
    }

    private ActivityPatternAnalyzer.Analysis analyzeBehavior(ActivityData activityData, long nowMillis) {
        boolean clickOnly = nowMillis - activityData.lastNonClickActivity >= nonClickGraceMillis;
        List<BehaviorSample> snapshot = List.copyOf(activityData.behaviorHistory);
        return analyzer.analyze(snapshot, nowMillis, clickOnly);
    }

    private static void updateAnalysisMarkers(ActivityData activityData,
                                              ActivityPatternAnalyzer.Analysis analysis,
                                              double evidence,
                                              long nowMillis) {
        if (evidence >= AUTOMATION_EVIDENCE_THRESHOLD) {
            activityData.lastAutomationEvidenceMillis = nowMillis;
            activityData.variedRecoveryStartMillis = 0L;
            return;
        }
        if (analysis.convincingVariation()) {
            activityData.lastVariedActivityMillis = nowMillis;
        }
        boolean recoveryQuality = analysis.convincingVariation()
                && LongHorizonActivityAnalyzer.hasDenseRecentActivity(activityData.activityPulses, nowMillis);
        if (!recoveryQuality) {
            activityData.variedRecoveryStartMillis = 0L;
        } else if (activityData.variedRecoveryStartMillis <= activityData.lastAutomationEvidenceMillis) {
            activityData.variedRecoveryStartMillis = nowMillis;
        }
    }

    private void updateSuspicionScore(ActivityData activityData, double evidence, long elapsed) {
        if (evidence >= activityData.suspicionScore) {
            activityData.suspicionScore = evidence;
            return;
        }
        double decay = advanced.scoring().decayPerSecond() * (elapsed / 1000.0D);
        activityData.suspicionScore = Math.max(evidence,
                Math.max(0.0D, activityData.suspicionScore - decay));
    }

    private void updateSuspicionState(UUID uuid, ActivityData activityData, long nowMillis) {
        boolean wasSuspicious = activityData.suspicious;
        if (activityData.suspicionScore >= advanced.scoring().suspiciousThreshold()) {
            activityData.suspicious = true;
        } else if (shouldClearSuspicion(activityData, nowMillis)) {
            activityData.suspicious = false;
        }
        if (wasSuspicious && !activityData.suspicious) {
            suspiciousResetMarkers.put(uuid, nowMillis);
        }
    }

    private boolean shouldClearSuspicion(ActivityData activityData, long nowMillis) {
        long recoveryMillis = Math.max(MIN_SUSPICIOUS_RECOVERY_MILLIS,
                advanced.scoring().recoveryMillis());
        return activityData.suspicious
                && activityData.suspicionScore <= advanced.scoring().clearThreshold()
                && activityData.lastVariedActivityMillis > activityData.lastAutomationEvidenceMillis
                && activityData.variedRecoveryStartMillis > activityData.lastAutomationEvidenceMillis
                && nowMillis - activityData.variedRecoveryStartMillis >= recoveryMillis;
    }

    public Map<UUID, ActivitySnapshot> snapshot() {
        Map<UUID, ActivitySnapshot> snapshot = new ConcurrentHashMap<>();
        for (Map.Entry<UUID, ActivityData> entry : data.entrySet()) {
            synchronized (entry.getValue()) {
                snapshot.put(entry.getKey(), entry.getValue().snapshot());
            }
        }
        return Collections.unmodifiableMap(snapshot);
    }

    public Map<UUID, Long> suspiciousResetMarkerSnapshot() {
        return Collections.unmodifiableMap(new ConcurrentHashMap<>(suspiciousResetMarkers));
    }

    public ActivityDiagnostics diagnostics(UUID uuid, long nowMillis) {
        ActivityData activityData = data.get(uuid);
        if (activityData == null) {
            return ActivityDiagnostics.EMPTY;
        }
        synchronized (activityData) {
            ActivityState state = activityData.online ? stateFor(uuid, activityData, nowMillis) : ActivityState.AFK;
            ActivityPatternAnalyzer.Analysis analysis = activityData.analysis;
            return new ActivityDiagnostics(state, activityData.suspicionScore,
                    analysis.clickRegularity(), analysis.movementRepetition(),
                    analysis.rotationRepetition(), analysis.sequenceRepetition(),
                    analysis.repetitions(), analysis.dominantCycleMillis(),
                    activityData.lastVariedActivityMillis <= 0L ? Long.MAX_VALUE
                            : Math.max(0L, nowMillis - activityData.lastVariedActivityMillis));
        }
    }

    public void bootstrapPlayer(Player player, long nowMillis) {
        pruneDisconnected(nowMillis);
        ActivityData activityData = getOrCreate(player, nowMillis);
        synchronized (activityData) {
            updatePosition(activityData, player.getLocation());
            activityData.online = true;
            activityData.disconnectedAtMillis = 0L;
            if (activityData.lastGeneralActivity <= UNINITIALIZED_ACTIVITY_TIME) {
                activityData.lastGeneralActivity = nowMillis;
            }
            if (activityData.lastNonClickActivity <= UNINITIALIZED_ACTIVITY_TIME) {
                activityData.lastNonClickActivity = nowMillis;
            }
            if (activityData.publishedState == null) {
                activityData.publishedState = stateFor(player.getUniqueId(), activityData, nowMillis);
            }
        }
        suspiciousResetMarkers.putIfAbsent(player.getUniqueId(), nowMillis);
        sessionManager.handleJoin(player.getUniqueId(), nowMillis);
    }

    public boolean ensureTracked(Player player, long nowMillis) {
        ActivityData existing = data.get(player.getUniqueId());
        if (existing != null) {
            synchronized (existing) {
                if (existing.online) {
                    return false;
                }
            }
        }
        bootstrapPlayer(player, nowMillis);
        return true;
    }

    public long getSuspiciousResetMarker(UUID uuid) {
        return suspiciousResetMarkers.getOrDefault(uuid, 0L);
    }

    public void forgetSuspiciousResetMarker(UUID uuid) {
        suspiciousResetMarkers.remove(uuid);
    }

    public String actionBarMessage(ActivityState state) {
        if (!config.actionBar().enabled()) {
            return HIDDEN_ACTION_BAR_MESSAGE;
        }
        return switch (state) {
            case ACTIVE -> actionBarText(config.actionBar().showActive(), config.actionBar().text().active());
            case IDLE -> actionBarText(config.actionBar().showIdle(), config.actionBar().text().idle());
            case AFK -> actionBarText(config.actionBar().showAfk(), config.actionBar().text().afk());
            case SUSPICIOUS -> actionBarText(config.actionBar().showSuspicious(), config.actionBar().text().suspicious());
        };
    }

    private String actionBarText(boolean visible, String text) {
        return visible ? stripColor(text) : HIDDEN_ACTION_BAR_MESSAGE;
    }

    private String stripColor(String value) {
        if (value == null) {
            return HIDDEN_ACTION_BAR_MESSAGE;
        }
        return ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', value));
    }

    private ActivityData getOrCreate(Player player, long nowMillis) {
        UUID uuid = player.getUniqueId();
        return data.compute(uuid, (ignored, existing) -> {
            if (existing == null) {
                return ActivityData.create(player, nowMillis);
            }
            synchronized (existing) {
                if (!existing.online && !existing.suspicious && existing.disconnectedAtMillis > 0L
                        && nowMillis - existing.disconnectedAtMillis
                        > advanced.scoring().reconnectRetentionMillis()) {
                    return ActivityData.create(player, nowMillis);
                }
                return existing;
            }
        });
    }

    void recordAction(Player player, long nowMillis, int actions) {
        recordAction(player.getUniqueId(), nowMillis, actions);
    }

    void recordAction(UUID uuid, long nowMillis, int actions) {
        ActivityData activityData = data.get(uuid);
        if (activityData == null) {
            counters.activityEventsSkipped.increment();
            return;
        }

        boolean newSample;
        synchronized (activityData) {
            if (!activityData.online) {
                counters.activityEventsSkipped.increment();
                return;
            }
            activityData.lastGeneralActivity = nowMillis;
            if ((actions & (BehaviorSample.CHAT | BehaviorSample.COMMAND)) != 0) {
                activityData.lastNonClickActivity = nowMillis;
            }
            BehaviorSample sample = new BehaviorSample(nowMillis, actions,
                    0.0D, 0.0D, 0.0D, 0.0F, 0.0F, true);
            newSample = appendBehavior(activityData, sample, true);
        }
        if (newSample) counters.activityEventsAccepted.increment();
        else counters.activityEventsSkipped.increment();
    }

    private boolean appendBehavior(ActivityData activityData,
                                   BehaviorSample sample,
                                   boolean deduplicateActions) {
        LongHorizonActivityAnalyzer.recordPulse(activityData.activityPulses, sample.timestampMillis());
        BehaviorSample last = activityData.behaviorHistory.peekLast();
        if (shouldMergeActionFanout(last, sample, deduplicateActions)) {
            activityData.behaviorHistory.pollLast();
            activityData.behaviorHistory.addLast(last.mergeActions(sample.actions(), sample.timestampMillis()));
            return false;
        }
        activityData.behaviorHistory.addLast(sample);
        trimBehaviorHistory(activityData);
        return true;
    }

    private static boolean shouldMergeActionFanout(BehaviorSample last,
                                                   BehaviorSample sample,
                                                   boolean deduplicateActions) {
        if (!deduplicateActions || last == null || !actionOnlyPair(last, sample)) {
            return false;
        }
        if (hasDistinctActionBits(last, sample)) {
            return withinActionWindow(last, sample, ACTION_DEDUP_MILLIS);
        }
        return hasSameActionBits(last, sample)
                && withinActionWindow(last, sample, DUPLICATE_ACTION_DEDUP_MILLIS);
    }

    private static boolean hasDistinctActionBits(BehaviorSample last, BehaviorSample sample) {
        return (sample.actions() & ~last.actions()) != 0;
    }

    private static boolean hasSameActionBits(BehaviorSample last, BehaviorSample sample) {
        return last.actions() == sample.actions();
    }

    private static boolean withinActionWindow(BehaviorSample last,
                                              BehaviorSample sample,
                                              long windowMillis) {
        long delta = sample.timestampMillis() - last.timestampMillis();
        return delta >= 0L && delta <= windowMillis;
    }

    private static boolean actionOnlyPair(BehaviorSample last, BehaviorSample sample) {
        return !last.hasMovement() && !last.hasRotation()
                && !sample.hasMovement() && !sample.hasRotation();
    }

    private void trimBehaviorHistory(ActivityData activityData) {
        while (activityData.behaviorHistory.size() > advanced.scoring().historySize()) {
            activityData.behaviorHistory.pollFirst();
        }
    }

    private static void updatePosition(ActivityData activityData, Location location) {
        activityData.lastX = location.getX();
        activityData.lastY = location.getY();
        activityData.lastZ = location.getZ();
        activityData.lastYaw = location.getYaw();
        activityData.lastPitch = location.getPitch();
        activityData.hasPosition = true;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        long nowMillis = System.currentTimeMillis();
        Player player = event.getPlayer();
        pruneDisconnected(nowMillis);
        ActivityData activityData = getOrCreate(player, nowMillis);
        synchronized (activityData) {
            updatePosition(activityData, player.getLocation());
            activityData.online = true;
            activityData.disconnectedAtMillis = 0L;
            if (activityData.lastGeneralActivity <= UNINITIALIZED_ACTIVITY_TIME) {
                activityData.lastGeneralActivity = nowMillis;
                activityData.lastNonClickActivity = nowMillis;
            }
            if (activityData.publishedState == null) {
                activityData.publishedState = stateFor(player.getUniqueId(), activityData, nowMillis);
            }
        }
        suspiciousResetMarkers.putIfAbsent(player.getUniqueId(), nowMillis);
        sessionManager.handleJoin(player.getUniqueId(), nowMillis);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        sessionManager.handleQuit(uuid);
        ActivityData activityData = data.get(uuid);
        if (activityData != null) {
            synchronized (activityData) {
                activityData.online = false;
                activityData.disconnectedAtMillis = System.currentTimeMillis();
            }
        }
        pruneDisconnected(System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        if (to == null) {
            counters.activityEventsSkipped.increment();
            return;
        }
        Location from = event.getFrom();
        if (isUnchangedMove(from, to)) {
            counters.activityEventsSkipped.increment();
            return;
        }

        long nowMillis = System.currentTimeMillis();
        Player player = event.getPlayer();
        ActivityData activityData = getOrCreate(player, nowMillis);
        synchronized (activityData) {
            if (!activityData.hasPosition) {
                updatePosition(activityData, to);
                counters.activityEventsSkipped.increment();
                return;
            }
            if (isMovementThrottled(activityData, nowMillis)) {
                counters.moveEventsThrottled.increment();
                return;
            }

            MovementDelta delta = movementDelta(activityData, to);
            boolean moved = delta.distanceSquared() > tinyMovementThreshold;
            boolean rotated = countHeadRotation && delta.hasHeadRotation();
            if (!moved && !rotated) {
                counters.activityEventsSkipped.increment();
                return;
            }
            activityData.lastMovementMutation = nowMillis;

            if (shouldSuppressMovement(player, activityData, delta, nowMillis)) {
                if (rotated) {
                    // Positional motion may be environmental, but camera input is still
                    // player-controlled. Keep only the rotation component and make it
                    // eligible for automation analysis instead of granting blind activity.
                    recordMovementSample(activityData, nowMillis, delta, false, true, true);
                    counters.activityEventsAccepted.increment();
                } else {
                    counters.activityEventsSkipped.increment();
                }
                updatePosition(activityData, to);
                return;
            }

            recordMovementSample(activityData, nowMillis, delta, moved, true, rotated);
            updatePosition(activityData, to);
            counters.activityEventsAccepted.increment();
        }
    }

    private void recordMovementSample(ActivityData activityData,
                                      long nowMillis,
                                      MovementDelta delta,
                                      boolean moved,
                                      boolean patternEligible,
                                      boolean rotated) {
        int actions = 0;
        if (moved) actions |= BehaviorSample.MOVE;
        if (rotated) actions |= BehaviorSample.ROTATE;
        if (moved && delta.dy() > JUMP_DELTA) actions |= BehaviorSample.JUMP;
        if (actions == 0) return;

        activityData.lastGeneralActivity = nowMillis;
        activityData.lastNonClickActivity = nowMillis;
        double dx = moved ? delta.dx() : 0.0D;
        double dy = moved ? delta.dy() : 0.0D;
        double dz = moved ? delta.dz() : 0.0D;
        BehaviorSample sample = new BehaviorSample(nowMillis, actions,
                dx, dy, dz, delta.yawDelta(), delta.pitchDelta(), patternEligible);
        appendBehavior(activityData, sample, false);
    }

    private boolean shouldSuppressMovement(Player player,
                                           ActivityData activityData,
                                           MovementDelta delta,
                                           long nowMillis) {
        return hasExternalOrPassiveMotion(player, activityData, delta, nowMillis)
                || isPassiveFall(player, delta);
    }

    private static boolean hasExternalOrPassiveMotion(Player player,
                                                       ActivityData activityData,
                                                       MovementDelta delta,
                                                       long nowMillis) {
        return nowMillis <= activityData.externalMotionUntilMillis
                || delta.distanceSquared() > SERVER_CORRECTION_DISTANCE_SQUARED
                || player.isInsideVehicle()
                || player.isGliding()
                || (player.isInWater() && !player.isSwimming());
    }

    private static boolean isPassiveFall(Player player, MovementDelta delta) {
        double horizontalSquared = delta.dx() * delta.dx() + delta.dz() * delta.dz();
        return !player.isOnGround()
                && delta.dy() < FALLING_DELTA
                && horizontalSquared < PURE_FALL_HORIZONTAL_SQUARED;
    }

    private boolean isMovementThrottled(ActivityData activityData, long nowMillis) {
        return movementThrottleMs > 0L
                && nowMillis - activityData.lastMovementMutation < movementThrottleMs;
    }

    private static boolean isUnchangedMove(Location from, Location to) {
        return from.getX() == to.getX()
                && from.getY() == to.getY()
                && from.getZ() == to.getZ()
                && from.getYaw() == to.getYaw()
                && from.getPitch() == to.getPitch();
    }

    private static MovementDelta movementDelta(ActivityData activityData, Location to) {
        double dx = to.getX() - activityData.lastX;
        double dy = to.getY() - activityData.lastY;
        double dz = to.getZ() - activityData.lastZ;
        float yaw = signedAngleDelta(to.getYaw(), activityData.lastYaw);
        float pitch = to.getPitch() - activityData.lastPitch;
        return new MovementDelta(dx, dy, dz, dx * dx + dy * dy + dz * dz, yaw, pitch);
    }

    private static float signedAngleDelta(float current, float previous) {
        float delta = (current - previous) % FULL_ROTATION_DEGREES;
        if (delta > HALF_ROTATION_DEGREES) delta -= FULL_ROTATION_DEGREES;
        if (delta < -HALF_ROTATION_DEGREES) delta += FULL_ROTATION_DEGREES;
        return delta;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onArmSwing(PlayerArmSwingEvent event) {
        if (!config.activity().suspiciousSwingTrackingEnabled()) {
            return;
        }
        recordAction(event.getPlayer(), System.currentTimeMillis(), BehaviorSample.SWING);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        recordAction(event.getPlayer(), System.currentTimeMillis(), BehaviorSample.INTERACT);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) {
            recordAction(player, System.currentTimeMillis(), BehaviorSample.ATTACK);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        recordAction(event.getPlayer(), System.currentTimeMillis(), BehaviorSample.BLOCK_BREAK);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        recordAction(event.getPlayer(), System.currentTimeMillis(), BehaviorSample.BLOCK_PLACE);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        if (config.chatActivity().countChatAsActivity()) {
            recordAction(event.getPlayer().getUniqueId(), System.currentTimeMillis(), BehaviorSample.CHAT);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (config.chatActivity().countCommandsAsActivity()) {
            recordAction(event.getPlayer(), System.currentTimeMillis(), BehaviorSample.COMMAND);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        markExternalMotion(event.getPlayer(), System.currentTimeMillis(), true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        markExternalMotion(event.getPlayer(), System.currentTimeMillis(), true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent event) {
        markExternalMotion(event.getPlayer(), System.currentTimeMillis(), false);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            markExternalMotion(player, System.currentTimeMillis(), false);
        }
    }

    private void markExternalMotion(Player player, long nowMillis, boolean rebase) {
        ActivityData activityData = getOrCreate(player, nowMillis);
        synchronized (activityData) {
            activityData.externalMotionUntilMillis = Math.max(activityData.externalMotionUntilMillis,
                    nowMillis + EXTERNAL_VELOCITY_GRACE_MILLIS);
            if (rebase) {
                updatePosition(activityData, player.getLocation());
                activityData.lastMovementMutation = nowMillis;
            }
        }
    }

    private void publishStateChange(UUID uuid, ActivityState oldState, ActivityState newState,
                                    long nowMillis, ActivityData activityData) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isOnline()) {
            return;
        }
        Bukkit.getPluginManager().callEvent(new PlayerActivityStateChangeEvent(
                player, oldState, newState, Instant.ofEpochMilli(nowMillis)));
        if (newState == ActivityState.SUSPICIOUS
                && config.debug().enabled() && config.debug().logSuspicious()) {
            ActivityDiagnostics diagnostics;
            synchronized (activityData) {
                diagnostics = diagnosticsLocked(newState, activityData, nowMillis);
            }
            Plugin plugin = Bukkit.getPluginManager().getPlugin("EnthusiaPlaytime");
            if (plugin != null) {
                plugin.getLogger().info("Untrusted repetitive activity for " + player.getName()
                        + ": score=" + percent(diagnostics.suspicionScore())
                        + ", click=" + percent(diagnostics.clickRegularity())
                        + ", movement=" + percent(diagnostics.movementRepetition())
                        + ", rotation=" + percent(diagnostics.rotationRepetition())
                        + ", sequence=" + percent(diagnostics.sequenceRepetition())
                        + ", repetitions=" + diagnostics.repetitions()
                        + ", cycle=" + diagnostics.dominantCycleMillis() + "ms.");
            }
        }
    }

    private ActivityDiagnostics diagnosticsLocked(ActivityState state, ActivityData activityData, long nowMillis) {
        ActivityPatternAnalyzer.Analysis analysis = activityData.analysis;
        return new ActivityDiagnostics(state, activityData.suspicionScore,
                analysis.clickRegularity(), analysis.movementRepetition(),
                analysis.rotationRepetition(), analysis.sequenceRepetition(),
                analysis.repetitions(), analysis.dominantCycleMillis(),
                activityData.lastVariedActivityMillis <= 0L ? Long.MAX_VALUE
                        : Math.max(0L, nowMillis - activityData.lastVariedActivityMillis));
    }

    private static String percent(double value) {
        return String.format(java.util.Locale.ROOT, "%.0f%%", value * 100.0D);
    }

    private void pruneDisconnected(long nowMillis) {
        removeExpiredDisconnected(nowMillis);
        trimDisconnectedRetention();
    }

    private void removeExpiredDisconnected(long nowMillis) {
        long normalRetention = advanced.scoring().reconnectRetentionMillis();
        for (Map.Entry<UUID, ActivityData> entry : data.entrySet()) {
            ActivityData activityData = entry.getValue();
            boolean remove;
            boolean resetSuspicion;
            synchronized (activityData) {
                long disconnectedFor = activityData.disconnectedAtMillis <= 0L
                        ? 0L
                        : nowMillis - activityData.disconnectedAtMillis;
                resetSuspicion = !activityData.online && activityData.suspicious
                        && disconnectedFor > SUSPICIOUS_OFFLINE_RESET_MILLIS;
                remove = !activityData.online && activityData.disconnectedAtMillis > 0L
                        && (resetSuspicion || (!activityData.suspicious && disconnectedFor > normalRetention));
            }
            if (remove && data.remove(entry.getKey(), activityData) && resetSuspicion) {
                suspiciousResetMarkers.put(entry.getKey(), nowMillis);
            }
        }
    }

    private void trimDisconnectedRetention() {
        List<Map.Entry<UUID, ActivityData>> removable = disconnectedNonSuspiciousEntries();
        int excess = data.size() - advanced.scoring().maxRetainedDisconnected();
        if (excess <= 0 || removable.isEmpty()) {
            return;
        }
        removable.sort(Comparator.comparingLong(entry -> entry.getValue().disconnectedAtMillis));
        int removeCount = Math.min(excess, removable.size());
        for (int index = 0; index < removeCount; index++) {
            Map.Entry<UUID, ActivityData> entry = removable.get(index);
            data.remove(entry.getKey(), entry.getValue());
        }
    }

    private List<Map.Entry<UUID, ActivityData>> disconnectedNonSuspiciousEntries() {
        List<Map.Entry<UUID, ActivityData>> disconnected = new ArrayList<>();
        for (Map.Entry<UUID, ActivityData> entry : data.entrySet()) {
            synchronized (entry.getValue()) {
                if (!entry.getValue().online && !entry.getValue().suspicious) {
                    disconnected.add(entry);
                }
            }
        }
        return disconnected;
    }

    private record MovementDelta(double dx, double dy, double dz, double distanceSquared,
                                 float yawDelta, float pitchDelta) {
        private boolean hasHeadRotation() {
            return Math.abs(yawDelta) > HEAD_ROTATION_THRESHOLD
                    || Math.abs(pitchDelta) > HEAD_ROTATION_THRESHOLD;
        }
    }

    private static final class ActivityData {
        private long lastGeneralActivity;
        private long lastNonClickActivity;
        private double lastX;
        private double lastY;
        private double lastZ;
        private float lastYaw;
        private float lastPitch;
        private long lastMovementMutation;
        private boolean hasPosition;
        private boolean online = true;
        private long disconnectedAtMillis;
        private long externalMotionUntilMillis;
        private final Deque<BehaviorSample> behaviorHistory = new ArrayDeque<>();
        private final Deque<Long> activityPulses = new ArrayDeque<>();
        private double suspicionScore;
        private boolean suspicious;
        private long lastAnalysisMillis;
        private long lastAutomationEvidenceMillis;
        private long lastVariedActivityMillis;
        private long variedRecoveryStartMillis;
        private ActivityPatternAnalyzer.Analysis analysis = ActivityPatternAnalyzer.Analysis.EMPTY;
        private ActivityState publishedState;

        private static ActivityData create(long nowMillis) {
            ActivityData data = new ActivityData();
            data.lastGeneralActivity = nowMillis;
            data.lastNonClickActivity = nowMillis;
            return data;
        }

        private static ActivityData create(Player player, long nowMillis) {
            ActivityData data = create(nowMillis);
            updatePosition(data, player.getLocation());
            return data;
        }

        private static ActivityData fromSnapshot(ActivitySnapshot snapshot) {
            ActivityData data = new ActivityData();
            data.lastGeneralActivity = snapshot.lastGeneralActivity();
            data.lastNonClickActivity = snapshot.lastNonClickActivity();
            data.lastX = snapshot.lastX();
            data.lastY = snapshot.lastY();
            data.lastZ = snapshot.lastZ();
            data.lastYaw = snapshot.lastYaw();
            data.lastPitch = snapshot.lastPitch();
            data.hasPosition = snapshot.hasPosition();
            data.behaviorHistory.addAll(snapshot.behaviorSamples());
            if (data.behaviorHistory.isEmpty()) {
                for (Long swing : snapshot.swingTimes()) {
                    data.behaviorHistory.addLast(new BehaviorSample(swing, BehaviorSample.SWING,
                            0.0D, 0.0D, 0.0D, 0.0F, 0.0F, true));
                }
            }
            data.activityPulses.addAll(snapshot.activityPulses());
            data.suspicionScore = snapshot.suspicionScore();
            data.suspicious = snapshot.suspicious();
            data.lastAnalysisMillis = snapshot.lastAnalysisMillis();
            data.lastAutomationEvidenceMillis = snapshot.lastAutomationEvidenceMillis();
            data.lastVariedActivityMillis = snapshot.lastVariedActivityMillis();
            data.variedRecoveryStartMillis = snapshot.variedRecoveryStartMillis();
            data.publishedState = snapshot.publishedState();
            data.online = snapshot.online();
            data.disconnectedAtMillis = snapshot.disconnectedAtMillis();
            return data;
        }

        private ActivitySnapshot snapshot() {
            List<Long> swings = new ArrayList<>();
            for (BehaviorSample sample : behaviorHistory) {
                if (sample.has(BehaviorSample.SWING)) swings.add(sample.timestampMillis());
            }
            return new ActivitySnapshot(lastGeneralActivity, lastNonClickActivity,
                    lastX, lastY, lastZ, lastYaw, lastPitch, hasPosition,
                    List.copyOf(swings), List.copyOf(behaviorHistory), suspicionScore,
                    suspicious, lastAnalysisMillis, lastAutomationEvidenceMillis,
                    lastVariedActivityMillis, publishedState, online, disconnectedAtMillis,
                    List.copyOf(activityPulses), variedRecoveryStartMillis);
        }
    }

    public record ActivitySnapshot(long lastGeneralActivity,
                                   long lastNonClickActivity,
                                   double lastX,
                                   double lastY,
                                   double lastZ,
                                   float lastYaw,
                                   float lastPitch,
                                   boolean hasPosition,
                                   List<Long> swingTimes,
                                   List<BehaviorSample> behaviorSamples,
                                   double suspicionScore,
                                   boolean suspicious,
                                   long lastAnalysisMillis,
                                   long lastAutomationEvidenceMillis,
                                   long lastVariedActivityMillis,
                                   ActivityState publishedState,
                                   boolean online,
                                   long disconnectedAtMillis,
                                   List<Long> activityPulses,
                                   long variedRecoveryStartMillis) {
        public ActivitySnapshot(long lastGeneralActivity,
                                long lastNonClickActivity,
                                double lastX,
                                double lastY,
                                double lastZ,
                                float lastYaw,
                                float lastPitch,
                                boolean hasPosition,
                                List<Long> swingTimes) {
            this(lastGeneralActivity, lastNonClickActivity, lastX, lastY, lastZ,
                    lastYaw, lastPitch, hasPosition, swingTimes, List.of(),
                    0.0D, false, 0L, 0L, 0L, null, true, 0L, List.of(), 0L);
        }

        public ActivitySnapshot(long lastGeneralActivity,
                                long lastNonClickActivity,
                                double lastX,
                                double lastY,
                                double lastZ,
                                float lastYaw,
                                float lastPitch,
                                boolean hasPosition,
                                List<Long> swingTimes,
                                List<BehaviorSample> behaviorSamples,
                                double suspicionScore,
                                boolean suspicious,
                                long lastAnalysisMillis,
                                long lastAutomationEvidenceMillis,
                                long lastVariedActivityMillis,
                                ActivityState publishedState,
                                boolean online,
                                long disconnectedAtMillis) {
            this(lastGeneralActivity, lastNonClickActivity, lastX, lastY, lastZ,
                    lastYaw, lastPitch, hasPosition, swingTimes, behaviorSamples,
                    suspicionScore, suspicious, lastAnalysisMillis, lastAutomationEvidenceMillis,
                    lastVariedActivityMillis, publishedState, online, disconnectedAtMillis,
                    List.of(), 0L);
        }

        public ActivitySnapshot {
            swingTimes = swingTimes == null ? List.of() : List.copyOf(swingTimes);
            behaviorSamples = behaviorSamples == null ? List.of() : List.copyOf(behaviorSamples);
            activityPulses = activityPulses == null ? List.of() : List.copyOf(activityPulses);
        }
    }

    public record ActivityDiagnostics(ActivityState state,
                                      double suspicionScore,
                                      double clickRegularity,
                                      double movementRepetition,
                                      double rotationRepetition,
                                      double sequenceRepetition,
                                      int repetitions,
                                      long dominantCycleMillis,
                                      long millisSinceConvincingVariation) {
        static final ActivityDiagnostics EMPTY = new ActivityDiagnostics(ActivityState.AFK,
                0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0, 0L, Long.MAX_VALUE);
    }
}

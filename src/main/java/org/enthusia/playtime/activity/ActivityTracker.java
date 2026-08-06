package org.enthusia.playtime.activity;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.enthusia.playtime.config.PlaytimeConfig;
import org.enthusia.playtime.util.PerformanceCounters;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ActivityTracker implements Listener {
    private static final long UNINITIALIZED_ACTIVITY_TIME = 0L;
    private static final int MIN_AUTOMATION_INTERVAL_COUNT = 2;
    private static final int MIN_AUTOMATION_VARIANCE_SAMPLES = 1;
    private static final int MIN_ROTATION_SAMPLES = 4;
    private static final double MIN_AUTOMATION_MEAN_MILLIS = 80.0D;
    private static final double MIN_POSITIVE_MEAN = 0.0D;
    private static final String HIDDEN_ACTION_BAR_MESSAGE = "";

    private final PlaytimeConfig config;
    private final SessionManager sessionManager;
    private final PerformanceCounters counters;
    private final Map<UUID, ActivityData> data = new ConcurrentHashMap<>();
    private final Map<UUID, Long> suspiciousResetMarkers = new ConcurrentHashMap<>();
    private final long idleMillis;
    private final long afkMillis;
    private final boolean suspicionEnabled;
    private final boolean swingTrackingEnabled;
    private final long suspicionWindowMillis;
    private final long nonClickGraceMillis;
    private final int minSwings;
    private final double maxCv;
    private final long movementThrottleMs;
    private final boolean countHeadRotation;
    private final double tinyMovementThreshold;

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
        this.swingTrackingEnabled = config.activity().suspiciousSwingTrackingEnabled();
        this.suspicionWindowMillis = config.sampling().suspicion().windowSeconds() * 1000L;
        this.nonClickGraceMillis = config.sampling().suspicion().nonClickGraceSeconds() * 1000L;
        this.minSwings = config.sampling().suspicion().minSwings();
        this.maxCv = config.sampling().suspicion().maxCv();
        this.movementThrottleMs = config.activity().movementThrottleMs();
        this.countHeadRotation = config.activity().countHeadRotation();
        this.tinyMovementThreshold = config.activity().tinyMovementThreshold();
        if (initialState != null) {
            initialState.forEach((uuid, snapshot) -> data.put(uuid, ActivityData.fromSnapshot(snapshot)));
        }
        if (initialSuspiciousResetMarkers != null) {
            suspiciousResetMarkers.putAll(initialSuspiciousResetMarkers);
        }
    }

    public ActivityState getState(UUID uuid, long nowMillis) {
        ActivityData activityData = data.computeIfAbsent(uuid, ignored -> ActivityData.create(nowMillis));
        return stateFor(activityData, nowMillis);
    }

    /** Read-only state lookup for public API callers. Unknown UUIDs are not retained. */
    public ActivityState peekState(UUID uuid, long nowMillis) {
        ActivityData activityData = data.get(uuid);
        return activityData == null ? ActivityState.AFK : stateFor(activityData, nowMillis);
    }

    private ActivityState stateFor(ActivityData activityData, long nowMillis) {
        synchronized (activityData) {
            long sinceAny = nowMillis - activityData.lastGeneralActivity;
            if (sinceAny >= afkMillis) {
                return ActivityState.AFK;
            }

            long sinceNonClick = nowMillis - activityData.lastNonClickActivity;
            if (suspicionEnabled
                    && sinceNonClick >= nonClickGraceMillis
                    && isAutoclickerPattern(activityData, nowMillis)) {
                return ActivityState.SUSPICIOUS;
            }

            if (sinceAny >= idleMillis) {
                return ActivityState.IDLE;
            }

            return ActivityState.ACTIVE;
        }
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

    public void bootstrapPlayer(Player player, long nowMillis) {
        ActivityData activityData = getOrCreate(player, nowMillis);
        synchronized (activityData) {
            updatePosition(activityData, player.getLocation());
            if (activityData.lastGeneralActivity <= UNINITIALIZED_ACTIVITY_TIME) {
                activityData.lastGeneralActivity = nowMillis;
            }
            if (activityData.lastNonClickActivity <= UNINITIALIZED_ACTIVITY_TIME) {
                activityData.lastNonClickActivity = nowMillis;
            }
        }
        suspiciousResetMarkers.putIfAbsent(player.getUniqueId(), nowMillis);
        sessionManager.handleJoin(player.getUniqueId(), nowMillis);
    }

    public boolean ensureTracked(Player player, long nowMillis) {
        ActivityData existing = data.get(player.getUniqueId());
        if (existing != null) {
            return false;
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

    private boolean isAutoclickerPattern(ActivityData data, long nowMillis) {
        if (!suspicionEnabled || !swingTrackingEnabled) {
            return false;
        }
        long cutoff = nowMillis - suspicionWindowMillis;
        while (!data.swingTimes.isEmpty() && data.swingTimes.peekFirst() < cutoff) {
            data.swingTimes.pollFirst();
        }

        int count = data.swingTimes.size();
        if (count < minSwings) {
            return false;
        }

        if (hasOnlyClickActivity(data, nowMillis)) {
            return true;
        }

        if (count <= MIN_AUTOMATION_INTERVAL_COUNT) {
            return false;
        }

        double[] intervals = new double[count - 1];
        long previous = -1L;
        int index = 0;
        for (long value : data.swingTimes) {
            if (previous != -1L) {
                intervals[index++] = value - previous;
            }
            previous = value;
        }

        if (index <= MIN_AUTOMATION_VARIANCE_SAMPLES) {
            return false;
        }

        double sum = 0.0D;
        for (int i = 0; i < index; i++) {
            sum += intervals[i];
        }
        double mean = sum / index;
        if (mean < MIN_AUTOMATION_MEAN_MILLIS) {
            return false;
        }

        double variance = 0.0D;
        for (int i = 0; i < index; i++) {
            double diff = intervals[i] - mean;
            variance += diff * diff;
        }
        variance /= index;

        double stdDev = Math.sqrt(variance);
        double cv = stdDev / mean;
        return cv <= maxCv;
    }

    private boolean hasOnlyClickActivity(ActivityData data, long nowMillis) {
        long cutoff = nowMillis - suspicionWindowMillis;
        return data.lastNonClickActivity < cutoff;
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
        return data.computeIfAbsent(player.getUniqueId(), ignored -> ActivityData.create(player, nowMillis));
    }

    private void recordNonClickActivity(Player player, long nowMillis) {
        ActivityData activityData = getOrCreate(player, nowMillis);
        synchronized (activityData) {
            activityData.lastGeneralActivity = nowMillis;
            activityData.lastNonClickActivity = nowMillis;
            updatePosition(activityData, player.getLocation());
            activityData.swingTimes.clear();
        }
        counters.activityEventsAccepted.increment();
        suspiciousResetMarkers.put(player.getUniqueId(), nowMillis);
    }

    private void recordNonClickActivity(UUID uuid, long nowMillis) {
        ActivityData activityData = data.computeIfAbsent(uuid, ignored -> ActivityData.create(nowMillis));
        synchronized (activityData) {
            activityData.lastGeneralActivity = nowMillis;
            activityData.lastNonClickActivity = nowMillis;
            activityData.swingTimes.clear();
        }
        counters.activityEventsAccepted.increment();
        suspiciousResetMarkers.put(uuid, nowMillis);
    }

    private void recordClickActivity(Player player, long nowMillis) {
        ActivityData activityData = getOrCreate(player, nowMillis);
        synchronized (activityData) {
            activityData.lastGeneralActivity = nowMillis;
            if (suspicionEnabled && swingTrackingEnabled) {
                activityData.swingTimes.addLast(nowMillis);
                while (activityData.swingTimes.size() > 128) {
                    activityData.swingTimes.pollFirst();
                }
            }
        }
        counters.activityEventsAccepted.increment();
    }

    private static void updatePosition(ActivityData data, Location location) {
        data.lastX = location.getX();
        data.lastY = location.getY();
        data.lastZ = location.getZ();
        data.lastYaw = location.getYaw();
        data.lastPitch = location.getPitch();
        data.hasPosition = true;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        long nowMillis = System.currentTimeMillis();
        Player player = event.getPlayer();
        ActivityData activityData = getOrCreate(player, nowMillis);
        synchronized (activityData) {
            updatePosition(activityData, player.getLocation());
            activityData.lastGeneralActivity = nowMillis;
            activityData.lastNonClickActivity = nowMillis;
            activityData.swingTimes.clear();
        }
        suspiciousResetMarkers.putIfAbsent(player.getUniqueId(), nowMillis);
        sessionManager.handleJoin(player.getUniqueId(), nowMillis);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onQuit(PlayerQuitEvent event) {
        sessionManager.handleQuit(event.getPlayer().getUniqueId());
        data.remove(event.getPlayer().getUniqueId());
        // Keep the last legitimate-activity marker so reconnecting cannot reset a
        // suspicious streak merely by cycling the connection.
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
                recordInitialPosition(player.getUniqueId(), activityData, to, nowMillis);
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
            if (moved) {
                recordMovement(player.getUniqueId(), activityData, to, nowMillis);
            } else if (rotated) {
                recordRotationMovement(player.getUniqueId(), activityData, to, nowMillis, delta.rotationAmount());
            }
        }
    }

    private boolean isUnchangedMove(Location from, Location to) {
        return from.getX() == to.getX()
                && from.getY() == to.getY()
                && from.getZ() == to.getZ()
                && from.getYaw() == to.getYaw()
                && from.getPitch() == to.getPitch();
    }

    private void recordInitialPosition(UUID uuid, ActivityData activityData, Location to, long nowMillis) {
        updatePosition(activityData, to);
        activityData.lastGeneralActivity = nowMillis;
        activityData.lastNonClickActivity = nowMillis;
        counters.activityEventsAccepted.increment();
        suspiciousResetMarkers.put(uuid, nowMillis);
    }

    private boolean isMovementThrottled(ActivityData activityData, long nowMillis) {
        return movementThrottleMs > 0L && nowMillis - activityData.lastMovementMutation < movementThrottleMs;
    }

    private MovementDelta movementDelta(ActivityData activityData, Location to) {
        double dx = to.getX() - activityData.lastX;
        double dy = to.getY() - activityData.lastY;
        double dz = to.getZ() - activityData.lastZ;
        float dyaw = angleDelta(to.getYaw(), activityData.lastYaw);
        float dpitch = Math.abs(to.getPitch() - activityData.lastPitch);
        return new MovementDelta(dx * dx + dy * dy + dz * dz, dyaw, dpitch);
    }

    private void recordMovement(UUID uuid, ActivityData activityData, Location to, long nowMillis) {
        updatePosition(activityData, to);
        activityData.lastGeneralActivity = nowMillis;
        activityData.lastNonClickActivity = nowMillis;
        activityData.swingTimes.clear();
        counters.activityEventsAccepted.increment();
        suspiciousResetMarkers.put(uuid, nowMillis);
    }

    private void recordRotationMovement(UUID uuid, ActivityData activityData, Location to,
                                      long nowMillis, float rotationAmount) {
        boolean wasAutoclicking = suspicionEnabled && isAutoclickerPattern(activityData, nowMillis);
        if (suspicionEnabled) {
            recordRotation(activityData, nowMillis, rotationAmount);
        }
        updatePosition(activityData, to);
        if (!wasAutoclicking && !isSuspiciousRotationPattern(activityData, nowMillis)) {
            activityData.lastGeneralActivity = nowMillis;
            activityData.lastNonClickActivity = nowMillis;
            activityData.swingTimes.clear();
            suspiciousResetMarkers.put(uuid, nowMillis);
        }
        counters.activityEventsAccepted.increment();
    }

    private static float angleDelta(float current, float previous) {
        float delta = Math.abs(current - previous) % 360.0F;
        return delta > 180.0F ? 360.0F - delta : delta;
    }

    private void recordRotation(ActivityData data, long nowMillis, float amount) {
        long cutoff = nowMillis - suspicionWindowMillis;
        data.rotationTimes.addLast(nowMillis);
        data.rotationAmounts.addLast(amount);
        while (!data.rotationTimes.isEmpty() && data.rotationTimes.peekFirst() < cutoff) {
            data.rotationTimes.pollFirst();
            data.rotationAmounts.pollFirst();
        }
        while (data.rotationTimes.size() > 128) {
            data.rotationTimes.pollFirst();
            data.rotationAmounts.pollFirst();
        }
    }

    private boolean isSuspiciousRotationPattern(ActivityData data, long nowMillis) {
        if (!suspicionEnabled) {
            return false;
        }
        long cutoff = nowMillis - suspicionWindowMillis;
        while (!data.rotationTimes.isEmpty() && data.rotationTimes.peekFirst() < cutoff) {
            data.rotationTimes.pollFirst();
            data.rotationAmounts.pollFirst();
        }

        int count = data.rotationTimes.size();
        if (count < MIN_ROTATION_SAMPLES) {
            return false;
        }

        double intervalCv = coefficientOfVariationForTimes(data.rotationTimes);
        double amountCv = coefficientOfVariationForAmounts(data.rotationAmounts);
        return intervalCv <= maxCv
                && amountCv <= Math.max(0.02D, maxCv);
    }

    private static double coefficientOfVariationForTimes(Deque<Long> times) {
        if (times.size() <= MIN_AUTOMATION_INTERVAL_COUNT) {
            return Double.MAX_VALUE;
        }

        List<Double> intervals = new ArrayList<>();
        long previous = -1L;
        for (long value : times) {
            if (previous != -1L) {
                intervals.add((double) (value - previous));
            }
            previous = value;
        }
        return coefficientOfVariation(intervals);
    }

    private static double coefficientOfVariationForAmounts(Deque<Float> amounts) {
        if (amounts.size() <= MIN_AUTOMATION_VARIANCE_SAMPLES) {
            return Double.MAX_VALUE;
        }

        List<Double> values = new ArrayList<>();
        for (float value : amounts) {
            values.add((double) value);
        }
        return coefficientOfVariation(values);
    }

    private static double coefficientOfVariation(List<Double> values) {
        if (values.isEmpty()) {
            return Double.MAX_VALUE;
        }

        double sum = 0.0D;
        for (double value : values) {
            sum += value;
        }
        double mean = sum / values.size();
        if (mean <= MIN_POSITIVE_MEAN) {
            return Double.MAX_VALUE;
        }

        double variance = 0.0D;
        for (double value : values) {
            double diff = value - mean;
            variance += diff * diff;
        }
        return Math.sqrt(variance / values.size()) / mean;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        if (!config.chatActivity().countChatAsActivity()) {
            return;
        }
        recordNonClickActivity(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!config.chatActivity().countCommandsAsActivity()) {
            return;
        }
        recordNonClickActivity(event.getPlayer(), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        long nowMillis = System.currentTimeMillis();
        switch (event.getAction()) {
            case LEFT_CLICK_AIR, LEFT_CLICK_BLOCK, RIGHT_CLICK_AIR, RIGHT_CLICK_BLOCK -> recordClickActivity(event.getPlayer(), nowMillis);
            default -> recordNonClickActivity(event.getPlayer(), nowMillis);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) {
            recordClickActivity(player, System.currentTimeMillis());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        recordClickActivity(event.getPlayer(), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        recordNonClickActivity(event.getPlayer(), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        recordNonClickActivity(event.getPlayer(), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        recordNonClickActivity(event.getPlayer(), System.currentTimeMillis());
    }

    private record MovementDelta(double distanceSquared, float yawDelta, float pitchDelta) {
        private static final float HEAD_ROTATION_THRESHOLD = 2.0F;

        private boolean hasHeadRotation() {
            return yawDelta > HEAD_ROTATION_THRESHOLD || pitchDelta > HEAD_ROTATION_THRESHOLD;
        }

        private float rotationAmount() {
            return yawDelta + pitchDelta;
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
        private final Deque<Long> swingTimes = new ArrayDeque<>();
        private final Deque<Long> rotationTimes = new ArrayDeque<>();
        private final Deque<Float> rotationAmounts = new ArrayDeque<>();

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
            data.swingTimes.addAll(snapshot.swingTimes());
            return data;
        }

        private ActivitySnapshot snapshot() {
            return new ActivitySnapshot(
                    lastGeneralActivity,
                    lastNonClickActivity,
                    lastX,
                    lastY,
                    lastZ,
                    lastYaw,
                    lastPitch,
                    hasPosition,
                    List.copyOf(new ArrayList<>(swingTimes))
            );
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
                                   List<Long> swingTimes) {
    }
}

package org.enthusia.playtime.activity;

import org.bukkit.Bukkit;
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
import org.enthusia.playtime.PlayTimePlugin;
import org.enthusia.playtime.config.PlaytimeConfig;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ActivityTracker implements Listener {

    private final PlayTimePlugin plugin;
    private final PlaytimeConfig config;
    private final SessionManager sessionManager;
    private final Map<UUID, ActivityData> data = new ConcurrentHashMap<>();
    private final Map<UUID, Long> suspiciousResetMarkers = new ConcurrentHashMap<>();

    public ActivityTracker(PlayTimePlugin plugin,
                           PlaytimeConfig config,
                           SessionManager sessionManager,
                           Map<UUID, ActivitySnapshot> initialState) {
        this.plugin = plugin;
        this.config = config;
        this.sessionManager = sessionManager;
        if (initialState != null) {
            initialState.forEach((uuid, snapshot) -> data.put(uuid, ActivityData.fromSnapshot(snapshot)));
        }
    }

    public ActivityState getState(UUID uuid, long nowMillis) {
        ActivityData activityData = data.computeIfAbsent(uuid, ignored -> ActivityData.create(nowMillis));
        synchronized (activityData) {
            long sinceAny = nowMillis - activityData.lastGeneralActivity;
            if (sinceAny >= config.sampling().afkSeconds() * 1000L) {
                return ActivityState.AFK;
            }

            long sinceNonClick = nowMillis - activityData.lastNonClickActivity;
            if (config.sampling().suspicion().enabled()
                    && sinceNonClick >= config.sampling().suspicion().nonClickGraceSeconds() * 1000L
                    && isSuspiciousSwingPattern(activityData, nowMillis)) {
                return ActivityState.SUSPICIOUS;
            }

            if (sinceAny >= config.sampling().idleSeconds() * 1000L) {
                return ActivityState.IDLE;
            }

            return ActivityState.ACTIVE;
        }
    }

    public Map<UUID, ActivitySnapshot> snapshot() {
        Map<UUID, ActivitySnapshot> snapshot = new HashMap<>();
        for (Map.Entry<UUID, ActivityData> entry : data.entrySet()) {
            synchronized (entry.getValue()) {
                snapshot.put(entry.getKey(), entry.getValue().snapshot());
            }
        }
        return Collections.unmodifiableMap(snapshot);
    }

    public void bootstrapPlayer(Player player, long nowMillis) {
        ActivityData activityData = getOrCreate(player, nowMillis);
        synchronized (activityData) {
            updatePosition(activityData, player.getLocation());
            if (activityData.lastGeneralActivity <= 0L) {
                activityData.lastGeneralActivity = nowMillis;
            }
            if (activityData.lastNonClickActivity <= 0L) {
                activityData.lastNonClickActivity = nowMillis;
            }
        }
        suspiciousResetMarkers.put(player.getUniqueId(), nowMillis);
        sessionManager.handleJoin(player.getUniqueId(), nowMillis);
    }

    public long getSuspiciousResetMarker(UUID uuid) {
        return suspiciousResetMarkers.getOrDefault(uuid, 0L);
    }

    private boolean isSuspiciousSwingPattern(ActivityData data, long nowMillis) {
        long cutoff = nowMillis - (config.sampling().suspicion().windowSeconds() * 1000L);
        while (!data.swingTimes.isEmpty() && data.swingTimes.peekFirst() < cutoff) {
            data.swingTimes.pollFirst();
        }

        int count = data.swingTimes.size();
        if (count < config.sampling().suspicion().minSwings() || count <= 2) {
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

        if (index <= 1) {
            return false;
        }

        double sum = 0.0D;
        for (int i = 0; i < index; i++) {
            sum += intervals[i];
        }
        double mean = sum / index;
        if (mean < 80.0D) {
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
        return cv <= config.sampling().suspicion().maxCv();
    }

    public String actionBarMessage(ActivityState state) {
        if (!config.actionBar().enabled()) {
            return null;
        }
        return switch (state) {
            case ACTIVE -> config.actionBar().showActive() ? stripColor(config.actionBar().text().active()) : null;
            case IDLE -> config.actionBar().showIdle() ? stripColor(config.actionBar().text().idle()) : null;
            case AFK -> config.actionBar().showAfk() ? stripColor(config.actionBar().text().afk()) : null;
            case SUSPICIOUS -> config.actionBar().showSuspicious() ? stripColor(config.actionBar().text().suspicious()) : null;
        };
    }

    private String stripColor(String value) {
        if (value == null) {
            return null;
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
        suspiciousResetMarkers.put(player.getUniqueId(), nowMillis);
    }

    private void recordNonClickActivity(UUID uuid, long nowMillis) {
        ActivityData activityData = data.computeIfAbsent(uuid, ignored -> ActivityData.create(nowMillis));
        synchronized (activityData) {
            activityData.lastGeneralActivity = nowMillis;
            activityData.lastNonClickActivity = nowMillis;
            activityData.swingTimes.clear();
        }
        suspiciousResetMarkers.put(uuid, nowMillis);
    }

    private void recordClickActivity(Player player, long nowMillis) {
        ActivityData activityData = getOrCreate(player, nowMillis);
        synchronized (activityData) {
            activityData.lastGeneralActivity = nowMillis;
            activityData.swingTimes.addLast(nowMillis);
            while (activityData.swingTimes.size() > 128) {
                activityData.swingTimes.pollFirst();
            }
        }
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
        suspiciousResetMarkers.put(player.getUniqueId(), nowMillis);
        sessionManager.handleJoin(player.getUniqueId(), nowMillis);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onQuit(PlayerQuitEvent event) {
        sessionManager.handleQuit(event.getPlayer().getUniqueId());
        data.remove(event.getPlayer().getUniqueId());
        suspiciousResetMarkers.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        if (to == null) {
            return;
        }

        long nowMillis = System.currentTimeMillis();
        Player player = event.getPlayer();
        ActivityData activityData = getOrCreate(player, nowMillis);
        synchronized (activityData) {
            if (!activityData.hasPosition) {
                updatePosition(activityData, to);
                activityData.lastGeneralActivity = nowMillis;
                activityData.lastNonClickActivity = nowMillis;
                return;
            }

            double dx = to.getX() - activityData.lastX;
            double dy = to.getY() - activityData.lastY;
            double dz = to.getZ() - activityData.lastZ;
            float dyaw = Math.abs(to.getYaw() - activityData.lastYaw);
            float dpitch = Math.abs(to.getPitch() - activityData.lastPitch);

            boolean moved = (dx * dx + dy * dy + dz * dz) > 0.01D;
            boolean rotated = dyaw > 2.0F || dpitch > 2.0F;
            if (moved || rotated) {
                updatePosition(activityData, to);
                activityData.lastGeneralActivity = nowMillis;
                activityData.lastNonClickActivity = nowMillis;
            }
        }
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

    private static final class ActivityData {
        private long lastGeneralActivity;
        private long lastNonClickActivity;
        private double lastX;
        private double lastY;
        private double lastZ;
        private float lastYaw;
        private float lastPitch;
        private boolean hasPosition;
        private final Deque<Long> swingTimes = new ArrayDeque<>();

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

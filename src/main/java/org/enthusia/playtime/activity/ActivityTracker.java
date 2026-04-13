package org.enthusia.playtime.activity;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.*;
import org.enthusia.playtime.PlayTimePlugin;
import org.enthusia.playtime.config.PlaytimeConfig;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks player activity and classifies them as ACTIVE, IDLE, AFK, or SUSPICIOUS.
 *
 * Heuristics:
 *  - ACTIVE: recent movement/rotation/chat/commands/etc.
 *  - IDLE: no activity for idleTimeout but not long enough to be AFK.
 *  - AFK: no activity at all for afkTimeout.
 *  - SUSPICIOUS: player is only clicking (no non-click input) and the swing pattern
 *                is extremely regular (low variance) over a sliding time window,
 *                which is typical for basic macros.
 *
 * Also handles optional action bar status messages.
 */
public final class ActivityTracker implements Listener {

    private final PlayTimePlugin plugin;
    @SuppressWarnings("unused")
    private final PlaytimeConfig config;
    private final SessionManager sessionManager;

    private static final class ActivityData {
        long lastGeneralActivity;      // any activity (move, chat, click, etc.)
        long lastNonClickActivity;     // anything except weapon/tool clicks
        double lastX, lastY, lastZ;
        float lastYaw, lastPitch;
        boolean hasPosition;

        // Timestamps (millis) of recent swings / clicks
        Deque<Long> swingTimes = new ArrayDeque<>();
    }

    private final Map<UUID, ActivityData> data = new ConcurrentHashMap<>();

    // Timeouts (ms)
    private final long idleTimeoutMillis;
    private final long afkTimeoutMillis;

    // Suspicion config
    private final boolean suspicionEnabled;
    private final long suspWindowMillis;
    private final int suspMinSwings;
    private final double suspMaxCv;
    private final long suspNonClickGraceMillis;

    // Action bar config
    private final boolean actionBarEnabled;
    private final boolean abShowActive;
    private final boolean abShowIdle;
    private final boolean abShowAfk;
    private final boolean abShowSuspicious;
    private final String abTextActive;
    private final String abTextIdle;
    private final String abTextAfk;
    private final String abTextSuspicious;

    public ActivityTracker(PlayTimePlugin plugin,
                           PlaytimeConfig config,
                           SessionManager sessionManager) {
        this.plugin = plugin;
        this.config = config;
        this.sessionManager = sessionManager;

        FileConfiguration cfg = plugin.getConfig();

        // Timeouts
        this.idleTimeoutMillis = cfg.getLong("sampling.idle_seconds", 60L) * 1000L;
        this.afkTimeoutMillis = cfg.getLong("sampling.afk_seconds", 300L) * 1000L;

        // Suspicion heuristics
        this.suspicionEnabled = cfg.getBoolean("sampling.suspicion.enabled", true);
        this.suspWindowMillis = cfg.getLong("sampling.suspicion.window_seconds", 20L) * 1000L;
        this.suspMinSwings = cfg.getInt("sampling.suspicion.min_swings", 25);
        this.suspMaxCv = cfg.getDouble("sampling.suspicion.max_cv", 0.08D); // coefficient of variation
        this.suspNonClickGraceMillis =
                cfg.getLong("sampling.suspicion.non_click_grace_seconds", 10L) * 1000L;

        // Action bar
        this.actionBarEnabled = cfg.getBoolean("ux.actionbar.enabled", false);
        this.abShowActive = cfg.getBoolean("ux.actionbar.show-active", false);
        this.abShowIdle = cfg.getBoolean("ux.actionbar.show-idle", false);
        this.abShowAfk = cfg.getBoolean("ux.actionbar.show-afk", true);
        this.abShowSuspicious = cfg.getBoolean("ux.actionbar.show-suspicious", true);

        this.abTextActive = stripColor(cfg.getString("ux.actionbar.text.active", "Playing"));
        this.abTextIdle = stripColor(cfg.getString("ux.actionbar.text.idle", "Idle"));
        this.abTextAfk = stripColor(cfg.getString("ux.actionbar.text.afk", "AFK"));
        this.abTextSuspicious = stripColor(cfg.getString("ux.actionbar.text.suspicious", "Suspicious input"));

        // Periodic action bar task
        if (actionBarEnabled) {
            Bukkit.getScheduler().runTaskTimer(plugin, this::tickActionBar, 20L, 20L);
        }
    }

    // --- Public API used by PlayTimePlugin + PAPI ---

    public ActivityState getState(UUID uuid, long nowMillis) {
        ActivityData d = data.computeIfAbsent(uuid, id -> {
            ActivityData nd = new ActivityData();
            nd.lastGeneralActivity = nowMillis;
            nd.lastNonClickActivity = nowMillis;
            return nd;
        });

        long sinceAny = nowMillis - d.lastGeneralActivity;

        // Hard AFK cutoff – no activity at all
        if (sinceAny >= afkTimeoutMillis) {
            return ActivityState.AFK;
        }

        long sinceNonClick = nowMillis - d.lastNonClickActivity;

        // Suspicion: only clicks, no movement/aim/chat for a while AND extremely regular click pattern
        if (suspicionEnabled &&
                sinceNonClick >= suspNonClickGraceMillis &&
                isSuspiciousSwingPattern(d, nowMillis)) {
            return ActivityState.SUSPICIOUS;
        }

        // Idle: not fully AFK but no recent activity
        if (sinceAny >= idleTimeoutMillis) {
            return ActivityState.IDLE;
        }

        // Otherwise active
        return ActivityState.ACTIVE;
    }

    // --- Action bar tick ---

    private void tickActionBar() {
        if (!actionBarEnabled) return;
        long now = System.currentTimeMillis();

        for (Player p : Bukkit.getOnlinePlayers()) {
            ActivityState state = getState(p.getUniqueId(), now);
            String msg = switch (state) {
                case ACTIVE -> abShowActive ? abTextActive : null;
                case IDLE -> abShowIdle ? abTextIdle : null;
                case AFK -> abShowAfk ? abTextAfk : null;
                case SUSPICIOUS -> abShowSuspicious ? abTextSuspicious : null;
            };

            if (msg == null || msg.isEmpty()) {
                continue;
            }

            // Paper/Leaf 1.21+ – send action bar using Adventure API
            p.sendActionBar(net.kyori.adventure.text.Component.text(msg));
        }
    }

    private String stripColor(String in) {
        if (in == null) return "";
        return ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', in));
    }

    // --- Suspicion detection ---

    private boolean isSuspiciousSwingPattern(ActivityData d, long nowMillis) {
        // Clean out old samples
        long cutoff = nowMillis - suspWindowMillis;
        while (!d.swingTimes.isEmpty() && d.swingTimes.peekFirst() < cutoff) {
            d.swingTimes.pollFirst();
        }

        int count = d.swingTimes.size();
        if (count < suspMinSwings) {
            return false;
        }

        if (count <= 2) {
            return false;
        }

        // Build intervals between swings
        int intervalsCount = count - 1;
        double[] intervals = new double[intervalsCount];

        long prev = -1;
        int idx = 0;
        for (long ts : d.swingTimes) {
            if (prev != -1) {
                intervals[idx++] = (ts - prev);
            }
            prev = ts;
        }

        if (idx <= 1) {
            return false;
        }

        // Compute mean interval
        double sum = 0;
        for (int i = 0; i < idx; i++) {
            sum += intervals[i];
        }
        double mean = sum / idx;

        // Ignore absurdly small means (spam / double events)
        if (mean < 80.0) {
            return false;
        }

        // Compute standard deviation
        double var = 0;
        for (int i = 0; i < idx; i++) {
            double diff = intervals[i] - mean;
            var += diff * diff;
        }
        var /= idx;
        double std = Math.sqrt(var);

        double cv = std / mean; // coefficient of variation

        // If the pattern is very regular (low variance), treat it as suspicious
        return cv <= suspMaxCv;
    }

    // --- Internal helpers ---

    private ActivityData getOrCreate(Player player, long now) {
        return data.computeIfAbsent(player.getUniqueId(), id -> {
            ActivityData d = new ActivityData();
            d.lastGeneralActivity = now;
            d.lastNonClickActivity = now;
            d.lastX = player.getLocation().getX();
            d.lastY = player.getLocation().getY();
            d.lastZ = player.getLocation().getZ();
            d.lastYaw = player.getLocation().getYaw();
            d.lastPitch = player.getLocation().getPitch();
            d.hasPosition = true;
            return d;
        });
    }

    private void recordNonClickActivity(Player player, long now) {
        ActivityData d = getOrCreate(player, now);
        d.lastGeneralActivity = now;
        d.lastNonClickActivity = now;
    }

    private void recordClickActivity(Player player, long now) {
        ActivityData d = getOrCreate(player, now);
        d.lastGeneralActivity = now;
        d.swingTimes.addLast(now);
        // Cap size to avoid unbounded growth
        while (d.swingTimes.size() > 64) {
            d.swingTimes.pollFirst();
        }
    }

    // --- Event listeners ---

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        long now = System.currentTimeMillis();
        Player p = event.getPlayer();
        getOrCreate(p, now);
        if (sessionManager != null) {
            sessionManager.handleJoin(p);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onQuit(PlayerQuitEvent event) {
        if (sessionManager != null) {
            sessionManager.handleQuit(event.getPlayer());
        }
        // We keep their data in memory – no need to remove it, but we could:
        // data.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player p = event.getPlayer();
        long now = System.currentTimeMillis();
        ActivityData d = getOrCreate(p, now);

        if (!d.hasPosition) {
            d.lastX = event.getTo().getX();
            d.lastY = event.getTo().getY();
            d.lastZ = event.getTo().getZ();
            d.lastYaw = event.getTo().getYaw();
            d.lastPitch = event.getTo().getPitch();
            d.hasPosition = true;
            recordNonClickActivity(p, now);
            return;
        }

        double dx = event.getTo().getX() - d.lastX;
        double dy = event.getTo().getY() - d.lastY;
        double dz = event.getTo().getZ() - d.lastZ;
        float dyaw = Math.abs(event.getTo().getYaw() - d.lastYaw);
        float dpitch = Math.abs(event.getTo().getPitch() - d.lastPitch);

        double distSq = dx * dx + dy * dy + dz * dz;

        // Consider either motion or noticeable aim change as activity
        boolean moved = distSq > 0.01;
        boolean rotated = (dyaw > 2.0F || dpitch > 2.0F);

        if (moved || rotated) {
            d.lastX = event.getTo().getX();
            d.lastY = event.getTo().getY();
            d.lastZ = event.getTo().getZ();
            d.lastYaw = event.getTo().getYaw();
            d.lastPitch = event.getTo().getPitch();

            recordNonClickActivity(p, now);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        long now = System.currentTimeMillis();
        recordNonClickActivity(event.getPlayer(), now);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        long now = System.currentTimeMillis();
        recordNonClickActivity(event.getPlayer(), now);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        long now = System.currentTimeMillis();
        switch (event.getAction()) {
            case LEFT_CLICK_AIR, LEFT_CLICK_BLOCK, RIGHT_CLICK_AIR, RIGHT_CLICK_BLOCK -> {
                // Treat swings & clicks as click-activity for macro detection
                recordClickActivity(event.getPlayer(), now);
            }
            default -> {
                // Other interactions (pressure plate stepping, etc.) count as non-click activity
                recordNonClickActivity(event.getPlayer(), now);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) {
            return;
        }
        long now = System.currentTimeMillis();
        recordClickActivity(player, now);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        long now = System.currentTimeMillis();
        recordClickActivity(event.getPlayer(), now);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        long now = System.currentTimeMillis();
        recordNonClickActivity(event.getPlayer(), now);
    }
}

package org.enthusia.playtime.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.enthusia.playtime.data.StorageType;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class PlaytimeConfig {

    private final Storage storage;
    private final Sampling sampling;
    private final Activity activity;
    private final ChatActivity chatActivity;
    private final Joins joins;
    private final Leaderboards leaderboards;
    private final Analytics analytics;
    private final Gui gui;
    private final Placeholders placeholders;
    private final ActionBar actionBar;
    private final PlaytimeAudit playtimeAudit;
    private final Debug debug;

    private PlaytimeConfig(Storage storage,
                           Sampling sampling,
                           Activity activity,
                           ChatActivity chatActivity,
                           Joins joins,
                           Leaderboards leaderboards,
                           Analytics analytics,
                           Gui gui,
                           Placeholders placeholders,
                           ActionBar actionBar,
                           PlaytimeAudit playtimeAudit,
                           Debug debug) {
        this.storage = storage;
        this.sampling = sampling;
        this.activity = activity;
        this.chatActivity = chatActivity;
        this.joins = joins;
        this.leaderboards = leaderboards;
        this.analytics = analytics;
        this.gui = gui;
        this.placeholders = placeholders;
        this.actionBar = actionBar;
        this.playtimeAudit = playtimeAudit;
        this.debug = debug;
    }

    public static PlaytimeConfig load(JavaPlugin plugin) {
        FileConfiguration cfg = plugin.getConfig();

        StorageType storageType = StorageType.SQLITE;
        String storageRaw = stringValue(cfg, List.of("storage.type"), "sqlite").toLowerCase(Locale.ROOT);
        if (storageRaw.equals("mysql") || storageRaw.equals("mariadb")) {
            storageType = StorageType.MYSQL;
        }

        Storage storage = new Storage(
                storageType,
                stringValue(cfg, List.of("storage.sqlite.file"), "playtime.db"),
                new Mysql(
                        stringValue(cfg, List.of("storage.mysql.host"), "localhost"),
                        intValue(cfg, List.of("storage.mysql.port"), 3306),
                        stringValue(cfg, List.of("storage.mysql.database"), "playtime"),
                        stringValue(cfg, List.of("storage.mysql.username"), "root"),
                        stringValue(cfg, List.of("storage.mysql.password"), "password"),
                        booleanValue(cfg, List.of("storage.mysql.use-ssl"), false),
                        Math.max(2, intValue(cfg, List.of("storage.mysql.pool-size"), 10))
                ),
                Math.max(20L, longValue(cfg, List.of("storage.flush-interval-ticks"), 1200L))
        );

        Sampling sampling = new Sampling(
                Math.max(1, intValue(cfg, List.of("sampling.tick-interval"), 20)),
                Math.max(1L, longValue(cfg, List.of("sampling.idle_seconds", "sampling.idle-seconds"), 60L)),
                Math.max(1L, longValue(cfg, List.of("sampling.afk_seconds", "sampling.afk-seconds"), 300L)),
                new Suspicion(
                        booleanValue(cfg, List.of("sampling.suspicion.enabled"), true),
                        Math.max(5L, longValue(cfg, List.of("sampling.suspicion.window_seconds", "sampling.suspicion.window-seconds"), 20L)),
                        Math.max(2, intValue(cfg, List.of("sampling.suspicion.min_swings", "sampling.suspicion.min-swings"), 25)),
                        Math.max(0.0D, doubleValue(cfg, List.of("sampling.suspicion.max_cv", "sampling.suspicion.max-cv"), 0.08D)),
                        Math.max(1L, longValue(cfg, List.of("sampling.suspicion.non_click_grace_seconds", "sampling.suspicion.non-click-grace-seconds"), 10L)),
                        Math.max(1, intValue(cfg, List.of("sampling.suspicion.max-counted-consecutive-minutes"), 10))
                )
        );

        Activity activity = new Activity(
                Math.max(0L, longValue(cfg, List.of("activity.movement-throttle-ms"), 250L)),
                booleanValue(cfg, List.of("activity.count-head-rotation"), true),
                Math.max(0.0D, doubleValue(cfg, List.of("activity.tiny-movement-threshold"), 0.01D)),
                booleanValue(cfg, List.of("activity.suspicious-swing-tracking-enabled"), true)
        );

        ChatActivity chatActivity = new ChatActivity(
                booleanValue(cfg, List.of("chat.count-chat-as-activity"), true),
                booleanValue(cfg, List.of("chat.count-commands-as-activity"), true)
        );

        ZoneId joinZoneId = ZoneId.of(stringValue(cfg, List.of("joins.timezone"), "America/New_York"));
        Joins joins = new Joins(
                intValue(cfg, List.of("joins.retention-days"), -1),
                joinZoneId,
                new FirstJoin(
                        booleanValue(cfg, List.of("joins.first-join.enabled"), true),
                        stringValue(cfg, List.of("joins.first-join.broadcast"),
                                "&a+ &f%player% &7just joined for the first time! Make them feel welcome."),
                        stringListValue(cfg, "joins.first-join.player-message", List.of(
                                "&aWelcome to Enthusia, %player%!",
                                "&7Use &b/tutorial &7for a quick start or ask in chat."
                        )),
                        new Ping(
                                booleanValue(cfg, List.of("joins.first-join.ping.enabled"), true),
                                stringValue(cfg, List.of("joins.first-join.ping.sound"), "BLOCK_NOTE_BLOCK_PLING"),
                                (float) doubleValue(cfg, List.of("joins.first-join.ping.volume"), 1.25D),
                                (float) doubleValue(cfg, List.of("joins.first-join.ping.pitch"), 1.6D)
                        )
                )
        );

        Leaderboards leaderboards = new Leaderboards(
                stringValue(cfg, List.of("leaderboards.default-metric"), "total").toUpperCase(Locale.ROOT),
                stringValue(cfg, List.of("leaderboards.default-range"), "all").toUpperCase(Locale.ROOT),
                Math.max(5, intValue(cfg, List.of("leaderboards.cache-ttl-seconds"), 30)),
                new LeaderboardExport(
                        booleanValue(cfg, List.of("leaderboards.export.enabled"), true),
                        stringValue(cfg, List.of("leaderboards.export.directory"), "public-leaderboards"),
                        Math.max(30, intValue(cfg, List.of("leaderboards.export.interval-seconds"), 300)),
                        new R2Export(
                                booleanValue(cfg, List.of("leaderboards.export.r2.enabled"), false),
                                credentialValue(cfg, "leaderboards.export.r2.account-id", "PLAYTIME_R2_ACCOUNT_ID", "playtime.r2.accountId"),
                                credentialValue(cfg, "leaderboards.export.r2.bucket", "PLAYTIME_R2_BUCKET", "playtime.r2.bucket"),
                                credentialValue(cfg, "leaderboards.export.r2.access-key-id", "PLAYTIME_R2_ACCESS_KEY_ID", "playtime.r2.accessKeyId"),
                                credentialValue(cfg, "leaderboards.export.r2.secret-access-key", "PLAYTIME_R2_SECRET_ACCESS_KEY", "playtime.r2.secretAccessKey"),
                                stringValue(cfg, List.of("leaderboards.export.r2.prefix"), "leaderboards")
                        ),
                        booleanValue(cfg, List.of("leaderboards.export.run-on-reload-close"), false),
                        booleanValue(cfg, List.of("leaderboards.export.run-on-disable"), true),
                        Math.max(1, intValue(cfg, List.of("leaderboards.export.shutdown-timeout-seconds"), 10))
                )
        );

        Analytics analytics = new Analytics(
                new PlanIntegration(booleanValue(cfg, List.of("analytics.plan.enabled"), true)),
                Math.max(1, intValue(cfg, List.of("analytics.hourly-retention-days"), 90))
        );

        Gui gui = new Gui(
                booleanValue(cfg, List.of("gui.enabled"), true),
                stringValue(cfg, List.of("gui.filler-material"), "GRAY_STAINED_GLASS_PANE"),
                new BedrockGui(
                        booleanValue(cfg, List.of("gui.bedrock.enabled"), true),
                        Math.max(3, intValue(cfg, List.of("gui.bedrock.main-menu-rows"), 5)),
                        Math.max(3, intValue(cfg, List.of("gui.bedrock.leaderboard-rows"), 6))
                )
        );

        Placeholders placeholders = new Placeholders(
                booleanValue(cfg, List.of("placeholders.enabled"), true),
                stringValue(cfg, List.of("placeholders.leaderboard-fallback"), ""),
                Math.max(10, intValue(cfg, List.of("placeholders.top-leaderboard-max-rank"), 100))
        );

        ActionBar actionBar = new ActionBar(
                booleanValue(cfg, List.of("ux.actionbar.enabled"), true),
                booleanValue(cfg, List.of("ux.actionbar.show-active"), false),
                booleanValue(cfg, List.of("ux.actionbar.show-idle"), false),
                booleanValue(cfg, List.of("ux.actionbar.show-afk"), true),
                booleanValue(cfg, List.of("ux.actionbar.show-suspicious"), true),
                new ActionBarText(
                        stringValue(cfg, List.of("ux.actionbar.text.active"), "Playing"),
                        stringValue(cfg, List.of("ux.actionbar.text.idle"), "Idle"),
                        stringValue(cfg, List.of("ux.actionbar.text.afk"), "AFK"),
                        stringValue(cfg, List.of("ux.actionbar.text.suspicious"), "Suspicious input")
                )
        );

        PlaytimeAudit playtimeAudit = new PlaytimeAudit(
                booleanValue(cfg, List.of("playtime-audit.enabled"), true),
                Math.max(1, intValue(cfg, List.of("playtime-audit.interval-minutes"), 5)),
                Math.max(1, intValue(cfg, List.of("playtime-audit.max-players-per-tick"), 1)),
                booleanValue(cfg, List.of("playtime-audit.repair-mode"), true),
                booleanValue(cfg, List.of("playtime-audit.debug-log-repairs"), false)
        );

        Debug debug = new Debug(
                booleanValue(cfg, List.of("debug.enabled"), false),
                booleanValue(cfg, List.of("debug.log-suspicious"), true),
                new PerformanceDebug(
                        booleanValue(cfg, List.of("debug.performance.enabled"), false),
                        Math.max(30, intValue(cfg, List.of("debug.performance.log-interval-seconds"), 300))
                )
        );

        return new PlaytimeConfig(storage, sampling, activity, chatActivity, joins, leaderboards, analytics, gui, placeholders, actionBar, playtimeAudit, debug);
    }

    public Storage storage() {
        return storage;
    }

    public Sampling sampling() {
        return sampling;
    }

    public Activity activity() {
        return activity;
    }

    public ChatActivity chatActivity() {
        return chatActivity;
    }

    public Joins joins() {
        return joins;
    }

    public Leaderboards leaderboards() {
        return leaderboards;
    }

    public Analytics analytics() {
        return analytics;
    }

    public Gui gui() {
        return gui;
    }

    public Placeholders placeholders() {
        return placeholders;
    }

    public ActionBar actionBar() {
        return actionBar;
    }

    public PlaytimeAudit playtimeAudit() {
        return playtimeAudit;
    }

    public Debug debug() {
        return debug;
    }

    public StorageType getStorageType() {
        return storage.type;
    }

    public String getSqliteFile() {
        return storage.sqliteFile;
    }

    public String getMysqlHost() {
        return storage.mysql.host;
    }

    public int getMysqlPort() {
        return storage.mysql.port;
    }

    public String getMysqlDatabase() {
        return storage.mysql.database;
    }

    public String getMysqlUsername() {
        return storage.mysql.username;
    }

    public String getMysqlPassword() {
        return storage.mysql.password;
    }

    public boolean isMysqlUseSsl() {
        return storage.mysql.useSsl;
    }

    public int getMysqlPoolSize() {
        return storage.mysql.poolSize;
    }

    public long getFlushIntervalTicks() {
        return storage.flushIntervalTicks;
    }

    public int getIdleSeconds() {
        return (int) sampling.idleSeconds;
    }

    public int getAfkSeconds() {
        return (int) sampling.afkSeconds;
    }

    public boolean countChatAsActivity() {
        return chatActivity.countChatAsActivity;
    }

    public boolean countCommandsAsActivity() {
        return chatActivity.countCommandsAsActivity;
    }

    public int getJoinRetentionDays() {
        return joins.retentionDays;
    }

    public String getJoinTimezoneId() {
        return joins.zoneId.getId();
    }

    public boolean isFirstJoinEnabled() {
        return joins.firstJoin.enabled;
    }

    public String getFirstJoinBroadcast() {
        return joins.firstJoin.broadcast;
    }

    public List<String> getFirstJoinPlayerLines() {
        return joins.firstJoin.playerMessage;
    }

    public boolean isFirstJoinPingEnabled() {
        return joins.firstJoin.ping.enabled;
    }

    public String getFirstJoinPingSound() {
        return joins.firstJoin.ping.sound;
    }

    public float getFirstJoinPingVolume() {
        return joins.firstJoin.ping.volume;
    }

    public float getFirstJoinPingPitch() {
        return joins.firstJoin.ping.pitch;
    }

    public boolean isGuiEnabled() {
        return gui.enabled;
    }

    public boolean isPlaceholdersEnabled() {
        return placeholders.enabled;
    }

    public boolean isDebugEnabled() {
        return debug.enabled;
    }

    public boolean isPlanIntegrationEnabled() {
        return analytics.plan().enabled();
    }

    public int getHourlyAnalyticsRetentionDays() {
        return analytics.hourlyRetentionDays();
    }

    public record Storage(StorageType type, String sqliteFile, Mysql mysql, long flushIntervalTicks) {
    }

    public record Mysql(String host, int port, String database, String username, String password, boolean useSsl, int poolSize) {
    }

    public record Sampling(int tickInterval, long idleSeconds, long afkSeconds, Suspicion suspicion) {
    }

    public record Suspicion(boolean enabled,
                            long windowSeconds,
                            int minSwings,
                            double maxCv,
                            long nonClickGraceSeconds,
                            int maxCountedConsecutiveMinutes) {
    }

    public record Activity(long movementThrottleMs,
                           boolean countHeadRotation,
                           double tinyMovementThreshold,
                           boolean suspiciousSwingTrackingEnabled) {
    }

    public record ChatActivity(boolean countChatAsActivity, boolean countCommandsAsActivity) {
    }

    public record Joins(int retentionDays, ZoneId zoneId, FirstJoin firstJoin) {
    }

    public record FirstJoin(boolean enabled, String broadcast, List<String> playerMessage, Ping ping) {
    }

    public record Ping(boolean enabled, String sound, float volume, float pitch) {
    }

    public record Leaderboards(String defaultMetric, String defaultRange, int cacheTtlSeconds, LeaderboardExport export) {
    }

    public record LeaderboardExport(boolean enabled,
                                    String directory,
                                    int intervalSeconds,
                                    R2Export r2,
                                    boolean runOnReloadClose,
                                    boolean runOnDisable,
                                    int shutdownTimeoutSeconds) {
    }

    public record R2Export(boolean enabled,
                           String accountId,
                           String bucket,
                           String accessKeyId,
                           String secretAccessKey,
                           String prefix) {
    }

    public record Analytics(PlanIntegration plan, int hourlyRetentionDays) {
    }

    public record PlanIntegration(boolean enabled) {
    }

    public record Gui(boolean enabled, String fillerMaterial, BedrockGui bedrock) {
    }

    public record BedrockGui(boolean enabled, int mainMenuRows, int leaderboardRows) {
    }

    public record Placeholders(boolean enabled, String leaderboardFallback, int topLeaderboardMaxRank) {
    }

    public record ActionBar(boolean enabled,
                            boolean showActive,
                            boolean showIdle,
                            boolean showAfk,
                            boolean showSuspicious,
                            ActionBarText text) {
    }

    public record ActionBarText(String active, String idle, String afk, String suspicious) {
    }

    public record PlaytimeAudit(boolean enabled,
                                int intervalMinutes,
                                int maxPlayersPerTick,
                                boolean repairMode,
                                boolean debugLogRepairs) {
    }

    public record Debug(boolean enabled, boolean logSuspicious, PerformanceDebug performance) {
    }

    public record PerformanceDebug(boolean enabled, int logIntervalSeconds) {
    }

    private static String stringValue(FileConfiguration cfg, List<String> paths, String defaultValue) {
        for (String path : paths) {
            String value = cfg.getString(path);
            if (value != null) {
                return value;
            }
        }
        return defaultValue;
    }

    private static String credentialValue(FileConfiguration cfg, String path, String envName, String propertyName) {
        String property = System.getProperty(propertyName);
        if (property != null && !property.isBlank()) {
            return property;
        }
        String env = System.getenv(envName);
        if (env != null && !env.isBlank()) {
            return env;
        }
        return stringValue(cfg, List.of(path), "");
    }

    private static int intValue(FileConfiguration cfg, List<String> paths, int defaultValue) {
        for (String path : paths) {
            if (cfg.isInt(path) || cfg.isLong(path)) {
                return cfg.getInt(path);
            }
        }
        return defaultValue;
    }

    private static long longValue(FileConfiguration cfg, List<String> paths, long defaultValue) {
        for (String path : paths) {
            if (cfg.isLong(path) || cfg.isInt(path)) {
                return cfg.getLong(path);
            }
        }
        return defaultValue;
    }

    private static double doubleValue(FileConfiguration cfg, List<String> paths, double defaultValue) {
        for (String path : paths) {
            if (cfg.isDouble(path) || cfg.isInt(path) || cfg.isLong(path)) {
                return cfg.getDouble(path);
            }
        }
        return defaultValue;
    }

    private static boolean booleanValue(FileConfiguration cfg, List<String> paths, boolean defaultValue) {
        for (String path : paths) {
            if (cfg.isBoolean(path)) {
                return cfg.getBoolean(path);
            }
        }
        return defaultValue;
    }

    private static List<String> stringListValue(FileConfiguration cfg, String path, List<String> defaultValue) {
        if (cfg.isList(path)) {
            List<String> lines = cfg.getStringList(path);
            if (!lines.isEmpty()) {
                return List.copyOf(lines);
            }
        }
        return List.copyOf(new ArrayList<>(defaultValue));
    }
}

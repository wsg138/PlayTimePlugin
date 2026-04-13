package org.enthusia.playtime;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.enthusia.playtime.activity.ActivityTracker;
import org.enthusia.playtime.activity.SessionManager;
import org.enthusia.playtime.api.PlaytimeService;
import org.enthusia.playtime.api.impl.PlaytimeServiceImpl;
import org.enthusia.playtime.bedrock.BedrockSupport;
import org.enthusia.playtime.command.AdminPlaytimeCommand;
import org.enthusia.playtime.command.FirstJoinCommand;
import org.enthusia.playtime.command.PlaytimeCommand;
import org.enthusia.playtime.command.SeenCommand;
import org.enthusia.playtime.config.PlaytimeConfig;
import org.enthusia.playtime.data.DatabaseProvider;
import org.enthusia.playtime.data.PlaytimeRepository;
import org.enthusia.playtime.data.StorageType;
import org.enthusia.playtime.event.PlayerPlaytimeTickEvent;
import org.enthusia.playtime.gui.GuiListener;
import org.enthusia.playtime.placeholders.PlaytimePlaceholderExpansion;
import org.enthusia.playtime.util.AsyncWriteQueue;
import org.enthusia.playtime.joins.FirstJoinWelcomeListener;
import org.enthusia.playtime.joins.JoinLogListener;
import org.enthusia.playtime.gui.admin.AdminGuiClickListener;
import org.enthusia.playtime.skin.HeadCache;
import org.enthusia.playtime.skin.HeadCacheListener;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.time.Instant;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.nio.charset.StandardCharsets;

public final class PlayTimePlugin extends JavaPlugin {

    private static PlayTimePlugin instance;

    private Logger log;

    private PlaytimeConfig configWrapper;
    private DatabaseProvider databaseProvider;
    private PlaytimeRepository repository;
    private ActivityTracker activityTracker;
    private SessionManager sessionManager;
    private PlaytimeServiceImpl playtimeService;
    private AsyncWriteQueue writeQueue;
    private BedrockSupport bedrockSupport;
    private HeadCache headCache;

    public static PlayTimePlugin getInstance() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        this.log = getLogger();

        saveDefaultConfig();
        reloadConfig();
        int addedConfig = ensureConfigDefaults();
        if (addedConfig > 0) {
            log.info("Added " + addedConfig + " missing config setting" + (addedConfig == 1 ? "" : "s") + " using defaults.");
        }
        this.configWrapper = new PlaytimeConfig(this);

        // Bedrock support helper (Floodgate/Geyser via reflection).
        this.bedrockSupport = new BedrockSupport(this);

        // Database & repository
        try {
            StorageType type = configWrapper.getStorageType();
            this.databaseProvider = new DatabaseProvider(this, configWrapper);
            this.databaseProvider.init(type);
            this.repository = new PlaytimeRepository(this, databaseProvider, configWrapper);
            this.repository.initSchema();
        } catch (Exception ex) {
            log.log(Level.SEVERE, "Failed to initialize storage, disabling plugin.", ex);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.headCache = new HeadCache(this);

        // Capture heads for any players already online (plugin reload scenario)
        for (Player online : Bukkit.getOnlinePlayers()) {
            headCache.updateHead(online);
        }

        // Async write queue
        this.writeQueue = new AsyncWriteQueue(this, repository, configWrapper.getFlushIntervalTicks());

        // Session manager
        this.sessionManager = new SessionManager();

        // Activity tracker (events + AFK/IDLE/SUS classification)
        this.activityTracker = new ActivityTracker(this, configWrapper, sessionManager);
        getServer().getPluginManager().registerEvents(activityTracker, this);

        // GUI listener (blocks item grabs from our menus)
        getServer().getPluginManager().registerEvents(new GuiListener(), this);
        // Cache heads on join so offline heads keep the correct skin
        getServer().getPluginManager().registerEvents(new HeadCacheListener(this, headCache), this);

        // Playtime service
        this.playtimeService = new PlaytimeServiceImpl(repository, activityTracker, sessionManager);
        Bukkit.getServicesManager().register(PlaytimeService.class,
                playtimeService, this, ServicePriority.Normal);

        // Commands
        PlaytimeCommand playtimeCommand = new PlaytimeCommand(this);
        AdminPlaytimeCommand adminCommand = new AdminPlaytimeCommand(this);
        FirstJoinCommand firstJoinCommand = new FirstJoinCommand(this, repository, configWrapper);
        SeenCommand seenCommand = new SeenCommand(this, repository, configWrapper);

        if (getCommand("playtime") != null) {
            getCommand("playtime").setExecutor(playtimeCommand);
            getCommand("playtime").setTabCompleter(playtimeCommand);
        }
        if (getCommand("roman") != null) {
            getCommand("roman").setExecutor(playtimeCommand);
            getCommand("roman").setTabCompleter(playtimeCommand);
        }
        if (getCommand("firstjoin") != null) {
            getCommand("firstjoin").setExecutor(firstJoinCommand);
            getCommand("firstjoin").setTabCompleter(firstJoinCommand);
        }
        if (getCommand("seen") != null) {
            getCommand("seen").setExecutor(seenCommand);
            getCommand("seen").setTabCompleter(seenCommand);
        }

        // Hook PlaceholderAPI
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null
                && configWrapper.isPlaceholdersEnabled()) {
            new PlaytimePlaceholderExpansion(this, playtimeService).register();
            log.info("Registered PlaceholderAPI expansion.");
        }

        // Optional ProtocolLib log
        if (Bukkit.getPluginManager().getPlugin("ProtocolLib") != null) {
            log.info("ProtocolLib detected, packet hooks can be enabled later.");
        }

        // Start per-minute tick
        startMinuteTick();

        // Start join log purge task (hourly)
        startJoinPurgeTask();

        log.info("EnthusiaPlaytime enabled.");

        // join logging & admin GUIs
        getServer().getPluginManager().registerEvents(new JoinLogListener(this), this);
        getServer().getPluginManager().registerEvents(new FirstJoinWelcomeListener(this, configWrapper), this);
        getServer().getPluginManager().registerEvents(new AdminGuiClickListener(), this);
    }

    @Override
    public void onDisable() {
        // Flush queue synchronously
        if (writeQueue != null) {
            writeQueue.flushNow();
        }

        // Save head cache
        if (headCache != null) {
            headCache.save();
        }

        // Close DB
        if (databaseProvider != null) {
            databaseProvider.shutdown();
        }

        instance = null;
        log.info("EnthusiaPlaytime disabled.");
    }

    private void startMinuteTick() {
        new BukkitRunnable() {
            @Override
            public void run() {
                long nowMillis = System.currentTimeMillis();
                Instant now = Instant.ofEpochMilli(nowMillis);

                for (Player player : Bukkit.getOnlinePlayers()) {
                    // Determine state
                    var state = activityTracker.getState(player.getUniqueId(), nowMillis);

                    // Let service decide where to credit minutes
                    int active = 0;
                    int afk = 0;
                    switch (state) {
                        case ACTIVE, SUSPICIOUS -> active = 1;
                        case IDLE, AFK -> afk = 1;
                    }

                    // Dispatch event so other plugins can listen
                    PlayerPlaytimeTickEvent event = new PlayerPlaytimeTickEvent(player, state, active, afk);
                    Bukkit.getPluginManager().callEvent(event);

                    if (event.isCancelled()) {
                        continue;
                    }

                    // Queue persistence
                    int finalActive = event.getActiveMinutes();
                    int finalAfk = event.getAfkMinutes();
                    if (finalActive == 0 && finalAfk == 0) continue;

                    writeQueue.enqueue(() -> {
                        try {
                            repository.recordMinute(player.getUniqueId(), now, finalActive, finalAfk);
                        } catch (Exception e) {
                            log.log(Level.WARNING, "Failed to persist minute for " + player.getName(), e);
                        }
                    });

                    // Reward checks happen inside RewardManager listening to the event
                }
            }
        }.runTaskTimer(this, 20L * 60, 20L * 60);
    }

    private void startJoinPurgeTask() {
        long hourTicks = 20L * 60 * 60;
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    repository.purgeOldJoins(configWrapper.getJoinRetentionDays());
                } catch (Exception ex) {
                    log.log(Level.WARNING, "Failed to purge old joins.", ex);
                }
            }
        }.runTaskTimerAsynchronously(this, hourTicks, hourTicks);
    }

    /**
     * Fill in any missing config entries using the packaged defaults, without overwriting user edits.
     */
    private int ensureConfigDefaults() {
        FileConfiguration config = getConfig();
        int added = 0;

        try (InputStream in = getResource("config.yml")) {
            if (in == null) {
                log.warning("Default config.yml missing from jar; cannot backfill defaults.");
                return 0;
            }

            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8));

            for (String path : defaults.getKeys(true)) {
                if (defaults.isConfigurationSection(path)) {
                    continue;
                }
                if (config.isSet(path)) {
                    continue;
                }
                config.set(path, defaults.get(path));
                added++;
            }

            if (added > 0) {
                try {
                    saveConfig();
                } catch (Exception ex) {
                    log.log(Level.WARNING, "Failed to save config after adding defaults.", ex);
                }
            }
        } catch (Exception ex) {
            log.log(Level.WARNING, "Failed to load default config.yml for backfill.", ex);
        }

        return added;
    }

    public PlaytimeConfig getPlaytimeConfig() {
        return configWrapper;
    }

    public PlaytimeRepository getRepository() {
        return repository;
    }

    public BedrockSupport getBedrockSupport() {
        return bedrockSupport;
    }

    public ActivityTracker getActivityTracker() {
        return activityTracker;
    }

    public SessionManager getSessionManager() {
        return sessionManager;
    }

    public HeadCache getHeadCache() {
        return headCache;
    }
}

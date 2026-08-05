package org.enthusia.playtime;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.enthusia.playtime.api.PlaytimeService;
import org.enthusia.playtime.bedrock.BedrockSupport;
import org.enthusia.playtime.command.FirstJoinCommand;
import org.enthusia.playtime.command.PlaytimeCommand;
import org.enthusia.playtime.command.SeenCommand;
import org.enthusia.playtime.config.ConfigMigrator;
import org.enthusia.playtime.config.PlaytimeConfig;
import org.enthusia.playtime.gui.GuiListener;
import org.enthusia.playtime.joins.FirstJoinWelcomeListener;
import org.enthusia.playtime.joins.JoinLogListener;
import org.enthusia.playtime.placeholders.PlaytimePlaceholderExpansion;
import org.enthusia.playtime.skin.HeadCacheListener;
import org.enthusia.playtime.service.PlaytimeRuntime;
import org.enthusia.playtime.util.AsyncWriteQueue;

import java.io.File;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.function.Consumer;

public class PlayTimePlugin extends JavaPlugin {
    enum ReloadStage {
        CONFIG_LOADED, OLD_PREPARED, CANDIDATE_CREATED, OLD_COMMITTED,
        CANDIDATE_PUBLISHED, OLD_CLOSED, PLACEHOLDER_REFRESH
    }
    private static volatile Consumer<ReloadStage> reloadProbe = ignored -> { };

    private final Object runtimeLock = new Object();
    private final AtomicBoolean sqliteStartupBackupPending = new AtomicBoolean(false);
    private volatile boolean allowInitialSqliteCreation;
    private volatile Optional<PlaytimeRuntime> activeRuntime = Optional.empty();
    private Optional<PlaytimePlaceholderExpansion> placeholderExpansion = Optional.empty();
    private PlaceholderLifecycle placeholderLifecycle = new PlaceholderLifecycle() {
        @Override public boolean available() {
            return Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
        }
        @Override public boolean registered() {
            return placeholderExpansion.isPresent();
        }
        @Override public void unregister() {
            placeholderExpansion.ifPresent(PlaytimePlaceholderExpansion::unregister);
            placeholderExpansion = Optional.empty();
        }
        @Override public void register() {
            PlaytimePlaceholderExpansion expansion = new PlaytimePlaceholderExpansion(PlayTimePlugin.this);
            expansion.register();
            placeholderExpansion = Optional.of(expansion);
        }
    };

    private BedrockSupport bedrockSupport;

    @Override
    public void onEnable() {
        boolean existingConfig = new File(getDataFolder(), "config.yml").isFile();
        this.allowInitialSqliteCreation = !existingConfig;
        this.sqliteStartupBackupPending.set(existingConfig);

        ConfigMigrator migrator = new ConfigMigrator(this);
        migrator.migrateConfig();

        this.bedrockSupport = new BedrockSupport(this);
        registerAdapters();

        if (!reloadPluginRuntime(null)) {
            this.allowInitialSqliteCreation = false;
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.allowInitialSqliteCreation = false;
        refreshPlaceholderExpansion();

        getLogger().info("EnthusiaPlaytime enabled.");
    }

    @Override
    public void onDisable() {
        this.allowInitialSqliteCreation = false;
        this.sqliteStartupBackupPending.set(false);
        placeholderExpansion.ifPresent(PlaytimePlaceholderExpansion::unregister);
        placeholderExpansion = Optional.empty();

        PlaytimeRuntime existing = activeRuntime.orElse(null);
        activeRuntime = Optional.empty();
        if (existing != null) {
            try {
                existing.close(false);
            } catch (Exception exception) {
                getLogger().log(Level.SEVERE, "Failed to close playtime runtime during disable.", exception);
            }
        }
        Bukkit.getServicesManager().unregisterAll(this);
    }

    public boolean reloadPluginRuntime() {
        return reloadPluginRuntime("reload");
    }

    public boolean mayCreateInitialSqliteDatabase() {
        return allowInitialSqliteCreation;
    }

    public boolean claimSqliteStartupBackup() {
        return sqliteStartupBackupPending.compareAndSet(true, false);
    }

    private boolean reloadPluginRuntime(String reason) {
        synchronized (runtimeLock) {
            return reloadPluginRuntimeLocked(reason);
        }
    }

    private boolean reloadPluginRuntimeLocked(String reason) {
        PlaytimeConfig config;
        try {
            reloadConfig();
            new ConfigMigrator(this).migrateConfig();
            config = PlaytimeConfig.load(this);
            reloadProbe.accept(ReloadStage.CONFIG_LOADED);
        } catch (Exception exception) {
            getLogger().log(Level.SEVERE, "Failed to parse playtime config. Existing runtime was left running.", exception);
            return false;
        }

        PlaytimeRuntime.RuntimeState state = null;
        PlaytimeRuntime oldRuntime = this.activeRuntime.orElse(null);
        if (oldRuntime != null) {
            try {
                PlaytimeRuntime.HandoffPreparation preparation = oldRuntime.prepareRuntimeHandoff();
                if (preparation.result() != AsyncWriteQueue.TransitionResult.SUCCESS) {
                    getLogger().warning("Playtime runtime reload aborted because queued writes could not be handed off: " + preparation.result());
                    return false;
                }
                state = preparation.state();
                reloadProbe.accept(ReloadStage.OLD_PREPARED);
            } catch (Exception exception) {
                oldRuntime.abortRuntimeHandoff();
                getLogger().log(Level.SEVERE,
                        "Failed to prepare the existing playtime runtime for reload. Existing runtime was left running.",
                        exception);
                return false;
            }
        }

        PlaytimeRuntime newRuntime = null;
        boolean oldCommitted = false;
        try {
            newRuntime = PlaytimeRuntime.create(this, config, state);
            reloadProbe.accept(ReloadStage.CANDIDATE_CREATED);
            if (oldRuntime != null) {
                AsyncWriteQueue.TransitionResult result = oldRuntime.commitRuntimeHandoff();
                if (result != AsyncWriteQueue.TransitionResult.SUCCESS) {
                    throw new IllegalStateException("Old runtime handoff did not complete: " + result);
                }
                oldCommitted = true;
                reloadProbe.accept(ReloadStage.OLD_COMMITTED);
            }
            this.activeRuntime = Optional.of(newRuntime);
            reloadProbe.accept(ReloadStage.CANDIDATE_PUBLISHED);
            if (oldRuntime != null) {
                try {
                    oldRuntime.close(true);
                    reloadProbe.accept(ReloadStage.OLD_CLOSED);
                } catch (Exception closeException) {
                    getLogger().log(Level.WARNING, "New playtime runtime is active, but the old runtime did not close cleanly.", closeException);
                }
            }
            try {
                reloadProbe.accept(ReloadStage.PLACEHOLDER_REFRESH);
                refreshPlaceholderExpansion(reason != null);
            } catch (RuntimeException integrationFailure) {
                getLogger().log(Level.WARNING,
                        "New playtime runtime is active, but PlaceholderAPI refresh failed.", integrationFailure);
            }
            if (reason != null) {
                getLogger().info("Playtime runtime reloaded successfully. Flush interval="
                        + config.getFlushIntervalTicks() + " ticks, leaderboard export="
                        + (config.leaderboards().export().enabled() ? config.leaderboards().export().intervalSeconds() + "s" : "disabled")
                        + ", audit=" + (config.playtimeAudit().enabled() ? config.playtimeAudit().intervalMinutes() + "m" : "disabled") + ".");
            }
            return true;
        } catch (Exception exception) {
            if (oldCommitted) {
                this.activeRuntime = Optional.of(newRuntime);
                if (oldRuntime != null) {
                    try {
                        oldRuntime.close(true);
                    } catch (Exception closeFailure) {
                        getLogger().log(Level.WARNING,
                                "Committed replacement is active, but old runtime cleanup failed.", closeFailure);
                    }
                }
                getLogger().log(Level.SEVERE,
                        "Playtime runtime committed; a post-commit step failed and the new runtime remains active.",
                        exception);
                return true;
            }
            if (newRuntime != null) {
                try {
                    newRuntime.close(false);
                } catch (Exception cleanupException) {
                    getLogger().log(Level.WARNING, "Failed to clean up replacement runtime after reload failure.", cleanupException);
                }
            }
            if (oldRuntime != null) {
                oldRuntime.abortRuntimeHandoff();
            }
            getLogger().log(Level.SEVERE, "Failed to " + (reason == null ? "initialize" : "reload")
                    + " playtime runtime. Existing runtime was left running when available.", exception);
            return false;
        }
    }

    public PlaytimeRuntime runtime() {
        return activeRuntime.orElse(null);
    }

    public PlaytimeConfig getRuntimeConfig() {
        PlaytimeRuntime current = activeRuntime.orElse(null);
        return current == null ? PlaytimeConfig.load(this) : current.config();
    }

    public BedrockSupport getBedrockSupport() {
        return bedrockSupport;
    }

    public PlaytimeService getPlaytimeService() {
        PlaytimeRuntime current = activeRuntime.orElse(null);
        return current == null ? null : current.playtimeService();
    }

    static void setReloadProbeForTesting(Consumer<ReloadStage> probe) {
        reloadProbe = probe == null ? ignored -> { } : probe;
    }

    void setPlaceholderLifecycleForTesting(PlaceholderLifecycle lifecycle) {
        placeholderLifecycle = lifecycle;
    }

    void refreshPlaceholderExpansionForTesting() {
        refreshPlaceholderExpansion(true);
    }

    private void registerAdapters() {
        PlaytimeCommand playtimeCommand = new PlaytimeCommand(this);
        FirstJoinCommand firstJoinCommand = new FirstJoinCommand(this);
        SeenCommand seenCommand = new SeenCommand(this);

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

        getServer().getPluginManager().registerEvents(new GuiListener(), this);
        getServer().getPluginManager().registerEvents(new HeadCacheListener(this), this);
        getServer().getPluginManager().registerEvents(new JoinLogListener(this), this);
        getServer().getPluginManager().registerEvents(new FirstJoinWelcomeListener(this), this);
    }

    private void refreshPlaceholderExpansion() {
        refreshPlaceholderExpansion(false);
    }

    private void refreshPlaceholderExpansion(boolean replaceExisting) {
        boolean enabled = placeholderLifecycle.available() && getRuntimeConfig().isPlaceholdersEnabled();

        if (!enabled) {
            placeholderLifecycle.unregister();
            return;
        }

        if (replaceExisting && placeholderLifecycle.registered()) {
            placeholderLifecycle.unregister();
        }
        if (!placeholderLifecycle.registered()) {
            placeholderLifecycle.register();
            getLogger().info("Registered PlaceholderAPI expansion.");
        }
    }

    interface PlaceholderLifecycle {
        boolean available();
        boolean registered();
        void unregister();
        void register();
    }
}

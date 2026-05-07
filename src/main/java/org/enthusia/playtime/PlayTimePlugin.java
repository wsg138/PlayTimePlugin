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

import java.util.logging.Level;

public final class PlayTimePlugin extends JavaPlugin {

    private volatile PlaytimeRuntime runtime;
    private BedrockSupport bedrockSupport;
    private PlaytimePlaceholderExpansion placeholderExpansion;

    @Override
    public void onEnable() {
        ConfigMigrator migrator = new ConfigMigrator(this);
        migrator.migrateConfig();
        migrator.backupSkinsIfNeeded();

        this.bedrockSupport = new BedrockSupport(this);
        registerAdapters();

        if (!reloadPluginRuntime(null)) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        refreshPlaceholderExpansion();

        getLogger().info("EnthusiaPlaytime enabled.");
    }

    @Override
    public void onDisable() {
        if (placeholderExpansion != null) {
            placeholderExpansion.unregister();
            placeholderExpansion = null;
        }
        PlaytimeRuntime existing = runtime;
        runtime = null;
        if (existing != null) {
            existing.close(false);
        }
        Bukkit.getServicesManager().unregisterAll(this);
    }

    public synchronized boolean reloadPluginRuntime() {
        return reloadPluginRuntime("reload");
    }

    private synchronized boolean reloadPluginRuntime(String reason) {
        PlaytimeConfig config;
        try {
            reloadConfig();
            new ConfigMigrator(this).migrateConfig();
            config = PlaytimeConfig.load(this);
        } catch (Exception exception) {
            getLogger().log(Level.SEVERE, "Failed to parse playtime config. Existing runtime was left running.", exception);
            return false;
        }

        PlaytimeRuntime.RuntimeState state = null;
        PlaytimeRuntime oldRuntime = this.runtime;
        if (oldRuntime != null) {
            state = oldRuntime.snapshotState();
            oldRuntime.close(true);
            this.runtime = null;
        }

        try {
            PlaytimeRuntime newRuntime = new PlaytimeRuntime(this, config, state);
            this.runtime = newRuntime;
            refreshPlaceholderExpansion();
            if (reason != null) {
                getLogger().info("Playtime runtime reloaded successfully. Flush interval="
                        + config.getFlushIntervalTicks() + " ticks, leaderboard export="
                        + (config.leaderboards().export().enabled() ? config.leaderboards().export().intervalSeconds() + "s" : "disabled")
                        + ", audit=" + (config.playtimeAudit().enabled() ? config.playtimeAudit().intervalMinutes() + "m" : "disabled") + ".");
            }
            return true;
        } catch (Exception exception) {
            getLogger().log(Level.SEVERE, "Failed to " + (reason == null ? "initialize" : "reload")
                    + " playtime runtime after the previous runtime was closed. A server restart may be needed.", exception);
            return false;
        }
    }

    public PlaytimeRuntime runtime() {
        return runtime;
    }

    public PlaytimeConfig getRuntimeConfig() {
        PlaytimeRuntime current = runtime;
        return current == null ? PlaytimeConfig.load(this) : current.config();
    }

    public BedrockSupport getBedrockSupport() {
        return bedrockSupport;
    }

    public PlaytimeService getPlaytimeService() {
        PlaytimeRuntime current = runtime;
        return current == null ? null : current.playtimeService();
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
        boolean papiPresent = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
        boolean enabled = papiPresent && getRuntimeConfig().isPlaceholdersEnabled();

        if (!enabled) {
            if (placeholderExpansion != null) {
                placeholderExpansion.unregister();
                placeholderExpansion = null;
            }
            return;
        }

        if (placeholderExpansion == null) {
            placeholderExpansion = new PlaytimePlaceholderExpansion(this);
            placeholderExpansion.register();
            getLogger().info("Registered PlaceholderAPI expansion.");
        }
    }

}

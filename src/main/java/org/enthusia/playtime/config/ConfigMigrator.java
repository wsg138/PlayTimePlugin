package org.enthusia.playtime.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

public final class ConfigMigrator {

    public static final int CURRENT_CONFIG_VERSION = 2;

    private final JavaPlugin plugin;

    public ConfigMigrator(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public MigrationResult migrateConfig() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();

        FileConfiguration config = plugin.getConfig();
        int existingVersion = config.getInt("config-version", 0);
        List<String> added = new ArrayList<>();
        boolean changed = false;
        boolean backedUp = false;

        try (InputStream in = plugin.getResource("config.yml")) {
            if (in == null) {
                plugin.getLogger().warning("Default config.yml missing from jar; cannot backfill defaults.");
                return new MigrationResult(existingVersion, existingVersion, List.of(), false);
            }

            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            if (existingVersion < CURRENT_CONFIG_VERSION) {
                backup("config.yml");
                backedUp = true;
            }

            for (String path : defaults.getKeys(true)) {
                if (defaults.isConfigurationSection(path) || config.isSet(path)) {
                    continue;
                }
                config.set(path, defaults.get(path));
                added.add(path);
                changed = true;
            }

            if (existingVersion < CURRENT_CONFIG_VERSION) {
                config.set("config-version", CURRENT_CONFIG_VERSION);
                changed = true;
            }

            if (changed) {
                plugin.saveConfig();
                plugin.reloadConfig();
            }
        } catch (Exception exception) {
            plugin.getLogger().log(Level.WARNING, "Config migration failed. Existing config was left in place; keep the backup if one was created.", exception);
        }

        if (!added.isEmpty()) {
            plugin.getLogger().info("Added missing config keys: " + String.join(", ", added));
        }
        if (existingVersion < CURRENT_CONFIG_VERSION) {
            plugin.getLogger().info("Migrated config.yml from version " + existingVersion + " to " + CURRENT_CONFIG_VERSION
                    + (backedUp ? " after creating a backup." : "."));
        }

        return new MigrationResult(existingVersion, CURRENT_CONFIG_VERSION, List.copyOf(added), backedUp);
    }

    public void backupSkinsIfNeeded() {
        File skins = new File(plugin.getDataFolder(), "skins.yml");
        if (!skins.exists()) {
            return;
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(skins);
        int version = config.getInt("meta.version", 1);
        if (version >= 1) {
            return;
        }
        try {
            backup("skins.yml");
            config.set("meta.version", 1);
            config.save(skins);
        } catch (Exception exception) {
            plugin.getLogger().log(Level.WARNING, "Failed to add skins.yml metadata; existing skin cache remains usable.", exception);
        }
    }

    private void backup(String fileName) throws Exception {
        File source = new File(plugin.getDataFolder(), fileName);
        if (!source.exists()) {
            return;
        }
        File backups = new File(plugin.getDataFolder(), "backups");
        Files.createDirectories(backups.toPath());
        String stamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now()).replace(':', '-');
        File target = new File(backups, fileName + "." + stamp + ".bak");
        Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        plugin.getLogger().info("Backed up " + fileName + " to " + target.getName());
    }

    public record MigrationResult(int oldVersion, int newVersion, List<String> addedKeys, boolean backedUp) {
    }
}

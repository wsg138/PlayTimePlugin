package org.enthusia.playtime.config;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;

public final class ConfigMigrator {

    public static final int CURRENT_CONFIG_VERSION = 4;
    static final String LAST_GOOD_NAME = "config.yml.last-good";
    static final String BROKEN_NAME = "config.yml.broken";

    private final JavaPlugin plugin;

    public ConfigMigrator(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public MigrationResult migrateConfig() {
        File dataFolder = plugin.getDataFolder();
        File configFile = new File(dataFolder, "config.yml");
        File lastGood = backupFile(LAST_GOOD_NAME);
        File broken = backupFile(BROKEN_NAME);
        boolean backedUp = false;
        boolean restored = false;

        try {
            Files.createDirectories(dataFolder.toPath());
            if (!configFile.isFile()) {
                if (lastGood.isFile()) {
                    replaceFile(lastGood.toPath(), configFile.toPath());
                    restored = true;
                    plugin.getLogger().warning("config.yml was missing; restored the rolling last-good configuration.");
                } else if (!hasPersistentDataBesidesConfig(dataFolder)) {
                    plugin.saveDefaultConfig();
                } else {
                    throw new IllegalStateException("Established EnthusiaPlaytime data is present, but config.yml "
                            + "and backups/" + LAST_GOOD_NAME + " are both missing. Refusing to guess storage settings.");
                }
            }

            YamlConfiguration config;
            try {
                config = loadStrict(configFile);
            } catch (Exception malformed) {
                replaceFile(configFile.toPath(), broken.toPath());
                backedUp = true;
                if (lastGood.isFile()) {
                    replaceFile(lastGood.toPath(), configFile.toPath());
                    config = loadStrict(configFile);
                    restored = true;
                    plugin.getLogger().log(Level.WARNING,
                            "config.yml was malformed. Preserved it as backups/" + BROKEN_NAME
                                    + " and restored backups/" + LAST_GOOD_NAME + ".",
                            malformed);
                } else if (!hasPersistentDataBesidesConfig(dataFolder)) {
                    Files.deleteIfExists(configFile.toPath());
                    plugin.saveDefaultConfig();
                    config = loadStrict(configFile);
                    restored = true;
                    plugin.getLogger().log(Level.WARNING,
                            "config.yml was malformed on an otherwise new installation. Preserved it as backups/"
                                    + BROKEN_NAME + " and generated defaults.", malformed);
                } else {
                    throw new IllegalStateException("config.yml is malformed and no rolling last-good config exists. "
                            + "The broken file was preserved at backups/" + BROKEN_NAME + ".", malformed);
                }
            }

            YamlConfiguration defaults = loadDefaults();
            int existingVersion = config.getInt("config-version", 0);
            List<String> repaired = repairConfig(config, defaults);
            if (existingVersion < CURRENT_CONFIG_VERSION) {
                config.set("config-version", CURRENT_CONFIG_VERSION);
                repaired.add("config-version");
            }

            if (!repaired.isEmpty()) {
                if (!backedUp) {
                    replaceFile(configFile.toPath(), broken.toPath());
                    backedUp = true;
                }
                config.save(configFile);
                plugin.getLogger().warning("Repaired config.yml values using defaults: "
                        + String.join(", ", repaired));
            }

            plugin.reloadConfig();
            return new MigrationResult(existingVersion, CURRENT_CONFIG_VERSION,
                    List.copyOf(repaired), backedUp, restored);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to recover and validate config.yml safely.", exception);
        }
    }

    public void markCurrentConfigGood() {
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        try {
            loadStrict(configFile);
            File target = backupFile(LAST_GOOD_NAME);
            replaceFile(configFile.toPath(), target.toPath());
        } catch (Exception exception) {
            plugin.getLogger().log(Level.WARNING,
                    "Runtime started, but the rolling last-good config backup could not be replaced.", exception);
        }
    }

    static List<String> repairConfig(YamlConfiguration config, YamlConfiguration defaults) {
        List<String> repaired = new ArrayList<>();

        for (String path : defaults.getKeys(true)) {
            if (!defaults.isConfigurationSection(path)) {
                continue;
            }
            Object actual = config.get(path);
            if (actual != null && !config.isConfigurationSection(path)) {
                config.set(path, null);
                repaired.add(path);
            }
        }

        ConfigurationSection configuredTiers = config.getConfigurationSection("numerals.tiers");
        boolean preserveCustomTiers = configuredTiers != null && !configuredTiers.getKeys(false).isEmpty();
        for (String path : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(path)) {
                continue;
            }
            if (preserveCustomTiers && path.startsWith("numerals.tiers.")) {
                continue;
            }
            Object expected = defaults.get(path);
            Object actual = config.get(path);
            if (actual == null || !compatibleType(actual, expected)) {
                config.set(path, expected);
                repaired.add(path);
            }
        }

        repairChoice(config, defaults, repaired, "storage.type", Set.of("sqlite", "mysql", "mariadb"));
        repairChoice(config, defaults, repaired, "leaderboards.default-metric", Set.of("active", "total", "afk"));
        repairChoice(config, defaults, repaired, "leaderboards.default-range", Set.of("all", "today", "7d", "30d"));
        repairNonBlank(config, defaults, repaired, "storage.sqlite.file");
        repairNonBlank(config, defaults, repaired, "storage.mysql.host");
        repairNonBlank(config, defaults, repaired, "storage.mysql.database");
        repairZone(config, defaults, repaired, "joins.timezone");
        repairMaterial(config, defaults, repaired, "gui.filler-material");

        repairIntegerRange(config, defaults, repaired, "storage.mysql.port", 1, 65535);
        repairIntegerRange(config, defaults, repaired, "storage.mysql.pool-size", 2, 100);
        repairLongRange(config, defaults, repaired, "storage.flush-interval-ticks", 20L, 72_000L);
        repairIntegerRange(config, defaults, repaired, "sampling.tick-interval", 1, 1200);
        repairLongRange(config, defaults, repaired, "sampling.idle-seconds", 1L, 86_400L);
        repairLongRange(config, defaults, repaired, "sampling.afk-seconds", 1L, 86_400L);
        repairLongRange(config, defaults, repaired, "sampling.suspicion.window-seconds", 5L, 3600L);
        repairIntegerRange(config, defaults, repaired, "sampling.suspicion.min-swings", 2, 100_000);
        repairDoubleRange(config, defaults, repaired, "sampling.suspicion.max-cv", 0.0D, 1000.0D);
        repairLongRange(config, defaults, repaired, "sampling.suspicion.non-click-grace-seconds", 1L, 86_400L);
        repairIntegerRange(config, defaults, repaired,
                "sampling.suspicion.max-counted-consecutive-minutes", 1, 100_000);
        repairLongRange(config, defaults, repaired, "activity.movement-throttle-ms", 0L, 86_400_000L);
        repairDoubleRange(config, defaults, repaired, "activity.tiny-movement-threshold", 0.0D, 1_000_000.0D);
        repairIntegerRange(config, defaults, repaired, "joins.retention-days", -1, 365_000);
        repairDoubleRange(config, defaults, repaired, "joins.first-join.ping.volume", 0.0D, 100.0D);
        repairDoubleRange(config, defaults, repaired, "joins.first-join.ping.pitch", 0.0D, 2.0D);
        repairIntegerRange(config, defaults, repaired, "leaderboards.cache-ttl-seconds", 5, 86_400);
        repairIntegerRange(config, defaults, repaired, "leaderboards.export.interval-seconds", 30, 86_400);
        repairIntegerRange(config, defaults, repaired, "leaderboards.export.shutdown-timeout-seconds", 1, 300);
        repairIntegerRange(config, defaults, repaired, "playtime-audit.interval-minutes", 1, 10_080);
        repairIntegerRange(config, defaults, repaired, "playtime-audit.max-players-per-tick", 1, 10_000);
        repairIntegerRange(config, defaults, repaired, "analytics.hourly-retention-days", 1, 3650);
        repairIntegerRange(config, defaults, repaired, "gui.bedrock.main-menu-rows", 3, 6);
        repairIntegerRange(config, defaults, repaired, "gui.bedrock.leaderboard-rows", 3, 6);
        repairIntegerRange(config, defaults, repaired, "placeholders.top-leaderboard-max-rank", 10, 10_000);
        repairIntegerRange(config, defaults, repaired, "debug.performance.log-interval-seconds", 30, 86_400);

        return repaired;
    }

    static boolean hasPersistentDataBesidesConfig(File dataFolder) {
        File[] entries = dataFolder.listFiles();
        if (entries == null) {
            return false;
        }
        for (File entry : entries) {
            if (!entry.getName().equals("config.yml")) {
                return true;
            }
        }
        return false;
    }

    static YamlConfiguration loadStrict(File file) throws Exception {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.load(file);
        return configuration;
    }

    private YamlConfiguration loadDefaults() throws IOException {
        try (InputStream input = plugin.getResource("config.yml")) {
            if (input == null) {
                throw new IOException("Default config.yml is missing from the plugin jar.");
            }
            return YamlConfiguration.loadConfiguration(
                    new InputStreamReader(input, StandardCharsets.UTF_8));
        }
    }

    private File backupFile(String name) {
        return new File(new File(plugin.getDataFolder(), "backups"), name);
    }

    private static boolean compatibleType(Object actual, Object expected) {
        if (expected instanceof Number) {
            return actual instanceof Number;
        }
        if (expected instanceof List<?>) {
            if (!(actual instanceof List<?> list)) {
                return false;
            }
            return list.stream().allMatch(String.class::isInstance);
        }
        return expected == null || expected.getClass().isInstance(actual);
    }

    private static void repairChoice(YamlConfiguration config, YamlConfiguration defaults,
                                     List<String> repaired, String path, Set<String> allowed) {
        String value = config.getString(path, "").toLowerCase(Locale.ROOT);
        if (!allowed.contains(value)) {
            replaceWithDefault(config, defaults, repaired, path);
        }
    }

    private static void repairNonBlank(YamlConfiguration config, YamlConfiguration defaults,
                                       List<String> repaired, String path) {
        String value = config.getString(path, "");
        if (value.isBlank()) {
            replaceWithDefault(config, defaults, repaired, path);
        }
    }

    private static void repairZone(YamlConfiguration config, YamlConfiguration defaults,
                                   List<String> repaired, String path) {
        try {
            ZoneId.of(config.getString(path, ""));
        } catch (Exception ignored) {
            replaceWithDefault(config, defaults, repaired, path);
        }
    }

    private static void repairMaterial(YamlConfiguration config, YamlConfiguration defaults,
                                       List<String> repaired, String path) {
        String value = config.getString(path, "");
        Material material = Material.matchMaterial(value);
        if (material == null || !material.isItem()) {
            replaceWithDefault(config, defaults, repaired, path);
        }
    }

    private static void repairIntegerRange(YamlConfiguration config, YamlConfiguration defaults,
                                           List<String> repaired, String path, int minimum, int maximum) {
        if (!(config.get(path) instanceof Number number)
                || number.longValue() < minimum || number.longValue() > maximum) {
            replaceWithDefault(config, defaults, repaired, path);
        }
    }

    private static void repairLongRange(YamlConfiguration config, YamlConfiguration defaults,
                                        List<String> repaired, String path, long minimum, long maximum) {
        if (!(config.get(path) instanceof Number number)
                || number.longValue() < minimum || number.longValue() > maximum) {
            replaceWithDefault(config, defaults, repaired, path);
        }
    }

    private static void repairDoubleRange(YamlConfiguration config, YamlConfiguration defaults,
                                          List<String> repaired, String path,
                                          double minimum, double maximum) {
        if (!(config.get(path) instanceof Number number)) {
            replaceWithDefault(config, defaults, repaired, path);
            return;
        }
        double value = number.doubleValue();
        if (!Double.isFinite(value) || value < minimum || value > maximum) {
            replaceWithDefault(config, defaults, repaired, path);
        }
    }

    private static void replaceWithDefault(YamlConfiguration config, YamlConfiguration defaults,
                                           List<String> repaired, String path) {
        Object expected = defaults.get(path);
        if (!java.util.Objects.equals(config.get(path), expected)) {
            config.set(path, expected);
            if (!repaired.contains(path)) {
                repaired.add(path);
            }
        }
    }

    private static void replaceFile(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.deleteIfExists(temporary);
        Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public record MigrationResult(int oldVersion, int newVersion, List<String> addedKeys,
                                  boolean backedUp, boolean restored) {
    }
}

package org.enthusia.playtime.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigMigratorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void repairsOnlyBrokenValuesAndPreservesValidCustomValues() {
        YamlConfiguration defaults = new YamlConfiguration();
        defaults.set("storage.type", "sqlite");
        defaults.set("storage.sqlite.file", "playtime.db");
        defaults.set("joins.timezone", "America/New_York");
        defaults.set("gui.filler-material", "GRAY_STAINED_GLASS_PANE");
        defaults.set("gui.bedrock.main-menu-rows", 5);
        defaults.set("gui.bedrock.leaderboard-rows", 6);

        YamlConfiguration configured = new YamlConfiguration();
        configured.set("storage.type", "mysql");
        configured.set("storage.sqlite.file", "custom.db");
        configured.set("joins.timezone", "not/a-zone");
        configured.set("gui.filler-material", "NOT_A_MATERIAL");
        configured.set("gui.bedrock.main-menu-rows", 99);
        configured.set("gui.bedrock.leaderboard-rows", 6);
        configured.set("custom.keep-me", "untouched");

        List<String> repaired = ConfigMigrator.repairConfig(configured, defaults);

        assertEquals("mysql", configured.getString("storage.type"));
        assertEquals("custom.db", configured.getString("storage.sqlite.file"));
        assertEquals("America/New_York", configured.getString("joins.timezone"));
        assertEquals("GRAY_STAINED_GLASS_PANE", configured.getString("gui.filler-material"));
        assertEquals(5, configured.getInt("gui.bedrock.main-menu-rows"));
        assertEquals(6, configured.getInt("gui.bedrock.leaderboard-rows"));
        assertEquals("untouched", configured.getString("custom.keep-me"));
        assertTrue(repaired.contains("joins.timezone"));
        assertTrue(repaired.contains("gui.filler-material"));
        assertTrue(repaired.contains("gui.bedrock.main-menu-rows"));
        assertFalse(repaired.contains("storage.type"));
        assertFalse(repaired.contains("storage.sqlite.file"));
    }

    @Test
    void replacesBrokenSectionWithoutDiscardingOtherSections() {
        YamlConfiguration defaults = new YamlConfiguration();
        defaults.set("storage.type", "sqlite");
        defaults.set("storage.sqlite.file", "playtime.db");
        defaults.set("debug.enabled", false);

        YamlConfiguration configured = new YamlConfiguration();
        configured.set("storage", "broken-section");
        configured.set("debug.enabled", true);

        List<String> repaired = ConfigMigrator.repairConfig(configured, defaults);

        assertEquals("sqlite", configured.getString("storage.type"));
        assertEquals("playtime.db", configured.getString("storage.sqlite.file"));
        assertTrue(configured.getBoolean("debug.enabled"));
        assertTrue(repaired.contains("storage"));
    }

    @Test
    void strictLoaderRejectsMalformedYaml() throws Exception {
        Path malformed = temporaryDirectory.resolve("config.yml");
        Files.writeString(malformed, "storage:\n  type: sqlite\n  broken: [\n");

        assertThrows(Exception.class,
                () -> ConfigMigrator.loadStrict(malformed.toFile()));
    }

    @Test
    void persistentDataDetectionIgnoresOnlyConfigRecoveryFiles() throws Exception {
        Path config = temporaryDirectory.resolve("config.yml");
        Files.writeString(config, "storage:\n  type: sqlite\n");
        assertFalse(ConfigMigrator.hasPersistentDataBesidesConfig(temporaryDirectory.toFile()));

        Path backups = Files.createDirectories(temporaryDirectory.resolve("backups"));
        Files.writeString(backups.resolve("config.yml.broken.tmp.repair"), "partial repair");
        assertFalse(ConfigMigrator.hasPersistentDataBesidesConfig(temporaryDirectory.toFile()));

        Files.writeString(temporaryDirectory.resolve("playtime.db"), "data");
        assertTrue(ConfigMigrator.hasPersistentDataBesidesConfig(temporaryDirectory.toFile()));
    }
}

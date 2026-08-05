package org.enthusia.playtime;

import org.bukkit.configuration.file.YamlConfiguration;
import org.enthusia.playtime.config.ConfigMigrator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.io.File;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigRecoveryIntegrationTest {
    @BeforeEach
    void setUpServer() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDownServer() {
        MockBukkit.unmock();
    }

    @Test
    void freshStartupCreatesOneRollingLastGoodConfig() {
        PlayTimePlugin plugin = MockBukkit.load(PlayTimePlugin.class);

        File backup = new File(plugin.getDataFolder(), "backups/config.yml.last-good");
        assertTrue(plugin.isEnabled());
        assertNotNull(plugin.runtime());
        assertTrue(backup.isFile());
    }

    @Test
    void reloadRepairsBrokenValuesAndPreservesValidValues() throws Exception {
        PlayTimePlugin plugin = MockBukkit.load(PlayTimePlugin.class);
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        YamlConfiguration config = ConfigMigrator.loadStrict(configFile);
        config.set("joins.timezone", "not/a-zone");
        config.set("gui.bedrock.main-menu-rows", 99);
        config.set("sampling.afk-seconds", 777L);
        config.save(configFile);

        assertTrue(plugin.reloadPluginRuntime());

        YamlConfiguration repaired = ConfigMigrator.loadStrict(configFile);
        assertEquals("America/New_York", repaired.getString("joins.timezone"));
        assertEquals(5, repaired.getInt("gui.bedrock.main-menu-rows"));
        assertEquals(777L, repaired.getLong("sampling.afk-seconds"));
        assertTrue(new File(plugin.getDataFolder(), "backups/config.yml.broken").isFile());
        assertTrue(new File(plugin.getDataFolder(), "backups/config.yml.last-good").isFile());
    }

    @Test
    void reloadRestoresMissingConfigFromLastGood() throws Exception {
        PlayTimePlugin plugin = MockBukkit.load(PlayTimePlugin.class);
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        assertTrue(configFile.delete());

        assertTrue(plugin.reloadPluginRuntime());
        assertTrue(configFile.isFile());
        assertNotNull(plugin.runtime());
    }

    @Test
    void reloadPreservesMalformedConfigAndRestoresLastGood() throws Exception {
        PlayTimePlugin plugin = MockBukkit.load(PlayTimePlugin.class);
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        Files.writeString(configFile.toPath(), "storage:\n  type: sqlite\n  broken: [\n");

        assertTrue(plugin.reloadPluginRuntime());

        assertNotNull(plugin.runtime());
        assertEquals("sqlite", ConfigMigrator.loadStrict(configFile).getString("storage.type"));
        File broken = new File(plugin.getDataFolder(), "backups/config.yml.broken");
        assertTrue(broken.isFile());
        assertTrue(Files.readString(broken.toPath()).contains("broken: ["));
    }
}

package org.enthusia.playtime;

import org.enthusia.playtime.service.PlaytimeRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayTimePluginRuntimeTest {
    @BeforeEach
    void setUpServer() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDownServer() {
        MockBukkit.unmock();
    }

    @Test
    void enablesAndReloadsRuntime() {
        PlayTimePlugin plugin = MockBukkit.load(PlayTimePlugin.class);
        PlaytimeRuntime firstRuntime = plugin.runtime();

        assertTrue(plugin.isEnabled());
        assertNotNull(firstRuntime);
        assertTrue(new File(plugin.getDataFolder(), plugin.getRuntimeConfig().getSqliteFile()).exists());

        assertTrue(plugin.reloadPluginRuntime());
        assertNotNull(plugin.runtime());
        assertNotSame(firstRuntime, plugin.runtime());
    }

    @Test
    void anyExistingConfigPreservesTheEstablishedDatabaseGuard(@TempDir Path dataFolder) throws Exception {
        assertFalse(PlayTimePlugin.containsEstablishedStorageData(dataFolder.toFile()));

        Files.writeString(dataFolder.resolve("config.yml"), "storage:\n  type: sqlite\n");
        assertTrue(PlayTimePlugin.containsEstablishedStorageData(dataFolder.toFile()));
    }

    @Test
    void brokenConfigCopyAloneDoesNotPretendAFirstInstallCompleted(@TempDir Path dataFolder) throws Exception {
        Path backups = Files.createDirectories(dataFolder.resolve("backups"));
        Files.writeString(backups.resolve("config.yml.broken"), "malformed: [\n");
        assertFalse(PlayTimePlugin.containsEstablishedStorageData(dataFolder.toFile()));
    }

    @Test
    void lastGoodConfigProvesInstallationPreviouslyStarted(@TempDir Path dataFolder) throws Exception {
        Path backups = Files.createDirectories(dataFolder.resolve("backups"));
        Files.writeString(backups.resolve("config.yml.last-good"), "storage:\n  type: sqlite\n");
        assertTrue(PlayTimePlugin.containsEstablishedStorageData(dataFolder.toFile()));
    }

    @Test
    void databaseBackupMarksInstallationAsEstablished(@TempDir Path dataFolder) throws Exception {
        Path backups = Files.createDirectories(dataFolder.resolve("backups"));
        Files.writeString(backups.resolve("playtime.db.last-good"), "database backup");
        assertTrue(PlayTimePlugin.containsEstablishedStorageData(dataFolder.toFile()));
    }
}

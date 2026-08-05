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
    void detectsExistingPluginDataEvenWhenConfigIsMissing(@TempDir Path dataFolder) throws Exception {
        assertFalse(PlayTimePlugin.containsExistingPluginData(dataFolder.toFile()));

        Files.writeString(dataFolder.resolve("legacy-custom.sqlite"), "existing data");

        assertTrue(PlayTimePlugin.containsExistingPluginData(dataFolder.toFile()));
    }
}

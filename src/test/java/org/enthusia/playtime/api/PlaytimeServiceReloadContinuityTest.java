package org.enthusia.playtime.api;

import org.bukkit.Bukkit;
import org.enthusia.playtime.PlayTimePlugin;
import org.enthusia.playtime.api.impl.PlaytimeServiceImpl;
import org.enthusia.playtime.service.PlaytimeRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaytimeServiceReloadContinuityTest {
    private PlayTimePlugin plugin;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        plugin = MockBukkit.load(PlayTimePlugin.class);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void cachedServiceObjectDelegatesToPublishedReplacementRuntime() {
        PlaytimeService cached = Bukkit.getServicesManager().load(PlaytimeService.class);
        PlaytimeRuntime oldRuntime = plugin.runtime();
        assertSame(oldRuntime.playtimeService(), cached);

        assertTrue(plugin.reloadPluginRuntime());
        PlaytimeRuntime replacement = plugin.runtime();
        assertNotSame(oldRuntime, replacement);
        assertNotSame(cached, replacement.playtimeService());
        assertSame(replacement.playtimeService(), Bukkit.getServicesManager().load(PlaytimeService.class));

        // The old Java object is still safe to cache: implementation-local access now
        // resolves through the currently published runtime before touching repository state.
        assertSame(replacement.repository(), ((PlaytimeServiceImpl) cached).repository());
    }
}

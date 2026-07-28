package org.enthusia.playtime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.enthusia.playtime.config.PlaytimeConfig;
import org.enthusia.playtime.config.ConfigMigrator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NumeralConfigurationTest {
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
    void loadsCustomTiersByThresholdAndRejectsDuplicateOrBlankEntries() {
        plugin.getConfig().set("numerals.tiers", null);
        tier("late", "Late", 10, "&b");
        tier("early", "Early", 1, "&a");
        tier("duplicate-threshold", "Other", 1, "&c");
        tier("blank", " ", 2, "&d");

        PlaytimeConfig config = PlaytimeConfig.load(plugin);

        assertEquals(2, config.numerals().catalog().tiers().size());
        assertEquals("Early", config.numerals().catalog().tiers().getFirst().label());
        assertEquals("Late", config.numerals().catalog().tiers().getLast().label());
    }

    @Test
    void malformedTierConfigurationFallsBackToDefaults() {
        plugin.getConfig().set("numerals.tiers", null);
        tier("bad", "", -1, "not-a-color");

        PlaytimeConfig config = PlaytimeConfig.load(plugin);

        assertEquals(12, config.numerals().catalog().tiers().size());
        assertEquals("I", config.numerals().catalog().tiers().getFirst().label());
    }

    @Test
    void enabledStateReloadsWithoutRetainingPriorNumeralSettings() {
        plugin.getConfig().set("numerals.enabled", false);
        plugin.saveConfig();
        assertTrue(plugin.reloadPluginRuntime());
        assertFalse(plugin.runtime().config().numerals().enabled());

        plugin.getConfig().set("numerals.enabled", true);
        plugin.getConfig().set("numerals.tiers", null);
        plugin.saveConfig();
        assertTrue(plugin.reloadPluginRuntime());
        assertTrue(plugin.runtime().config().numerals().enabled());
        assertEquals("I", plugin.runtime().config().numerals().catalog().tiers().getFirst().label());
    }

    @Test
    void acceptsNamedHexAndGradientTierColors() {
        plugin.getConfig().set("numerals.tiers", null);
        tier("named", "Named", 1, "dark_aqua");
        tier("hex", "Hex", 2, "#12AB34");
        tier("gradient", "Gradient", 3, "gradient:#FF0000:#00FF00:#0000FF");

        PlaytimeConfig config = PlaytimeConfig.load(plugin);

        assertEquals(3, config.numerals().catalog().tiers().size());
        assertEquals("dark_aqua", config.numerals().catalog().tiers().get(0).color());
        assertEquals("#12AB34", config.numerals().catalog().tiers().get(1).color());
        assertEquals("gradient:#FF0000:#00FF00:#0000FF",
                config.numerals().catalog().tiers().get(2).color());
    }

    @Test
    void migrationBackfillsMissingTierConfigurationWithoutReplacingCustomTiers() {
        plugin.getConfig().set("config-version", 3);
        plugin.getConfig().set("numerals.tiers", null);
        plugin.saveConfig();

        new ConfigMigrator(plugin).migrateConfig();

        assertTrue(plugin.getConfig().contains("numerals.tiers.1.color", true));
        assertEquals("gray", plugin.getConfig().getString("numerals.tiers.1.color"));
        assertEquals(4, plugin.getConfig().getInt("config-version"));
    }

    private void tier(String key, String label, long hours, String color) {
        String base = "numerals.tiers." + key;
        plugin.getConfig().set(base + ".label", label);
        plugin.getConfig().set(base + ".hours", hours);
        plugin.getConfig().set(base + ".color", color);
    }
}

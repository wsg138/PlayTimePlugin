package org.enthusia.playtime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaceholderReloadContinuityTest {
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
    void reloadPublishesNewNumeralColorsWithoutReplacingRegisteredExpansion() {
        CountingPlaceholderLifecycle lifecycle = new CountingPlaceholderLifecycle();
        plugin.setPlaceholderLifecycleForTesting(lifecycle);
        plugin.getConfig().set("numerals.tiers.1.color", "gradient:#1C1313:#241B1B");
        plugin.saveConfig();

        assertTrue(plugin.reloadPluginRuntime());

        assertEquals("gradient:#1C1313:#241B1B",
                plugin.runtime().config().numerals().catalog().tiers().getFirst().color());
        assertEquals(0, lifecycle.unregisterCalls.get());
        assertEquals(0, lifecycle.registerCalls.get());
        assertTrue(lifecycle.registered.get());
    }

    private static final class CountingPlaceholderLifecycle implements PlayTimePlugin.PlaceholderLifecycle {
        private final AtomicBoolean registered = new AtomicBoolean(true);
        private final AtomicInteger unregisterCalls = new AtomicInteger();
        private final AtomicInteger registerCalls = new AtomicInteger();

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public boolean registered() {
            return registered.get();
        }

        @Override
        public void unregister() {
            unregisterCalls.incrementAndGet();
            registered.set(false);
        }

        @Override
        public void register() {
            registerCalls.incrementAndGet();
            registered.set(true);
        }
    }
}

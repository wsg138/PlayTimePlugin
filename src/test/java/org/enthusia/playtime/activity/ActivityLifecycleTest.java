package org.enthusia.playtime.activity;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.enthusia.playtime.PlayTimePlugin;
import org.enthusia.playtime.event.PlayerActivityStateChangeEvent;
import org.enthusia.playtime.util.PerformanceCounters;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActivityLifecycleTest {
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
    void stateChangeEventFiresOnlyForActualTransitions() {
        PlayerMock player = MockBukkit.getMock().addPlayer();
        ActivityTracker tracker = plugin.runtime().activityTracker();
        long base = System.currentTimeMillis();
        tracker.bootstrapPlayer(player, base);

        List<String> transitions = new ArrayList<>();
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onStateChange(PlayerActivityStateChangeEvent event) {
                if (event.getPlayer().getUniqueId().equals(player.getUniqueId())) {
                    transitions.add(event.getOldState() + "->" + event.getNewState());
                }
            }
        }, plugin);

        assertEquals(ActivityState.ACTIVE, tracker.getState(player.getUniqueId(), base));
        assertEquals(ActivityState.IDLE, tracker.getState(player.getUniqueId(), base + 61_000L));
        assertEquals(ActivityState.IDLE, tracker.getState(player.getUniqueId(), base + 62_000L));
        assertEquals(ActivityState.AFK, tracker.getState(player.getUniqueId(), base + 301_000L));

        assertEquals(List.of("ACTIVE->IDLE", "IDLE->AFK"), transitions);
    }

    @Test
    void suspiciousEvidenceSurvivesQuickReconnectSnapshot() {
        PlayerMock player = MockBukkit.getMock().addPlayer();
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        List<BehaviorSample> behavior = new ArrayList<>();
        long sampleTime = now - 20_000L;
        for (int cycle = 0; cycle < 10; cycle++) {
            behavior.add(new BehaviorSample(sampleTime, BehaviorSample.MOVE,
                    0.28D, 0.0D, 0.0D, 0.0F, 0.0F, true));
            sampleTime += 500L;
            behavior.add(new BehaviorSample(sampleTime, BehaviorSample.MOVE,
                    -0.28D, 0.0D, 0.0D, 0.0F, 0.0F, true));
            sampleTime += 500L;
        }

        ActivityTracker.ActivitySnapshot snapshot = new ActivityTracker.ActivitySnapshot(
                now - 500L, now - 500L,
                player.getLocation().getX(), player.getLocation().getY(), player.getLocation().getZ(),
                player.getLocation().getYaw(), player.getLocation().getPitch(), true,
                List.of(), behavior, 0.95D, true, now - 100L, now - 100L,
                0L, ActivityState.SUSPICIOUS, false, now - 1_000L);

        ActivityTracker restored = new ActivityTracker(plugin.getRuntimeConfig(), new SessionManager(),
                Map.of(uuid, snapshot), Map.of(uuid, now - 30_000L), new PerformanceCounters());
        restored.bootstrapPlayer(player, now);

        assertEquals(ActivityState.SUSPICIOUS, restored.peekState(uuid, now));
        assertTrue(restored.diagnostics(uuid, now).suspicionScore() >= 0.86D);
    }
}

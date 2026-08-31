package org.enthusia.playtime.activity;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.enthusia.playtime.PlayTimePlugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttackActivityLifecycleTest {
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
    void attackOnlyAutomationTransitionsToSuspicious() {
        PlayerMock player = MockBukkit.getMock().addPlayer();
        ActivityTracker tracker = plugin.runtime().activityTracker();
        long base = System.currentTimeMillis();
        tracker.bootstrapPlayer(player, base);

        long time = base + 100L;
        for (int i = 0; i < 70; i++) {
            tracker.recordAction(player, time, BehaviorSample.ATTACK);
            time += 200L;
        }

        assertEquals(ActivityState.SUSPICIOUS, tracker.getState(player.getUniqueId(), time));
        ActivityTracker.ActivityDiagnostics diagnostics = tracker.diagnostics(player.getUniqueId(), time);
        assertTrue(diagnostics.clickRegularity() >= 0.985D);
        assertTrue(diagnostics.suspicionScore() >= 0.86D);
    }
}

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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void highCpsSwingsRemainDistinctAndBecomeSuspicious() {
        PlayerMock player = MockBukkit.getMock().addPlayer();
        ActivityTracker tracker = plugin.runtime().activityTracker();
        long base = System.currentTimeMillis();
        tracker.bootstrapPlayer(player, base);

        long time = base + 100L;
        for (int i = 0; i < 70; i++) {
            tracker.recordAction(player, time, BehaviorSample.SWING);
            time += 50L;
        }

        ActivityTracker.ActivitySnapshot snapshot = tracker.snapshot().get(player.getUniqueId());
        assertEquals(70, snapshot.behaviorSamples().size());
        assertEquals(ActivityState.SUSPICIOUS, tracker.getState(player.getUniqueId(), time));
        assertTrue(tracker.diagnostics(player.getUniqueId(), time).clickRegularity() >= 0.985D);
    }

    @Test
    void distinctEventsFromOnePhysicalActionStillDeduplicate() {
        PlayerMock player = MockBukkit.getMock().addPlayer();
        ActivityTracker tracker = plugin.runtime().activityTracker();
        long base = System.currentTimeMillis();
        tracker.bootstrapPlayer(player, base);

        tracker.recordAction(player, base + 100L, BehaviorSample.SWING);
        tracker.recordAction(player, base + 110L, BehaviorSample.ATTACK);

        List<BehaviorSample> samples = tracker.snapshot().get(player.getUniqueId()).behaviorSamples();
        assertEquals(1, samples.size());
        assertTrue(samples.get(0).has(BehaviorSample.SWING));
        assertTrue(samples.get(0).has(BehaviorSample.ATTACK));
    }

    @Test
    void duplicateAttackAfterMergedSwingAttackFanoutDoesNotCreateExtraSample() {
        PlayerMock player = MockBukkit.getMock().addPlayer();
        ActivityTracker tracker = plugin.runtime().activityTracker();
        long base = System.currentTimeMillis();
        tracker.bootstrapPlayer(player, base);

        tracker.recordAction(player, base + 100L, BehaviorSample.SWING);
        tracker.recordAction(player, base + 110L, BehaviorSample.ATTACK);
        tracker.recordAction(player, base + 115L, BehaviorSample.ATTACK);

        List<BehaviorSample> samples = tracker.snapshot().get(player.getUniqueId()).behaviorSamples();
        assertEquals(1, samples.size());
        assertTrue(samples.get(0).has(BehaviorSample.SWING));
        assertTrue(samples.get(0).has(BehaviorSample.ATTACK));
    }

    @Test
    void duplicateDamageFanoutFromOneAttackDoesNotFloodHistory() {
        PlayerMock player = MockBukkit.getMock().addPlayer();
        ActivityTracker tracker = plugin.runtime().activityTracker();
        long base = System.currentTimeMillis();
        tracker.bootstrapPlayer(player, base);

        long time = base + 100L;
        for (int i = 0; i < 70; i++) {
            tracker.recordAction(player, time, BehaviorSample.ATTACK);
            tracker.recordAction(player, time + 3L, BehaviorSample.ATTACK);
            tracker.recordAction(player, time + 7L, BehaviorSample.ATTACK);
            time += 200L;
        }

        List<BehaviorSample> samples = tracker.snapshot().get(player.getUniqueId()).behaviorSamples();
        assertEquals(70, samples.size());
        assertEquals(ActivityState.SUSPICIOUS, tracker.getState(player.getUniqueId(), time));
    }

    @Test
    void repeatedHeldUseStyleInteractionIsUntrustedAutomationEvidence() {
        PlayerMock player = MockBukkit.getMock().addPlayer();
        ActivityTracker tracker = plugin.runtime().activityTracker();
        long base = System.currentTimeMillis();
        tracker.bootstrapPlayer(player, base);

        long time = base + 100L;
        for (int i = 0; i < 80; i++) {
            tracker.recordAction(player, time, BehaviorSample.INTERACT | BehaviorSample.BLOCK_PLACE);
            time += 200L;
        }

        assertEquals(ActivityState.SUSPICIOUS, tracker.getState(player.getUniqueId(), time));
        ActivityTracker.ActivityDiagnostics diagnostics = tracker.diagnostics(player.getUniqueId(), time);
        assertEquals(0.0D, diagnostics.clickRegularity());
        assertTrue(diagnostics.sequenceRepetition() >= 0.975D);
    }

    @Test
    void lowFrequencyCommandHeartbeatBecomesSuspicious() {
        PlayerMock player = MockBukkit.getMock().addPlayer();
        ActivityTracker tracker = plugin.runtime().activityTracker();
        long base = System.currentTimeMillis();
        tracker.bootstrapPlayer(player, base);

        long time = base + 1_000L;
        for (int i = 0; i < 5; i++) {
            tracker.recordAction(player, time, BehaviorSample.COMMAND);
            time += 30_000L;
        }
        long analyzeAt = time - 30_000L;

        assertEquals(ActivityState.SUSPICIOUS, tracker.getState(player.getUniqueId(), analyzeAt));
        ActivityTracker.ActivityDiagnostics diagnostics = tracker.diagnostics(player.getUniqueId(), analyzeAt);
        assertTrue(diagnostics.sequenceRepetition() >= 0.98D);
        assertEquals(30_000L, diagnostics.dominantCycleMillis());
    }

    @Test
    void lateAsyncStyleActionCannotReonlineDisconnectedState() {
        PlayerMock player = MockBukkit.getMock().addPlayer();
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        ActivityTracker.ActivitySnapshot disconnected = new ActivityTracker.ActivitySnapshot(
                now - 1_000L, now - 1_000L,
                player.getLocation().getX(), player.getLocation().getY(), player.getLocation().getZ(),
                player.getLocation().getYaw(), player.getLocation().getPitch(), true,
                List.of(), List.of(), 0.0D, false, now - 1_000L, 0L,
                0L, ActivityState.ACTIVE, false, now - 500L);
        ActivityTracker tracker = new ActivityTracker(plugin.getRuntimeConfig(), new SessionManager(),
                Map.of(uuid, disconnected), Map.of(), new PerformanceCounters());

        tracker.recordAction(uuid, now, BehaviorSample.CHAT);

        ActivityTracker.ActivitySnapshot after = tracker.snapshot().get(uuid);
        assertFalse(after.online());
        assertTrue(after.behaviorSamples().isEmpty());
        assertEquals(ActivityState.AFK, tracker.peekState(uuid, now));
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

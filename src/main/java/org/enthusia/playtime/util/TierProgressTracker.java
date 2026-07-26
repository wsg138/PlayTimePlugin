package org.enthusia.playtime.util;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Runtime-owned active-minute totals used exclusively for tier advancement. */
public final class TierProgressTracker {
    private final NumeralTierCatalog catalog;
    private final Map<UUID, Progress> progressByPlayer = new HashMap<>();

    public TierProgressTracker(NumeralTierCatalog catalog, Map<UUID, ProgressState> restored) {
        this.catalog = catalog;
        restored.forEach((uuid, state) -> progressByPlayer.put(uuid,
                new Progress(state.activeMinutes(), state.pendingActiveMinutes(), state.initialized(), state.connected(),
                        state.initialized() ? catalog.tierForMinutes(state.activeMinutes()).orElse(null) : null)));
    }

    public synchronized boolean needsInitialization(UUID uuid) {
        Progress progress = progressByPlayer.get(uuid);
        if (progress == null) {
            progressByPlayer.put(uuid, new Progress(0L, 0L, false, true, null));
            return true;
        }
        progress.connected = true;
        return !progress.initialized;
    }

    public synchronized ActiveUpdate acceptActiveMinutes(UUID uuid, int activeMinutes) {
        if (activeMinutes <= 0) {
            return new ActiveUpdate(true, Optional.empty());
        }
        Progress progress = progressByPlayer.computeIfAbsent(uuid, ignored -> new Progress(0L, 0L, false, true, null));
        if (!progress.initialized) {
            progress.pendingActiveMinutes += activeMinutes;
            return new ActiveUpdate(false, Optional.empty());
        }
        progress.activeMinutes += activeMinutes;
        NumeralTierCatalog.Tier reachedTier = catalog.tierForMinutes(progress.activeMinutes).orElse(null);
        Optional<NumeralTierCatalog.Tier> reached = reachedTier == null || reachedTier == progress.currentTier
                ? Optional.empty() : Optional.of(reachedTier);
        progress.currentTier = reachedTier;
        return new ActiveUpdate(true, reached);
    }

    public synchronized InitializationResult finishInitialization(UUID uuid, long durableActiveMinutes) {
        Progress progress = progressByPlayer.computeIfAbsent(uuid, ignored -> new Progress(0L, 0L, false, true, null));
        if (progress.initialized) {
            return new InitializationResult(0L, Optional.empty(), progress.connected);
        }
        long pending = progress.pendingActiveMinutes;
        progress.activeMinutes = Math.max(0L, durableActiveMinutes) + pending;
        progress.pendingActiveMinutes = 0L;
        progress.initialized = true;
        progress.currentTier = catalog.tierForMinutes(progress.activeMinutes).orElse(null);
        return new InitializationResult(pending, TierAdvancement.reachedTier(catalog, Math.max(0L, durableActiveMinutes), pending), progress.connected);
    }

    public synchronized void disconnect(UUID uuid) {
        Progress progress = progressByPlayer.get(uuid);
        if (progress == null) {
            return;
        }
        progress.connected = false;
        if (progress.initialized) {
            progressByPlayer.remove(uuid);
        }
    }

    public synchronized boolean removeIfDisconnected(UUID uuid) {
        Progress progress = progressByPlayer.get(uuid);
        if (progress != null && !progress.connected && progress.initialized) {
            progressByPlayer.remove(uuid);
            return true;
        }
        return false;
    }

    public synchronized Map<UUID, ProgressState> snapshot() {
        Map<UUID, ProgressState> snapshot = new HashMap<>();
        progressByPlayer.forEach((uuid, progress) -> snapshot.put(uuid,
                new ProgressState(progress.activeMinutes, progress.pendingActiveMinutes, progress.initialized, progress.connected)));
        return snapshot;
    }

    public synchronized Map<UUID, Long> drainPendingActiveMinutes() {
        Map<UUID, Long> pending = new HashMap<>();
        progressByPlayer.forEach((uuid, progress) -> {
            if (!progress.initialized && progress.pendingActiveMinutes > 0L) {
                pending.put(uuid, progress.pendingActiveMinutes);
                progress.pendingActiveMinutes = 0L;
            }
        });
        return pending;
    }

    public record ProgressState(long activeMinutes, long pendingActiveMinutes, boolean initialized, boolean connected) {
    }

    public record InitializationResult(long withheldActiveMinutes, Optional<NumeralTierCatalog.Tier> reachedTier, boolean connected) {
    }

    public record ActiveUpdate(boolean initialized, Optional<NumeralTierCatalog.Tier> reachedTier) {
    }

    private static final class Progress {
        private long activeMinutes;
        private long pendingActiveMinutes;
        private boolean initialized;
        private boolean connected;
        private NumeralTierCatalog.Tier currentTier;

        private Progress(long activeMinutes, long pendingActiveMinutes, boolean initialized, boolean connected,
                         NumeralTierCatalog.Tier currentTier) {
            this.activeMinutes = activeMinutes;
            this.pendingActiveMinutes = pendingActiveMinutes;
            this.initialized = initialized;
            this.connected = connected;
            this.currentTier = currentTier;
        }
    }
}

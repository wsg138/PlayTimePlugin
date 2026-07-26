package org.enthusia.playtime.util;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Runtime-owned active-minute totals and current tiers with generation-safe initialization. */
public final class TierProgressTracker {
    private final NumeralTierCatalog catalog;
    private final Map<UUID, Progress> progressByPlayer = new HashMap<>();

    public TierProgressTracker(NumeralTierCatalog catalog, Map<UUID, ProgressState> restored) {
        this.catalog = catalog;
        restored.forEach((uuid, state) -> progressByPlayer.put(uuid, Progress.from(state, catalog)));
    }

    public synchronized Optional<InitializationRequest> requestInitialization(UUID uuid, boolean connected) {
        Progress progress = progressByPlayer.computeIfAbsent(uuid, ignored -> Progress.uninitialized(connected));
        progress.connected = connected;
        if (progress.initialized || progress.initializing) {
            return Optional.empty();
        }
        progress.initializing = true;
        progress.generation++;
        return Optional.of(new InitializationRequest(uuid, progress.generation));
    }

    public synchronized ActiveUpdate acceptActiveMinutes(UUID uuid, int activeMinutes) {
        if (activeMinutes <= 0) {
            return new ActiveUpdate(true, Optional.empty());
        }
        Progress progress = progressByPlayer.computeIfAbsent(uuid, ignored -> Progress.uninitialized(true));
        if (!progress.initialized) {
            progress.withheldActiveMinutes += activeMinutes;
            return new ActiveUpdate(false, Optional.empty());
        }
        progress.activeMinutes += activeMinutes;
        NumeralTierCatalog.Tier nextTier = catalog.tierForMinutes(progress.activeMinutes).orElse(null);
        Optional<NumeralTierCatalog.Tier> reached = nextTier == null || nextTier == progress.currentTier
                ? Optional.empty() : Optional.of(nextTier);
        progress.currentTier = nextTier;
        return new ActiveUpdate(true, reached);
    }

    public synchronized Optional<InitializationResult> finishInitialization(InitializationRequest request, long effectiveActiveMinutes) {
        Progress progress = progressByPlayer.get(request.uuid());
        if (progress == null || progress.initialized || !progress.initializing || progress.generation != request.generation()) {
            return Optional.empty();
        }
        long withheld = progress.withheldActiveMinutes;
        long baseline = Math.max(0L, effectiveActiveMinutes);
        progress.activeMinutes = baseline + withheld;
        progress.withheldActiveMinutes = 0L;
        progress.initialized = true;
        progress.initializing = false;
        progress.currentTier = catalog.tierForMinutes(progress.activeMinutes).orElse(null);
        return Optional.of(new InitializationResult(withheld,
                TierAdvancement.reachedTier(catalog, baseline, withheld), progress.connected));
    }

    public synchronized void disconnect(UUID uuid) {
        Progress progress = progressByPlayer.get(uuid);
        if (progress == null) {
            return;
        }
        progress.connected = false;
        if (!progress.initialized) {
            progress.initializing = false;
            progress.generation++;
        }
    }

    public synchronized void reconnect(UUID uuid) {
        progressByPlayer.computeIfAbsent(uuid, ignored -> Progress.uninitialized(true)).connected = true;
    }

    public synchronized Map<UUID, ProgressState> snapshot() {
        Map<UUID, ProgressState> snapshot = new HashMap<>();
        progressByPlayer.forEach((uuid, progress) -> snapshot.put(uuid, progress.state()));
        return snapshot;
    }

    public synchronized Map<UUID, Long> drainWithheldActiveMinutes() {
        Map<UUID, Long> withheld = new HashMap<>();
        progressByPlayer.forEach((uuid, progress) -> {
            if (!progress.initialized && progress.withheldActiveMinutes > 0L) {
                withheld.put(uuid, progress.withheldActiveMinutes);
                progress.withheldActiveMinutes = 0L;
            }
        });
        return withheld;
    }

    public synchronized Map<UUID, Boolean> uninitializedPlayers() {
        Map<UUID, Boolean> players = new HashMap<>();
        progressByPlayer.forEach((uuid, progress) -> {
            if (!progress.initialized) {
                players.put(uuid, progress.connected);
            }
        });
        return players;
    }

    public synchronized Map<UUID, Long> disconnectedInitializedPlayers() {
        Map<UUID, Long> players = new HashMap<>();
        progressByPlayer.forEach((uuid, progress) -> {
            if (progress.initialized && !progress.connected) {
                players.put(uuid, progress.activeMinutes);
            }
        });
        return players;
    }

    public synchronized void removeDisconnectedInitialized(UUID uuid) {
        Progress progress = progressByPlayer.get(uuid);
        if (progress != null && progress.initialized && !progress.connected) {
            progressByPlayer.remove(uuid);
        }
    }

    public record ProgressState(long activeMinutes, long withheldActiveMinutes, boolean initialized, boolean connected,
                                long generation, boolean initializing) {
    }

    public record InitializationRequest(UUID uuid, long generation) {
    }

    public record InitializationResult(long withheldActiveMinutes, Optional<NumeralTierCatalog.Tier> reachedTier, boolean connected) {
    }

    public record ActiveUpdate(boolean initialized, Optional<NumeralTierCatalog.Tier> reachedTier) {
    }

    private static final class Progress {
        private long activeMinutes;
        private long withheldActiveMinutes;
        private boolean initialized;
        private boolean connected;
        private long generation;
        private boolean initializing;
        private NumeralTierCatalog.Tier currentTier;

        private static Progress uninitialized(boolean connected) {
            return new Progress(0L, 0L, false, connected, 0L, false, null);
        }

        private static Progress from(ProgressState state, NumeralTierCatalog catalog) {
            return new Progress(state.activeMinutes(), state.withheldActiveMinutes(), state.initialized(), state.connected(),
                    state.generation(), false, state.initialized() ? catalog.tierForMinutes(state.activeMinutes()).orElse(null) : null);
        }

        private Progress(long activeMinutes, long withheldActiveMinutes, boolean initialized, boolean connected,
                         long generation, boolean initializing, NumeralTierCatalog.Tier currentTier) {
            this.activeMinutes = activeMinutes;
            this.withheldActiveMinutes = withheldActiveMinutes;
            this.initialized = initialized;
            this.connected = connected;
            this.generation = generation;
            this.initializing = initializing;
            this.currentTier = currentTier;
        }

        private ProgressState state() {
            return new ProgressState(activeMinutes, withheldActiveMinutes, initialized, connected, generation, initializing);
        }
    }
}

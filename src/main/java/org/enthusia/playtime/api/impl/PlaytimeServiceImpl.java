package org.enthusia.playtime.api.impl;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.enthusia.playtime.PlayTimePlugin;
import org.enthusia.playtime.activity.ActivityState;
import org.enthusia.playtime.activity.ActivityTracker;
import org.enthusia.playtime.activity.SessionManager;
import org.enthusia.playtime.api.PlaytimeRange;
import org.enthusia.playtime.api.PlaytimeService;
import org.enthusia.playtime.data.PlaytimeRepository;
import org.enthusia.playtime.data.model.PlaytimeSnapshot;
import org.enthusia.playtime.data.model.RangeTotals;
import org.enthusia.playtime.service.PlaytimeReadService;
import org.enthusia.playtime.service.PlaytimeRuntime;

import java.util.Optional;
import java.util.UUID;

/**
 * Runtime-local implementation whose public methods behave as stable facade handles.
 *
 * <p>ServicesManager historically exposed one of these objects per runtime. External
 * plugins are allowed to cache that Java reference, so each public method resolves
 * the plugin's currently published runtime before touching runtime-local state. A
 * reference obtained before /playtime reload therefore delegates to the replacement
 * runtime instead of retaining a stale ActivityTracker or read service.</p>
 */
public final class PlaytimeServiceImpl implements PlaytimeService {

    private final PlaytimeReadService readService;
    private final PlaytimeRepository playtimeRepository;
    private final ActivityTracker tracker;
    private final SessionManager sessionManager;

    public PlaytimeServiceImpl(PlaytimeReadService readService,
                               PlaytimeRepository repository,
                               ActivityTracker tracker,
                               SessionManager sessionManager) {
        this.readService = readService;
        this.playtimeRepository = repository;
        this.tracker = tracker;
        this.sessionManager = sessionManager;
    }

    @Override
    public Optional<PlaytimeSnapshot> getLifetime(UUID uuid) {
        PlaytimeServiceImpl current = currentRuntimeDelegate();
        return current == null ? Optional.empty() : current.localLifetime(uuid);
    }

    @Override
    public RangeTotals getRangeTotals(UUID uuid, PlaytimeRange range) {
        PlaytimeServiceImpl current = currentRuntimeDelegate();
        return current == null ? new RangeTotals(0L, 0L, 0L) : current.localRangeTotals(uuid, range);
    }

    @Override
    public ActivityState getLiveState(UUID uuid) {
        PlaytimeServiceImpl current = currentRuntimeDelegate();
        return current == null ? ActivityState.AFK : current.tracker.peekState(uuid, System.currentTimeMillis());
    }

    @Override
    public long getCurrentSessionMillis(UUID uuid) {
        PlaytimeServiceImpl current = currentRuntimeDelegate();
        return current == null ? 0L : current.sessionManager.getSessionLengthMillis(uuid);
    }

    private Optional<PlaytimeSnapshot> localLifetime(UUID uuid) {
        return readService.getLifetime(uuid);
    }

    private RangeTotals localRangeTotals(UUID uuid, PlaytimeRange range) {
        String key = switch (range) {
            case TODAY -> "TODAY";
            case LAST_7D -> "7D";
            case LAST_30D -> "30D";
            case ALL -> "ALL";
        };
        return readService.getRangeTotals(uuid, key);
    }

    private PlaytimeServiceImpl currentRuntimeDelegate() {
        try {
            Plugin plugin = Bukkit.getPluginManager().getPlugin("EnthusiaPlaytime");
            if (!(plugin instanceof PlayTimePlugin playTimePlugin)) {
                // Unit-level construction outside a running plugin still uses its own
                // immutable runtime dependencies.
                return this;
            }
            PlaytimeRuntime runtime = playTimePlugin.runtime();
            if (runtime == null) {
                return null;
            }
            PlaytimeService current = runtime.playtimeService();
            return current instanceof PlaytimeServiceImpl implementation
                    ? implementation
                    : this;
        } catch (IllegalStateException ignored) {
            // Bukkit is not available (for example after complete server teardown).
            return null;
        }
    }

    public PlaytimeRepository repository() {
        PlaytimeServiceImpl current = currentRuntimeDelegate();
        return current == null ? playtimeRepository : current.playtimeRepository;
    }
}

package org.enthusia.playtime.api.impl;

import org.enthusia.playtime.activity.ActivityState;
import org.enthusia.playtime.activity.ActivityTracker;
import org.enthusia.playtime.activity.SessionManager;
import org.enthusia.playtime.api.PlaytimeRange;
import org.enthusia.playtime.api.PlaytimeService;
import org.enthusia.playtime.data.PlaytimeRepository;
import org.enthusia.playtime.data.model.PlaytimeSnapshot;
import org.enthusia.playtime.data.model.RangeTotals;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public final class PlaytimeServiceImpl implements PlaytimeService {

    private final PlaytimeRepository repository;
    private final ActivityTracker tracker;
    private final SessionManager sessionManager;

    public PlaytimeServiceImpl(PlaytimeRepository repository,
                               ActivityTracker tracker,
                               SessionManager sessionManager) {
        this.repository = repository;
        this.tracker = tracker;
        this.sessionManager = sessionManager;
    }

    @Override
    public Optional<PlaytimeSnapshot> getLifetime(UUID uuid) {
        return repository.getLifetime(uuid);
    }

    @Override
    public RangeTotals getRangeTotals(UUID uuid, PlaytimeRange range) {
        // For now, ignore actual range and use lifetime for ALL.
        Instant now = Instant.now();
        String key = switch (range) {
            case TODAY -> "today";
            case LAST_7D -> "7d";
            case LAST_30D -> "30d";
            case ALL -> "all";
        };
        return repository.getRangeTotals(uuid, now, key);
    }

    @Override
    public ActivityState getLiveState(UUID uuid) {
        return tracker.getState(uuid, System.currentTimeMillis());
    }

    @Override
    public long getCurrentSessionMillis(UUID uuid) {
        return sessionManager.getSessionLengthMillis(uuid);
    }
}

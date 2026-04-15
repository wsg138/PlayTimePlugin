package org.enthusia.playtime.api.impl;

import org.enthusia.playtime.activity.ActivityState;
import org.enthusia.playtime.activity.ActivityTracker;
import org.enthusia.playtime.activity.SessionManager;
import org.enthusia.playtime.api.PlaytimeRange;
import org.enthusia.playtime.api.PlaytimeService;
import org.enthusia.playtime.data.PlaytimeRepository;
import org.enthusia.playtime.data.model.PlaytimeSnapshot;
import org.enthusia.playtime.data.model.RangeTotals;
import org.enthusia.playtime.service.PlaytimeReadService;

import java.util.Optional;
import java.util.UUID;

public final class PlaytimeServiceImpl implements PlaytimeService {

    private final PlaytimeReadService readService;
    private final PlaytimeRepository repository;
    private final ActivityTracker tracker;
    private final SessionManager sessionManager;

    public PlaytimeServiceImpl(PlaytimeReadService readService,
                               PlaytimeRepository repository,
                               ActivityTracker tracker,
                               SessionManager sessionManager) {
        this.readService = readService;
        this.repository = repository;
        this.tracker = tracker;
        this.sessionManager = sessionManager;
    }

    @Override
    public Optional<PlaytimeSnapshot> getLifetime(UUID uuid) {
        return readService.getLifetime(uuid);
    }

    @Override
    public RangeTotals getRangeTotals(UUID uuid, PlaytimeRange range) {
        String key = switch (range) {
            case TODAY -> "TODAY";
            case LAST_7D -> "7D";
            case LAST_30D -> "30D";
            case ALL -> "ALL";
        };
        return readService.getRangeTotals(uuid, key);
    }

    @Override
    public ActivityState getLiveState(UUID uuid) {
        return tracker.getState(uuid, System.currentTimeMillis());
    }

    @Override
    public long getCurrentSessionMillis(UUID uuid) {
        return sessionManager.getSessionLengthMillis(uuid);
    }

    public PlaytimeRepository repository() {
        return repository;
    }
}

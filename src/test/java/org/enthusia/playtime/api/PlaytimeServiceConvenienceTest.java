package org.enthusia.playtime.api;

import org.enthusia.playtime.activity.ActivityState;
import org.enthusia.playtime.data.model.PlaytimeSnapshot;
import org.enthusia.playtime.data.model.RangeTotals;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaytimeServiceConvenienceTest {

    @Test
    void convenienceMethodsMatchDocumentedActivitySemantics() {
        PlaytimeService active = serviceReporting(ActivityState.ACTIVE);
        assertTrue(active.isGenuinelyActive(UUID.randomUUID()));
        assertFalse(active.isEffectivelyAfk(UUID.randomUUID()));

        PlaytimeService idle = serviceReporting(ActivityState.IDLE);
        assertFalse(idle.isGenuinelyActive(UUID.randomUUID()));
        assertFalse(idle.isEffectivelyAfk(UUID.randomUUID()));

        PlaytimeService afk = serviceReporting(ActivityState.AFK);
        assertFalse(afk.isGenuinelyActive(UUID.randomUUID()));
        assertTrue(afk.isEffectivelyAfk(UUID.randomUUID()));

        PlaytimeService suspicious = serviceReporting(ActivityState.SUSPICIOUS);
        assertFalse(suspicious.isGenuinelyActive(UUID.randomUUID()));
        assertTrue(suspicious.isEffectivelyAfk(UUID.randomUUID()));
    }

    private static PlaytimeService serviceReporting(ActivityState state) {
        return new PlaytimeService() {
            @Override
            public Optional<PlaytimeSnapshot> getLifetime(UUID uuid) {
                throw new UnsupportedOperationException();
            }

            @Override
            public RangeTotals getRangeTotals(UUID uuid, PlaytimeRange range) {
                throw new UnsupportedOperationException();
            }

            @Override
            public ActivityState getLiveState(UUID uuid) {
                return state;
            }

            @Override
            public long getCurrentSessionMillis(UUID uuid) {
                throw new UnsupportedOperationException();
            }
        };
    }
}

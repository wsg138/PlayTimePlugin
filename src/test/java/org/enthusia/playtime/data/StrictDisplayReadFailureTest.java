package org.enthusia.playtime.data;

import org.bukkit.plugin.java.JavaPlugin;
import org.enthusia.playtime.config.PlaytimeConfig;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StrictDisplayReadFailureTest {
    private static final String ALL_TIME = "ALL";
    private static final String TOTAL_METRIC = "TOTAL";

    @Test void sqlFailureIsNotAnEmptyLeaderboardOrZeroRange() throws Exception {
        DatabaseProvider provider = mock(DatabaseProvider.class);
        when(provider.getConnection()).thenThrow(new SQLException("outage"));
        PlaytimeRepository repository = repository(provider);
        UUID uuid = UUID.randomUUID();
        for (String range : new String[]{ALL_TIME, "TODAY"}) {
            assertThrows(SQLException.class, () -> repository.getRangeTotalsStrict(uuid, Instant.now(), range));
            assertThrows(SQLException.class, () -> repository.getLeaderboardStrict(TOTAL_METRIC, range, Instant.now(), 10, 0));
            assertThrows(SQLException.class, () -> repository.getPublicLeaderboardStrict(TOTAL_METRIC, range, Instant.now(), 10));
        }
    }

    @Test void successfulEmptyQueryIsStillAuthoritative() throws Exception {
        DatabaseProvider provider = mock(DatabaseProvider.class);
        try (Connection connection = mock(Connection.class);
             PreparedStatement statement = mock(PreparedStatement.class)) {
            when(provider.getConnection()).thenReturn(connection);
            when(connection.prepareStatement(anyString())).thenReturn(statement);
            when(statement.executeQuery()).thenReturn(mock(ResultSet.class));
            PlaytimeRepository repository = repository(provider);
            assertTrue(repository.getPublicLeaderboardStrict(TOTAL_METRIC, ALL_TIME, Instant.now(), 10).isEmpty());
            assertTrue(repository.getLeaderboardStrict(TOTAL_METRIC, ALL_TIME, Instant.now(), 10, 0).isEmpty());
            assertEquals(0, repository.getRangeTotalsStrict(UUID.randomUUID(), Instant.now(), ALL_TIME).totalMinutes);
        }
    }

    private PlaytimeRepository repository(DatabaseProvider provider) {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        PlaytimeConfig config = mock(PlaytimeConfig.class);
        PlaytimeConfig.Joins joins = mock(PlaytimeConfig.Joins.class);
        when(config.joins()).thenReturn(joins);
        when(joins.zoneId()).thenReturn(ZoneOffset.UTC);
        return new PlaytimeRepository(plugin, provider, config);
    }
}

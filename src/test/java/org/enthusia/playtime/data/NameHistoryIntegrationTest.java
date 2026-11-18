package org.enthusia.playtime.data;

import org.bukkit.plugin.java.JavaPlugin;
import org.enthusia.playtime.config.PlaytimeConfig;
import org.enthusia.playtime.data.model.PlayerProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NameHistoryIntegrationTest {
    @TempDir Path directory;

    @Test void migrationAndRepeatedObservationsKeepHistoryAndReusedNames() throws Exception {
        String url = "jdbc:sqlite:" + directory.resolve("history.db");
        DatabaseProvider provider = mock(DatabaseProvider.class);
        when(provider.getDialect()).thenReturn(SqlDialect.SQLITE);
        when(provider.getConnection()).thenAnswer(call -> DriverManager.getConnection(url));
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        PlaytimeConfig config = mock(PlaytimeConfig.class);
        PlaytimeConfig.Joins joins = mock(PlaytimeConfig.Joins.class);
        when(config.joins()).thenReturn(joins);
        when(joins.zoneId()).thenReturn(ZoneOffset.UTC);
        PlaytimeRepository repository = new PlaytimeRepository(plugin, provider, config);
        repository.initSchema();

        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        Instant initial = Instant.parse("2025-01-01T00:00:00Z");
        Instant later = Instant.parse("2025-02-01T00:00:00Z");
        repository.batchUpsertPlayerProfiles(List.of(
                new PlayerProfile(first, "OldName", null, initial),
                new PlayerProfile(first, "NewName", null, later),
                new PlayerProfile(first, "OldName", null, later),
                new PlayerProfile(second, "OldName", null, later)));
        assertEquals(2, repository.getNameObservations(first).size());
        var matches = repository.findNameObservations("oldname");
        assertEquals(2, matches.size());
        var old = matches.stream().filter(row -> row.uuid().equals(first)).findFirst().orElseThrow();
        assertEquals(initial, old.firstObserved());
        assertEquals(later, old.lastObserved());
        repository.initSchema();
        assertEquals(2, repository.getNameObservations(first).size());
    }
}

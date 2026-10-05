package org.enthusia.playtime.data;

import org.enthusia.playtime.PlayTimePlugin;
import org.enthusia.playtime.config.PlaytimeConfig;
import org.enthusia.playtime.data.model.PlayerProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.sql.Connection;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UsernameHistoryIntegrationTest {
    @TempDir Path tempDir;
    private final UUID player = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private DatabaseProvider provider;
    private PlaytimeRepository repository;

    @BeforeEach void setUp() {
        MockBukkit.mock();
        PlayTimePlugin plugin = mock(PlayTimePlugin.class);
        PlaytimeConfig config = mock(PlaytimeConfig.class);
        when(config.getSqliteFile()).thenReturn("history-test.db");
        when(config.joins()).thenReturn(new PlaytimeConfig.Joins(30, java.time.ZoneId.of("UTC"), null));
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        when(plugin.mayCreateInitialSqliteDatabase()).thenReturn(true);
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("history-test"));
        provider = new DatabaseProvider(plugin, config);
        provider.init(StorageType.SQLITE);
        repository = new PlaytimeRepository(plugin, provider, config);
    }

    @AfterEach void tearDown() {
        if (provider != null) provider.shutdown();
        MockBukkit.unmock();
    }

    @Test void upgradeAndRenamePreserveObservedNames() throws Exception {
        try (Connection connection = provider.getConnection(); Statement sql = connection.createStatement()) {
            sql.execute(SqlDialect.SQLITE.playerProfilesCreateTable());
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO player_profiles VALUES (?, ?, ?, ?, ?, ?)")) {
                insert.setString(1, player.toString()); insert.setString(2, "OldName"); insert.setString(3, null);
                insert.setTimestamp(4, Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));
                insert.setTimestamp(5, Timestamp.from(Instant.parse("2026-02-01T00:00:00Z")));
                insert.setTimestamp(6, Timestamp.from(Instant.parse("2026-02-01T00:00:00Z")));
                insert.executeUpdate();
            }
        }
        repository.initSchema();
        assertEquals(player, repository.findPlayerByObservedName("oldname").orElseThrow());
        repository.batchUpsertPlayerProfiles(List.of(new PlayerProfile(player, "NewName", null, Instant.parse("2026-03-01T00:00:00Z"))));
        repository.initSchema(); // repeated startup migration must retain the prior name
        assertEquals(List.of("OldName", "NewName"), repository.getObservedNames(player).stream().map(PlaytimeRepository.ObservedName::name).toList());
        assertEquals(player, repository.findPlayerByObservedName("OLDNAME").orElseThrow());
        assertEquals(player, repository.findPlayerByObservedName("newname").orElseThrow());
    }

    @Test void reusedNameIsAmbiguous() throws Exception {
        repository.initSchema();
        UUID other = UUID.randomUUID();
        repository.batchUpsertPlayerProfiles(List.of(
                new PlayerProfile(player, "Shared", null, Instant.parse("2026-01-01T00:00:00Z")),
                new PlayerProfile(other, "Shared", null, Instant.parse("2026-02-01T00:00:00Z"))));
        assertThrows(PlaytimeRepository.AmbiguousNameException.class, () -> repository.findPlayerByObservedName("Shared"));
    }

    @Test void currentOwnerWinsOverPriorOwnerOfReusedName() throws Exception {
        repository.initSchema();
        UUID currentOwner = UUID.randomUUID();
        repository.batchUpsertPlayerProfiles(List.of(new PlayerProfile(player, "Shared", null, Instant.parse("2026-01-01T00:00:00Z"))));
        repository.batchUpsertPlayerProfiles(List.of(new PlayerProfile(player, "Renamed", null, Instant.parse("2026-02-01T00:00:00Z"))));
        repository.batchUpsertPlayerProfiles(List.of(new PlayerProfile(currentOwner, "Shared", null, Instant.parse("2026-03-01T00:00:00Z"))));
        assertEquals(currentOwner, repository.findPlayerByObservedName("Shared").orElseThrow());
    }

    @Test void recoveryReplayKeepsNameAndBatchIdempotent() throws Exception {
        repository.initSchema();
        Instant observed = Instant.parse("2026-03-01T00:00:00Z");
        WriteBatch batch = new WriteBatch(UUID.randomUUID(), observed, java.util.Map.of(),
                java.util.Map.of(player, new PlayerProfile(player, "Recovered", null, observed)), List.of());
        assertEquals(RecoveryApplyResult.APPLIED, repository.applyWriteBatch(batch));
        assertEquals(RecoveryApplyResult.ALREADY_APPLIED, repository.applyWriteBatch(batch));
        assertEquals(List.of("Recovered"), repository.getObservedNames(player).stream().map(PlaytimeRepository.ObservedName::name).toList());
    }
}

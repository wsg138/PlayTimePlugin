package org.enthusia.playtime.data;

import org.enthusia.playtime.PlayTimePlugin;
import org.enthusia.playtime.config.PlaytimeConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SqliteStorageSafetyTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void establishedInstallationRefusesMissingOrEmptyDatabase() throws Exception {
        File database = temporaryDirectory.resolve("playtime.db").toFile();

        assertFalse(SqliteStorageSafety.prepareDatabasePath(database, true));
        assertThrows(IllegalStateException.class,
                () -> SqliteStorageSafety.prepareDatabasePath(database, false));

        assertTrue(database.createNewFile());
        assertThrows(IllegalStateException.class,
                () -> SqliteStorageSafety.prepareDatabasePath(database, false));
    }

    @Test
    void invalidEstablishedDatabaseNeverBecomesLiveDatasource() throws Exception {
        File database = temporaryDirectory.resolve("playtime.db").toFile();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.getAbsolutePath());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE unrelated (id INTEGER PRIMARY KEY)");
        }

        int providersBefore = DatabaseProvider.openProviderCountForTesting();
        DatabaseProvider provider = providerFor(temporaryDirectory.toFile());

        assertThrows(RuntimeException.class, () -> provider.init(StorageType.SQLITE));
        assertEquals(providersBefore, DatabaseProvider.openProviderCountForTesting());
        assertThrows(SQLException.class, provider::getConnection);
        assertFalse(temporaryDirectory.resolve("backups/playtime.db.last-good").toFile().exists());

        provider.shutdown();
        assertEquals(providersBefore, DatabaseProvider.openProviderCountForTesting());
    }

    @Test
    void populatedDatabaseKeepsCurrentAndPriorRollingBackups() throws Exception {
        File database = temporaryDirectory.resolve("playtime.db").toFile();
        createPopulatedDatabase(database, 120L, 125L);
        int providersBefore = DatabaseProvider.openProviderCountForTesting();

        DatabaseProvider firstProvider = providerFor(temporaryDirectory.toFile());
        firstProvider.init(StorageType.SQLITE);
        try {
            assertEquals(120L, activeMinutes(firstProvider));
            assertEquals(120L, activeMinutes(rollingBackup()));
        } finally {
            firstProvider.shutdown();
        }
        assertEquals(providersBefore, DatabaseProvider.openProviderCountForTesting());

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.getAbsolutePath());
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE lifetime_agg SET active_minutes = 240, total_minutes = 245 "
                    + "WHERE player_uuid = 'player-one'");
        }

        DatabaseProvider secondProvider = providerFor(temporaryDirectory.toFile());
        secondProvider.init(StorageType.SQLITE);
        try {
            assertEquals(240L, activeMinutes(secondProvider));
            assertEquals(240L, activeMinutes(rollingBackup()));
        } finally {
            secondProvider.shutdown();
        }
        assertEquals(providersBefore, DatabaseProvider.openProviderCountForTesting());

        try (var files = Files.list(temporaryDirectory.resolve("backups"))) {
            assertEquals(2L, files.filter(Files::isRegularFile).count());
        }
    }


    @Test
    void sqlitePathCannotEscapePluginDataFolder() {
        PlayTimePlugin plugin = mock(PlayTimePlugin.class);
        when(plugin.getDataFolder()).thenReturn(temporaryDirectory.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        PlaytimeConfig config = mock(PlaytimeConfig.class);
        when(config.getSqliteFile()).thenReturn("../outside.db");
        DatabaseProvider provider = new DatabaseProvider(plugin, config);

        assertThrows(IllegalArgumentException.class, () -> provider.init(StorageType.SQLITE));
        assertFalse(temporaryDirectory.getParent().resolve("outside.db").toFile().exists());
    }

    private DatabaseProvider providerFor(File dataFolder) {
        PlayTimePlugin plugin = mock(PlayTimePlugin.class);
        when(plugin.getDataFolder()).thenReturn(dataFolder);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("SqliteStorageSafetyTest"));
        when(plugin.mayCreateInitialSqliteDatabase()).thenReturn(false);
        when(plugin.claimSqliteStartupBackup()).thenReturn(true);

        PlaytimeConfig config = mock(PlaytimeConfig.class);
        when(config.getSqliteFile()).thenReturn("playtime.db");
        return new DatabaseProvider(plugin, config);
    }

    private void createPopulatedDatabase(File database, long activeMinutes, long totalMinutes) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.getAbsolutePath());
             Statement statement = connection.createStatement()) {
            statement.execute(SqlDialect.SQLITE.dailyAggCreateTable());
            statement.execute(SqlDialect.SQLITE.hourlyAggCreateTable());
            statement.execute(SqlDialect.SQLITE.lifetimeAggCreateTable());
            statement.execute(SqlDialect.SQLITE.joinsLogCreateTable());
            statement.execute(SqlDialect.SQLITE.playerProfilesCreateTable());
            statement.execute(SqlDialect.SQLITE.playerSkinProfilesCreateTable());
            statement.execute(SqlDialect.SQLITE.appliedBatchesCreateTable());
            statement.execute("INSERT INTO lifetime_agg (player_uuid, first_join, last_join, last_seen, "
                    + "active_minutes, afk_minutes, total_minutes) VALUES ("
                    + "'player-one', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, "
                    + activeMinutes + ", 5, " + totalMinutes + ")");
        }
    }

    private File rollingBackup() {
        return temporaryDirectory.resolve("backups/playtime.db.last-good").toFile();
    }

    private long activeMinutes(DatabaseProvider provider) throws Exception {
        try (Connection connection = provider.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT active_minutes FROM lifetime_agg WHERE player_uuid = 'player-one'")) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    private long activeMinutes(File database) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.getAbsolutePath());
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT active_minutes FROM lifetime_agg WHERE player_uuid = 'player-one'")) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }
}

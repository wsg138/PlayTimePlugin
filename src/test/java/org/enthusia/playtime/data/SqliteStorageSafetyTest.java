package org.enthusia.playtime.data;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void establishedDatabaseMustContainExpectedSchema() throws Exception {
        File database = temporaryDirectory.resolve("playtime.db").toFile();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.getAbsolutePath())) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE unrelated (id INTEGER PRIMARY KEY)");
            }
            assertThrows(SQLException.class,
                    () -> SqliteStorageSafety.validateEstablishedDatabase(connection));
        }
    }

    @Test
    void populatedDatabaseSurvivesSingleRollingBackupReplacement() throws Exception {
        File database = temporaryDirectory.resolve("playtime.db").toFile();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.getAbsolutePath())) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(SqlDialect.SQLITE.lifetimeAggCreateTable());
                statement.execute("INSERT INTO lifetime_agg (player_uuid, first_join, last_join, last_seen, "
                        + "active_minutes, afk_minutes, total_minutes) VALUES ("
                        + "'player-one', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 120, 5, 125)");
            }

            assertTrue(SqliteStorageSafety.prepareDatabasePath(database, false));
            SqliteStorageSafety.validateEstablishedDatabase(connection);
            File firstBackup = SqliteStorageSafety.replaceRollingBackup(
                    connection, database, temporaryDirectory.toFile());
            assertEquals(120L, activeMinutes(firstBackup));

            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE lifetime_agg SET active_minutes = 240, total_minutes = 245 "
                        + "WHERE player_uuid = 'player-one'");
            }

            File secondBackup = SqliteStorageSafety.replaceRollingBackup(
                    connection, database, temporaryDirectory.toFile());
            assertEquals(firstBackup.getCanonicalPath(), secondBackup.getCanonicalPath());
            assertEquals(240L, activeMinutes(secondBackup));

            Path backups = temporaryDirectory.resolve("backups");
            try (var files = Files.list(backups)) {
                assertEquals(1L, files.filter(Files::isRegularFile).count());
            }
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

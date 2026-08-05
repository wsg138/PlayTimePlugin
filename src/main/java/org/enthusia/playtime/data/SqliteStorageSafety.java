package org.enthusia.playtime.data;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

final class SqliteStorageSafety {
    private static final String REQUIRED_TABLE = "lifetime_agg";
    private static final String BACKUP_SUFFIX = ".last-good";

    private SqliteStorageSafety() {
    }

    static boolean prepareDatabasePath(File databaseFile, boolean allowCreate) {
        if (databaseFile.isFile()) {
            if (databaseFile.length() <= 0L) {
                throw new IllegalStateException("Refusing to open empty SQLite database: "
                        + databaseFile.getAbsolutePath());
            }
            return true;
        }
        if (databaseFile.exists()) {
            throw new IllegalStateException("SQLite database path is not a file: "
                    + databaseFile.getAbsolutePath());
        }
        if (!allowCreate) {
            throw new IllegalStateException("Refusing to create a replacement SQLite database for an established "
                    + "installation. Restore the expected database before starting: "
                    + databaseFile.getAbsolutePath());
        }
        return false;
    }

    static void validateEstablishedDatabase(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA quick_check")) {
            String detail = result.next() ? result.getString(1) : "no result";
            if (!"ok".equalsIgnoreCase(detail)) {
                throw new SQLException("SQLite quick_check failed: " + detail);
            }
        }

        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT 1 FROM sqlite_master WHERE type='table' AND name='" + REQUIRED_TABLE + "'")) {
            if (!result.next()) {
                throw new SQLException("Established SQLite database is missing required table " + REQUIRED_TABLE);
            }
        }
    }

    static File replaceRollingBackup(Connection connection, File databaseFile, File dataFolder) throws SQLException {
        Path backupDirectory = dataFolder.toPath().resolve("backups");
        Path target = backupDirectory.resolve(databaseFile.getName() + BACKUP_SUFFIX);
        Path temporary = backupDirectory.resolve(databaseFile.getName() + BACKUP_SUFFIX + ".tmp");
        try {
            Files.createDirectories(backupDirectory);
            Files.deleteIfExists(temporary);
            String escapedPath = temporary.toAbsolutePath().toString().replace("'", "''");
            try (Statement statement = connection.createStatement()) {
                statement.execute("VACUUM INTO '" + escapedPath + "'");
            }
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException unsupportedAtomicMove) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return target.toFile();
        } catch (IOException | SQLException failure) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw new SQLException("Failed to replace rolling SQLite backup", failure);
        }
    }
}

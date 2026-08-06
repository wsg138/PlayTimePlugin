package org.enthusia.playtime.data;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

final class SqliteStorageSafety {
    private static final List<String> REQUIRED_TABLES = List.of(
            "daily_agg", "hourly_agg", "lifetime_agg", "joins_log",
            "player_profiles", "player_skin_profiles", "playtime_applied_batches");
    private static final String BACKUP_SUFFIX = ".last-good";
    private static final int RETAINED_BACKUPS = 3;

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
        validateRequiredSchema(connection);
    }

    static void validateRequiredSchema(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            for (String table : REQUIRED_TABLES) {
                statement.setString(1, table);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        throw new SQLException("Established SQLite database is missing required table " + table);
                    }
                }
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
            rotateBackups(target);
            moveReplacing(temporary, target);
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

    private static void rotateBackups(Path target) throws IOException {
        for (int index = RETAINED_BACKUPS - 1; index >= 1; index--) {
            Path source = target.resolveSibling(target.getFileName() + "." + index);
            Path destination = target.resolveSibling(target.getFileName() + "." + (index + 1));
            if (Files.exists(source)) {
                moveReplacing(source, destination);
            }
        }
        if (Files.exists(target)) {
            moveReplacing(target, target.resolveSibling(target.getFileName() + ".1"));
        }
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException unsupportedAtomicMove) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}

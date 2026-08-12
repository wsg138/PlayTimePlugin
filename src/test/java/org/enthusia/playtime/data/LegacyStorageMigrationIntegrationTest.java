package org.enthusia.playtime.data;

import org.bukkit.configuration.file.YamlConfiguration;
import org.enthusia.playtime.PlayTimePlugin;
import org.enthusia.playtime.config.ConfigMigrator;
import org.enthusia.playtime.config.PlaytimeConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.ZoneId;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * End-to-end regression for the destructive migration failure class: a populated
 * established SQLite database and legacy config must be upgraded in place without
 * losing rows, canaries, or the pre-migration recovery copy.
 */
class LegacyStorageMigrationIntegrationTest {
    private static final String PLAYER_UUID = "00000000-0000-0000-0000-000000000001";
    private static final String BATCH_UUID = "11111111-1111-1111-1111-111111111111";

    @TempDir
    Path dataFolder;

    @Test
    void legacyConfigAndPopulatedDatabaseUpgradeWithoutReplacementOrDataLoss() throws Exception {
        File configFile = dataFolder.resolve("config.yml").toFile();
        Files.writeString(configFile.toPath(), """
                config-version: 3
                storage:
                  type: sqlite
                  sqlite:
                    file: "playtime.db"
                sampling:
                  tick-interval: 20
                  idle-seconds: 60
                  afk-seconds: 777
                joins:
                  timezone: "America/New_York"
                """);

        File database = dataFolder.resolve("playtime.db").toFile();
        createLegacyDatabase(database);
        Object originalFileKey = Files.readAttributes(database.toPath(),
                java.nio.file.attribute.BasicFileAttributes.class).fileKey();

        PlayTimePlugin plugin = mock(PlayTimePlugin.class);
        when(plugin.getDataFolder()).thenReturn(dataFolder.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("LegacyStorageMigrationIntegrationTest"));
        when(plugin.getResource("config.yml")).thenAnswer(ignored -> defaultConfigResource());
        when(plugin.mayCreateInitialSqliteDatabase()).thenReturn(false);
        when(plugin.claimSqliteStartupBackup()).thenReturn(true);

        ConfigMigrator migrator = new ConfigMigrator(plugin);
        ConfigMigrator.MigrationResult migration = migrator.migrateConfig();
        assertEquals(3, migration.oldVersion());
        assertEquals(ConfigMigrator.CURRENT_CONFIG_VERSION, migration.newVersion());

        YamlConfiguration migratedConfig = ConfigMigrator.loadStrict(configFile);
        assertEquals(ConfigMigrator.CURRENT_CONFIG_VERSION,
                migratedConfig.getInt("config-version"));
        assertEquals(777L, migratedConfig.getLong("sampling.afk-seconds"));

        PlaytimeConfig runtimeConfig = mock(PlaytimeConfig.class);
        when(runtimeConfig.getSqliteFile()).thenReturn("playtime.db");
        when(runtimeConfig.joins()).thenReturn(
                new PlaytimeConfig.Joins(-1, ZoneId.of("America/New_York"), null));

        int providersBefore = DatabaseProvider.openProviderCountForTesting();
        DatabaseProvider provider = new DatabaseProvider(plugin, runtimeConfig);
        provider.init(StorageType.SQLITE);
        try {
            File backup = dataFolder.resolve("backups/playtime.db.last-good").toFile();
            assertTrue(backup.isFile());
            assertLegacyCanaries(backup, false);

            PlaytimeRepository repository = new PlaytimeRepository(plugin, provider, runtimeConfig);
            repository.initSchema();

            assertCurrentCanaries(database);
            Object currentFileKey = Files.readAttributes(database.toPath(),
                    java.nio.file.attribute.BasicFileAttributes.class).fileKey();
            if (originalFileKey != null && currentFileKey != null) {
                assertEquals(originalFileKey, currentFileKey,
                        "schema migration must not replace the established database file");
            }

            // A successful runtime would mark the now-migrated configuration good.
            migrator.markCurrentConfigGood();
            File configBackup = dataFolder.resolve("backups/config.yml.last-good").toFile();
            assertTrue(configBackup.isFile());
            YamlConfiguration backedUpConfig = ConfigMigrator.loadStrict(configBackup);
            assertEquals(ConfigMigrator.CURRENT_CONFIG_VERSION,
                    backedUpConfig.getInt("config-version"));
            assertEquals(777L, backedUpConfig.getLong("sampling.afk-seconds"));
        } finally {
            provider.shutdown();
        }
        assertEquals(providersBefore, DatabaseProvider.openProviderCountForTesting());
    }

    private InputStream defaultConfigResource() {
        InputStream stream = LegacyStorageMigrationIntegrationTest.class.getClassLoader()
                .getResourceAsStream("config.yml");
        if (stream == null) {
            throw new IllegalStateException("test runtime is missing config.yml resource");
        }
        return stream;
    }

    private void createLegacyDatabase(File database) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.getAbsolutePath());
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA user_version = 0");
            statement.execute("PRAGMA journal_mode = DELETE");
            statement.execute("""
                    CREATE TABLE daily_agg (
                      player_uuid TEXT NOT NULL,
                      day DATE NOT NULL,
                      active_minutes INTEGER NOT NULL DEFAULT 0,
                      afk_minutes INTEGER NOT NULL DEFAULT 0,
                      total_minutes INTEGER NOT NULL DEFAULT 0,
                      PRIMARY KEY (player_uuid, day)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE hourly_agg (
                      player_uuid TEXT NOT NULL,
                      hour_start TIMESTAMP NOT NULL,
                      active_minutes INTEGER NOT NULL DEFAULT 0,
                      afk_minutes INTEGER NOT NULL DEFAULT 0,
                      total_minutes INTEGER NOT NULL DEFAULT 0,
                      PRIMARY KEY (player_uuid, hour_start)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE lifetime_agg (
                      player_uuid TEXT PRIMARY KEY,
                      first_join TIMESTAMP NOT NULL,
                      last_join TIMESTAMP NOT NULL,
                      active_minutes INTEGER NOT NULL DEFAULT 0,
                      afk_minutes INTEGER NOT NULL DEFAULT 0,
                      total_minutes INTEGER NOT NULL DEFAULT 0
                    )
                    """);
            statement.execute("""
                    CREATE TABLE joins_log (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      player_uuid TEXT NOT NULL,
                      joined_at TIMESTAMP NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE player_profiles (
                      player_uuid TEXT PRIMARY KEY,
                      username TEXT NOT NULL,
                      display_name TEXT,
                      first_seen TIMESTAMP NOT NULL,
                      last_seen TIMESTAMP NOT NULL,
                      updated_at TIMESTAMP NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE player_skin_profiles (
                      player_uuid TEXT PRIMARY KEY,
                      texture_value TEXT,
                      texture_signature TEXT,
                      last_known_name TEXT,
                      updated_at TIMESTAMP NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE playtime_applied_batches (
                      batch_id TEXT PRIMARY KEY,
                      applied_at TIMESTAMP NOT NULL
                    )
                    """);

            statement.execute("INSERT INTO daily_agg VALUES ('" + PLAYER_UUID
                    + "', '2026-01-02', 321, 45, 366)");
            statement.execute("INSERT INTO hourly_agg VALUES ('" + PLAYER_UUID
                    + "', '2026-01-02 12:00:00', 60, 5, 65)");
            statement.execute("INSERT INTO lifetime_agg VALUES ('" + PLAYER_UUID
                    + "', '2025-06-01 12:00:00', '2026-01-02 12:34:56', 1234, 56, 1290)");
            statement.execute("INSERT INTO joins_log (id, player_uuid, joined_at) VALUES (1, '"
                    + PLAYER_UUID + "', '2026-01-02 12:34:56')");
            statement.execute("INSERT INTO player_profiles VALUES ('" + PLAYER_UUID
                    + "', 'sentineluser', 'Sentinel User', '2025-06-01 12:00:00', "
                    + "'2026-01-02 12:34:56', '2026-01-02 12:34:56')");
            statement.execute("INSERT INTO player_skin_profiles VALUES ('" + PLAYER_UUID
                    + "', 'sentinel-texture-canary', 'sentinel-signature-canary', "
                    + "'sentineluser', '2026-01-02 12:34:56')");
            statement.execute("INSERT INTO playtime_applied_batches VALUES ('" + BATCH_UUID
                    + "', '2026-01-02 12:34:56')");
        }
    }

    private void assertLegacyCanaries(File database, boolean expectLastSeen) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.getAbsolutePath());
             Statement statement = connection.createStatement()) {
            Set<String> columns = new java.util.HashSet<>();
            try (ResultSet result = statement.executeQuery("PRAGMA table_info(lifetime_agg)")) {
                while (result.next()) {
                    columns.add(result.getString("name"));
                }
            }
            assertEquals(expectLastSeen, columns.contains("last_seen"));
            assertEquals(1234L, scalarLong(statement,
                    "SELECT active_minutes FROM lifetime_agg WHERE player_uuid='" + PLAYER_UUID + "'"));
            assertEquals(366L, scalarLong(statement,
                    "SELECT total_minutes FROM daily_agg WHERE player_uuid='" + PLAYER_UUID + "'"));
            assertEquals(60L, scalarLong(statement,
                    "SELECT active_minutes FROM hourly_agg WHERE player_uuid='" + PLAYER_UUID + "'"));
            assertEquals("sentineluser", scalarString(statement,
                    "SELECT username FROM player_profiles WHERE player_uuid='" + PLAYER_UUID + "'"));
            assertEquals(BATCH_UUID, scalarString(statement,
                    "SELECT batch_id FROM playtime_applied_batches WHERE batch_id='" + BATCH_UUID + "'"));
            assertEquals("ok", scalarString(statement, "PRAGMA quick_check"));
        }
    }

    private void assertCurrentCanaries(File database) throws Exception {
        assertLegacyCanaries(database, true);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.getAbsolutePath());
             Statement statement = connection.createStatement()) {
            assertEquals("2026-01-02 12:34:56.0", scalarString(statement,
                    "SELECT last_seen FROM lifetime_agg WHERE player_uuid='" + PLAYER_UUID + "'"));
            assertEquals("2026-01-02 12:34:56.0", scalarString(statement,
                    "SELECT last_join FROM lifetime_agg WHERE player_uuid='" + PLAYER_UUID + "'"));
        }
    }

    private long scalarLong(Statement statement, String sql) throws Exception {
        try (ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    private String scalarString(Statement statement, String sql) throws Exception {
        try (ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }
}

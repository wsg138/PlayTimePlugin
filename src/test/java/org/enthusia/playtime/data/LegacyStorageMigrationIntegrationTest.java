package org.enthusia.playtime.data;

import org.bukkit.configuration.file.YamlConfiguration;
import org.enthusia.playtime.PlayTimePlugin;
import org.enthusia.playtime.config.ConfigMigrator;
import org.enthusia.playtime.config.PlaytimeConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    private static final String LEGACY_JOIN_TIME = "2026-01-02 12:34:56";

    @TempDir
    Path dataFolder;

    @BeforeEach
    void setUpServer() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDownServer() {
        MockBukkit.unmock();
    }

    @Test
    void legacyConfigAndPopulatedDatabaseUpgradeWithoutReplacementOrDataLoss() throws Exception {
        File configFile = writeLegacyConfig();
        File database = dataFolder.resolve("playtime.db").toFile();
        createLegacyDatabase(database);
        Object originalFileKey = fileKey(database);

        PlayTimePlugin plugin = configuredPlugin();
        ConfigMigrator migrator = migrateAndVerifyConfig(plugin, configFile);
        PlaytimeConfig runtimeConfig = runtimeConfig();

        int providersBefore = DatabaseProvider.openProviderCountForTesting();
        DatabaseProvider provider = new DatabaseProvider(plugin, runtimeConfig);
        provider.init(StorageType.SQLITE);
        try {
            assertPreMigrationBackup();
            new PlaytimeRepository(plugin, provider, runtimeConfig).initSchema();
            assertCurrentCanaries(database);
            assertDatabaseIdentityPreserved(database, originalFileKey);
            markGoodAndVerifyConfigBackup(migrator);
        } finally {
            provider.shutdown();
        }
        assertEquals(providersBefore, DatabaseProvider.openProviderCountForTesting());
    }

    private File writeLegacyConfig() throws Exception {
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
        return configFile;
    }

    private PlayTimePlugin configuredPlugin() {
        PlayTimePlugin plugin = mock(PlayTimePlugin.class);
        when(plugin.getDataFolder()).thenReturn(dataFolder.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("LegacyStorageMigrationIntegrationTest"));
        when(plugin.getResource("config.yml")).thenAnswer(ignored -> defaultConfigResource());
        when(plugin.mayCreateInitialSqliteDatabase()).thenReturn(false);
        when(plugin.claimSqliteStartupBackup()).thenReturn(true);
        return plugin;
    }

    private ConfigMigrator migrateAndVerifyConfig(PlayTimePlugin plugin, File configFile) {
        ConfigMigrator migrator = new ConfigMigrator(plugin);
        ConfigMigrator.MigrationResult migration = migrator.migrateConfig();
        assertEquals(3, migration.oldVersion());
        assertEquals(ConfigMigrator.CURRENT_CONFIG_VERSION, migration.newVersion());

        YamlConfiguration migratedConfig = ConfigMigrator.loadStrict(configFile);
        assertEquals(ConfigMigrator.CURRENT_CONFIG_VERSION, migratedConfig.getInt("config-version"));
        assertEquals(777L, migratedConfig.getLong("sampling.afk-seconds"));
        return migrator;
    }

    private PlaytimeConfig runtimeConfig() {
        PlaytimeConfig config = mock(PlaytimeConfig.class);
        when(config.getSqliteFile()).thenReturn("playtime.db");
        when(config.joins()).thenReturn(new PlaytimeConfig.Joins(-1, ZoneId.of("America/New_York"), null));
        return config;
    }

    private InputStream defaultConfigResource() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        InputStream stream = loader.getResourceAsStream("config.yml");
        if (stream == null) {
            throw new IllegalStateException("test runtime is missing config.yml resource");
        }
        return stream;
    }

    private void assertPreMigrationBackup() throws Exception {
        File backup = dataFolder.resolve("backups/playtime.db.last-good").toFile();
        assertTrue(backup.isFile());
        assertLegacyCanaries(backup, false);
    }

    private void assertDatabaseIdentityPreserved(File database, Object originalFileKey) throws Exception {
        Object currentFileKey = fileKey(database);
        if (originalFileKey != null && currentFileKey != null) {
            assertEquals(originalFileKey, currentFileKey,
                    "schema migration must not replace the established database file");
        }
    }

    private Object fileKey(File file) throws Exception {
        return Files.readAttributes(file.toPath(), BasicFileAttributes.class).fileKey();
    }

    private void markGoodAndVerifyConfigBackup(ConfigMigrator migrator) {
        migrator.markCurrentConfigGood();
        File backup = dataFolder.resolve("backups/config.yml.last-good").toFile();
        assertTrue(backup.isFile());
        YamlConfiguration config = ConfigMigrator.loadStrict(backup);
        assertEquals(ConfigMigrator.CURRENT_CONFIG_VERSION, config.getInt("config-version"));
        assertEquals(777L, config.getLong("sampling.afk-seconds"));
    }

    private void createLegacyDatabase(File database) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.getAbsolutePath());
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA user_version = 0");
            statement.execute("PRAGMA journal_mode = DELETE");
            createAggregationTables(statement);
            createProfileTables(statement);
            insertLegacyCanaries(connection);
        }
    }

    private void createAggregationTables(Statement statement) throws Exception {
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
    }

    private void createProfileTables(Statement statement) throws Exception {
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
    }

    private void insertLegacyCanaries(Connection connection) throws Exception {
        executeUpdate(connection, "INSERT INTO daily_agg VALUES (?, ?, ?, ?, ?)",
                PLAYER_UUID, "2026-01-02", 321, 45, 366);
        executeUpdate(connection, "INSERT INTO hourly_agg VALUES (?, ?, ?, ?, ?)",
                PLAYER_UUID, "2026-01-02 12:00:00", 60, 5, 65);
        executeUpdate(connection, "INSERT INTO lifetime_agg VALUES (?, ?, ?, ?, ?, ?)",
                PLAYER_UUID, "2025-06-01 12:00:00", LEGACY_JOIN_TIME, 1234, 56, 1290);
        executeUpdate(connection, "INSERT INTO joins_log (id, player_uuid, joined_at) VALUES (?, ?, ?)",
                1, PLAYER_UUID, LEGACY_JOIN_TIME);
        executeUpdate(connection, "INSERT INTO player_profiles VALUES (?, ?, ?, ?, ?, ?)",
                PLAYER_UUID, "sentineluser", "Sentinel User",
                "2025-06-01 12:00:00", LEGACY_JOIN_TIME, LEGACY_JOIN_TIME);
        executeUpdate(connection, "INSERT INTO player_skin_profiles VALUES (?, ?, ?, ?, ?)",
                PLAYER_UUID, "sentinel-texture-canary", "sentinel-signature-canary",
                "sentineluser", LEGACY_JOIN_TIME);
        executeUpdate(connection, "INSERT INTO playtime_applied_batches VALUES (?, ?)",
                BATCH_UUID, LEGACY_JOIN_TIME);
    }

    private void executeUpdate(Connection connection, String sql, Object... values) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            statement.executeUpdate();
        }
    }

    private void assertLegacyCanaries(File database, boolean expectLastSeen) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.getAbsolutePath())) {
            assertEquals(expectLastSeen, lifetimeColumns(connection).contains("last_seen"));
            assertEquals(1234L, queryLong(connection,
                    "SELECT active_minutes FROM lifetime_agg WHERE player_uuid = ?", PLAYER_UUID));
            assertEquals(366L, queryLong(connection,
                    "SELECT total_minutes FROM daily_agg WHERE player_uuid = ?", PLAYER_UUID));
            assertEquals(60L, queryLong(connection,
                    "SELECT active_minutes FROM hourly_agg WHERE player_uuid = ?", PLAYER_UUID));
            assertEquals("sentineluser", queryString(connection,
                    "SELECT username FROM player_profiles WHERE player_uuid = ?", PLAYER_UUID));
            assertEquals(BATCH_UUID, queryString(connection,
                    "SELECT batch_id FROM playtime_applied_batches WHERE batch_id = ?", BATCH_UUID));
            assertEquals("ok", queryString(connection, "PRAGMA quick_check"));
        }
    }

    private Set<String> lifetimeColumns(Connection connection) throws Exception {
        Set<String> columns = new HashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA table_info(lifetime_agg)")) {
            while (result.next()) {
                columns.add(result.getString("name"));
            }
        }
        return columns;
    }

    private void assertCurrentCanaries(File database) throws Exception {
        assertLegacyCanaries(database, true);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.getAbsolutePath())) {
            String lastSeen = queryString(connection,
                    "SELECT last_seen FROM lifetime_agg WHERE player_uuid = ?", PLAYER_UUID);
            String lastJoin = queryString(connection,
                    "SELECT last_join FROM lifetime_agg WHERE player_uuid = ?", PLAYER_UUID);
            assertEquals(lastJoin, lastSeen, "legacy last_seen must be backfilled from last_join");
        }
    }

    private long queryLong(Connection connection, String sql, String key) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getLong(1);
            }
        }
    }

    private String queryString(Connection connection, String sql, String key) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getString(1);
            }
        }
    }

    private String queryString(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }
}

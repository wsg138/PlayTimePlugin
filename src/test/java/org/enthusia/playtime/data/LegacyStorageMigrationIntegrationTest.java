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

    private static final String INSERT_DAILY = "INSERT INTO daily_agg VALUES (?, ?, ?, ?, ?)";
    private static final String INSERT_HOURLY = "INSERT INTO hourly_agg VALUES (?, ?, ?, ?, ?)";
    private static final String INSERT_LIFETIME = "INSERT INTO lifetime_agg VALUES (?, ?, ?, ?, ?, ?)";
    private static final String INSERT_JOIN =
            "INSERT INTO joins_log (id, player_uuid, joined_at) VALUES (?, ?, ?)";
    private static final String INSERT_PROFILE = "INSERT INTO player_profiles VALUES (?, ?, ?, ?, ?, ?)";
    private static final String INSERT_SKIN = "INSERT INTO player_skin_profiles VALUES (?, ?, ?, ?, ?)";
    private static final String INSERT_BATCH = "INSERT INTO playtime_applied_batches VALUES (?, ?)";
    private static final String SELECT_LIFETIME_ACTIVE =
            "SELECT active_minutes FROM lifetime_agg WHERE player_uuid = ?";
    private static final String SELECT_DAILY_TOTAL =
            "SELECT total_minutes FROM daily_agg WHERE player_uuid = ?";
    private static final String SELECT_HOURLY_ACTIVE =
            "SELECT active_minutes FROM hourly_agg WHERE player_uuid = ?";
    private static final String SELECT_USERNAME =
            "SELECT username FROM player_profiles WHERE player_uuid = ?";
    private static final String SELECT_BATCH =
            "SELECT batch_id FROM playtime_applied_batches WHERE batch_id = ?";
    private static final String SELECT_LAST_SEEN =
            "SELECT last_seen FROM lifetime_agg WHERE player_uuid = ?";
    private static final String SELECT_LAST_JOIN =
            "SELECT last_join FROM lifetime_agg WHERE player_uuid = ?";

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

    private ConfigMigrator migrateAndVerifyConfig(PlayTimePlugin plugin, File configFile) throws Exception {
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

    private void markGoodAndVerifyConfigBackup(ConfigMigrator migrator) throws Exception {
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
        executeUpdate(connection.prepareStatement(INSERT_DAILY),
                PLAYER_UUID, "2026-01-02", 321, 45, 366);
        executeUpdate(connection.prepareStatement(INSERT_HOURLY),
                PLAYER_UUID, "2026-01-02 12:00:00", 60, 5, 65);
        executeUpdate(connection.prepareStatement(INSERT_LIFETIME),
                PLAYER_UUID, "2025-06-01 12:00:00", LEGACY_JOIN_TIME, 1234, 56, 1290);
        insertProfileCanaries(connection);
    }

    private void insertProfileCanaries(Connection connection) throws Exception {
        executeUpdate(connection.prepareStatement(INSERT_JOIN), 1, PLAYER_UUID, LEGACY_JOIN_TIME);
        executeUpdate(connection.prepareStatement(INSERT_PROFILE),
                PLAYER_UUID, "sentineluser", "Sentinel User",
                "2025-06-01 12:00:00", LEGACY_JOIN_TIME, LEGACY_JOIN_TIME);
        executeUpdate(connection.prepareStatement(INSERT_SKIN),
                PLAYER_UUID, "sentinel-texture-canary", "sentinel-signature-canary",
                "sentineluser", LEGACY_JOIN_TIME);
        executeUpdate(connection.prepareStatement(INSERT_BATCH), BATCH_UUID, LEGACY_JOIN_TIME);
    }

    private void executeUpdate(PreparedStatement statement, Object... values) throws Exception {
        try (statement) {
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            statement.executeUpdate();
        }
    }

    private void assertLegacyCanaries(File database, boolean expectLastSeen) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.getAbsolutePath())) {
            assertEquals(expectLastSeen, lifetimeColumns(connection).contains("last_seen"));
            assertEquals(1234L, queryLong(connection.prepareStatement(SELECT_LIFETIME_ACTIVE), PLAYER_UUID));
            assertEquals(366L, queryLong(connection.prepareStatement(SELECT_DAILY_TOTAL), PLAYER_UUID));
            assertEquals(60L, queryLong(connection.prepareStatement(SELECT_HOURLY_ACTIVE), PLAYER_UUID));
            assertEquals("sentineluser", queryString(connection.prepareStatement(SELECT_USERNAME), PLAYER_UUID));
            assertEquals(BATCH_UUID, queryString(connection.prepareStatement(SELECT_BATCH), BATCH_UUID));
            assertEquals("ok", quickCheck(connection));
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

    private String quickCheck(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA quick_check")) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }

    private void assertCurrentCanaries(File database) throws Exception {
        assertLegacyCanaries(database, true);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.getAbsolutePath())) {
            String lastSeen = queryString(connection.prepareStatement(SELECT_LAST_SEEN), PLAYER_UUID);
            String lastJoin = queryString(connection.prepareStatement(SELECT_LAST_JOIN), PLAYER_UUID);
            assertEquals(lastJoin, lastSeen, "legacy last_seen must be backfilled from last_join");
        }
    }

    private long queryLong(PreparedStatement statement, String key) throws Exception {
        try (statement) {
            statement.setString(1, key);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getLong(1);
            }
        }
    }

    private String queryString(PreparedStatement statement, String key) throws Exception {
        try (statement) {
            statement.setString(1, key);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getString(1);
            }
        }
    }
}

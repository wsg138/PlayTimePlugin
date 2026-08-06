package org.enthusia.playtime.data;

import org.enthusia.playtime.PlayTimePlugin;
import org.enthusia.playtime.config.PlaytimeConfig;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.ZoneId;
import java.util.Locale;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

class SchemaMetadataMigrationTest {

    @Test
    void detectsExistingColumnBeforeRunningAlterTable() throws Exception {
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        ResultSet columns = mock(ResultSet.class);
        when(connection.getMetaData()).thenReturn(metadata);
        when(connection.getCatalog()).thenReturn("playtime");
        when(metadata.getColumns(anyString(), isNull(), anyString(), anyString())).thenReturn(columns);
        when(columns.next()).thenReturn(true);

        assertTrue(PlaytimeRepository.columnExists(connection, "lifetime_agg", "last_seen"));
    }

    @Test
    void reportsMissingColumnAfterCheckingIdentifierCaseVariants() throws Exception {
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        ResultSet columns = mock(ResultSet.class);
        when(connection.getMetaData()).thenReturn(metadata);
        when(connection.getCatalog()).thenReturn(null);
        when(metadata.getColumns(isNull(), isNull(), anyString(), anyString())).thenReturn(columns);
        when(columns.next()).thenReturn(false);

        assertFalse(PlaytimeRepository.columnExists(connection, "lifetime_agg", "last_seen"));
    }

    @Test
    void wildcardSimilarColumnDoesNotCountAsLastSeen() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE lifetime_agg (lastXseen TIMESTAMP)");

            assertFalse(PlaytimeRepository.columnExists(connection, "lifetime_agg", "last_seen"));
        }
    }

    @Test
    void exactLastSeenColumnIsDetectedWithEscapedMetadataPatterns() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE lifetime_agg (last_seen TIMESTAMP)");

            assertTrue(PlaytimeRepository.columnExists(connection, "lifetime_agg", "last_seen"));
        }
    }

    @Test
void nonDuplicateAlterFailureAbortsSchemaInitialization() throws Exception {
    PlayTimePlugin plugin = mock(PlayTimePlugin.class);
    when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
    DatabaseProvider provider = mock(DatabaseProvider.class);
    when(provider.getDialect()).thenReturn(SqlDialect.MYSQL);
    when(provider.reopenIfSqliteDbMoved(any())).thenReturn(false);
    Connection connection = mock(Connection.class);
    try (Statement statement = mock(Statement.class)) {
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        ResultSet columns = mock(ResultSet.class);
        when(provider.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(connection.getMetaData()).thenReturn(metadata);
        when(connection.getCatalog()).thenReturn("playtime");
        when(metadata.getColumns(anyString(), isNull(), anyString(), anyString())).thenReturn(columns);
        when(columns.next()).thenReturn(false);
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.toUpperCase(Locale.ROOT).contains("ADD COLUMN LAST_SEEN")) {
                throw new java.sql.SQLException("ALTER privilege denied");
            }
            return false;
        }).when(statement).execute(anyString());

        PlaytimeConfig config = mock(PlaytimeConfig.class);
        PlaytimeConfig.Joins joins = mock(PlaytimeConfig.Joins.class);
        when(config.joins()).thenReturn(joins);
        when(joins.zoneId()).thenReturn(ZoneId.of("UTC"));
        PlaytimeRepository repository = new PlaytimeRepository(plugin, provider, config);

        assertThrows(java.sql.SQLException.class, repository::initSchema);
    }
}

}

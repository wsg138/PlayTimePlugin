package org.enthusia.playtime.data;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
}

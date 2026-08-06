package org.enthusia.playtime.data;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlDialectMigrationTest {

    @Test
    void mariaDbCompatibleMigrationDoesNotRaiseDuplicateColumnError() {
        String sql = SqlDialect.MYSQL.lifetimeAggAddLastSeenColumn().toLowerCase(Locale.ROOT);

        assertTrue(sql.contains("add column if not exists last_seen"));
    }

    @Test
    void sqliteMigrationKeepsSupportedAddColumnSyntax() {
        String sql = SqlDialect.SQLITE.lifetimeAggAddLastSeenColumn().toLowerCase(Locale.ROOT);

        assertTrue(sql.contains("add column last_seen"));
        assertFalse(sql.contains("if not exists"));
    }
}

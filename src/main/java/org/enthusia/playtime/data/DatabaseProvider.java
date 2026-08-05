package org.enthusia.playtime.data;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.plugin.java.JavaPlugin;
import org.enthusia.playtime.PlayTimePlugin;
import org.enthusia.playtime.config.PlaytimeConfig;
import org.sqlite.SQLiteException;

import javax.sql.DataSource;
import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DatabaseProvider {
    private static final AtomicInteger OPEN_PROVIDERS = new AtomicInteger();
    private static final int SQLITE_READONLY_DBMOVED = 1038;
    private static final int SQLITE_READONLY_DIRECTORY_MOVED = 1039;

    private final JavaPlugin plugin;
    private final PlaytimeConfig config;
    private final Object lock = new Object();
    private HikariDataSource dataSource;
    private SqlDialect dialect;
    private StorageType storageType;
    private File sqliteFile;
    private boolean sqliteCreationAllowed;
    private boolean sqliteExistedBeforeOpen;
    private final AtomicBoolean countedOpen = new AtomicBoolean();

    public DatabaseProvider(JavaPlugin plugin, PlaytimeConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void init(StorageType type) {
        this.storageType = type;
        this.dialect = SqlDialect.fromStorageType(type);

        if (type == StorageType.SQLITE) {
            this.sqliteFile = new File(plugin.getDataFolder(), config.getSqliteFile()).getAbsoluteFile();
            this.sqliteCreationAllowed = plugin instanceof PlayTimePlugin playTimePlugin
                    && playTimePlugin.mayCreateInitialSqliteDatabase();
            this.sqliteExistedBeforeOpen = SqliteStorageSafety.prepareDatabasePath(
                    sqliteFile, sqliteCreationAllowed);
        }

        synchronized (lock) {
            try {
                rebuildDataSource();
                if (type == StorageType.SQLITE) {
                    sqliteCreationAllowed = false;
                    validateAndBackupEstablishedSqlite();
                }
                if (countedOpen.compareAndSet(false, true)) OPEN_PROVIDERS.incrementAndGet();
            } catch (LinkageError failure) {
                // sqlite-jdbc loads JNI methods while the pool opens. Convert linkage failures into
                // a normal runtime initialization failure so reload can roll back the prepared runtime.
                throw new DatabaseInitializationException(failure);
            }
        }
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public boolean reopenIfSqliteDbMoved(SQLException ex) {
        if (dialect != SqlDialect.SQLITE || !isSqliteDbMoved(ex)) {
            return false;
        }

        Logger log = plugin.getLogger();
        if (log.isLoggable(Level.WARNING)) {
            log.warning("SQLite database file appears to have been moved; reopening the connection pool.");
        }

        synchronized (lock) {
            try {
                sqliteCreationAllowed = false;
                SqliteStorageSafety.prepareDatabasePath(sqliteFile, false);
                rebuildDataSource();
                try (Connection connection = dataSource.getConnection()) {
                    SqliteStorageSafety.validateEstablishedDatabase(connection);
                }
                return true;
            } catch (Exception failure) {
                if (log.isLoggable(Level.SEVERE)) {
                    log.severe("Failed to reopen SQLite database after move: " + failure.getMessage());
                }
                return false;
            }
        }
    }

    public SqlDialect getDialect() {
        return dialect;
    }

    public void shutdown() {
        synchronized (lock) {
            if (dataSource != null) {
                dataSource.close();
            }
            if (countedOpen.compareAndSet(true, false)) OPEN_PROVIDERS.decrementAndGet();
        }
    }

    public static int openProviderCountForTesting() {
        return OPEN_PROVIDERS.get();
    }

    private void rebuildDataSource() {
        HikariDataSource candidate = null;
        RuntimeException failure = null;
        try {
            candidate = createDataSource();
            validateConnection(candidate);
            HikariDataSource previous = this.dataSource;
            this.dataSource = candidate;
            candidate = null;
            if (previous != null) {
                previous.close();
            }
        } catch (RuntimeException exception) {
            failure = exception;
            throw exception;
        } finally {
            if (candidate != null) {
                try {
                    candidate.close();
                } catch (RuntimeException closeFailure) {
                    if (failure != null) {
                        failure.addSuppressed(closeFailure);
                    } else {
                        throw closeFailure;
                    }
                }
            }
        }
    }

    private HikariDataSource createDataSource() {
        HikariConfig hikariConfig = new HikariConfig();

        if (storageType == StorageType.SQLITE) {
            SqliteStorageSafety.prepareDatabasePath(sqliteFile, sqliteCreationAllowed);
            File parent = sqliteFile.getParentFile();
            if (parent != null) {
                // Ensure directory exists for a genuine first installation only. Established
                // installations are rejected above if their database unexpectedly disappears.
                parent.mkdirs();
            }

            String jdbcUrl = "jdbc:sqlite:" + sqliteFile.getAbsolutePath();
            hikariConfig.setJdbcUrl(jdbcUrl);
            hikariConfig.setDriverClassName(org.sqlite.JDBC.class.getName());
            hikariConfig.setConnectionTestQuery("SELECT 1");
            hikariConfig.setMaximumPoolSize(5);
        } else {
            String host = config.getMysqlHost();
            int port = config.getMysqlPort();
            String db = config.getMysqlDatabase();
            String jdbcUrl = "jdbc:mariadb://" + host + ":" + port + "/" + db +
                    "?useUnicode=true&characterEncoding=UTF-8" +
                    "&useSSL=" + config.isMysqlUseSsl();

            hikariConfig.setJdbcUrl(jdbcUrl);
            hikariConfig.setDriverClassName(org.mariadb.jdbc.Driver.class.getName());
            hikariConfig.setUsername(config.getMysqlUsername());
            hikariConfig.setPassword(config.getMysqlPassword());
            hikariConfig.setMaximumPoolSize(config.getMysqlPoolSize());
            hikariConfig.setConnectionTestQuery("SELECT 1");
        }

        hikariConfig.setPoolName("EnthusiaPlaytimePool");
        hikariConfig.setLeakDetectionThreshold(0);

        return new HikariDataSource(hikariConfig);
    }

    private void validateAndBackupEstablishedSqlite() {
        if (!sqliteExistedBeforeOpen) {
            return;
        }
        boolean createStartupBackup = plugin instanceof PlayTimePlugin playTimePlugin
                && playTimePlugin.claimSqliteStartupBackup();
        try (Connection connection = dataSource.getConnection()) {
            if (createStartupBackup) {
                SqliteStorageSafety.validateEstablishedDatabase(connection);
                File backup = SqliteStorageSafety.replaceRollingBackup(
                        connection, sqliteFile, plugin.getDataFolder());
                plugin.getLogger().info("Verified SQLite database " + sqliteFile.getAbsolutePath()
                        + " and replaced rolling startup backup " + backup.getAbsolutePath() + ".");
            } else {
                SqliteStorageSafety.validateRequiredSchema(connection);
            }
        } catch (SQLException failure) {
            throw new DatabaseInitializationException(failure);
        }
    }

    private void validateConnection(DataSource ds) {
        try (var conn = ds.getConnection()) {
            // ok
        } catch (Exception e) {
            Logger log = plugin.getLogger();
            if (log.isLoggable(Level.SEVERE)) {
                log.severe("Failed to open initial database connection: " + e.getMessage());
            }
            throw new DatabaseInitializationException(e);
        }
    }

    private boolean isSqliteDbMoved(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SQLiteException se && sqliteMoved(se)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean sqliteMoved(SQLiteException exception) {
        String msg = exception.getMessage();
        if (msg != null) {
            String upper = msg.toUpperCase(Locale.ROOT);
            if (upper.contains("READONLY_DBMOVED") || upper.contains("DATABASE FILE HAS BEEN MOVED")) {
                return true;
            }
        }
        int code = exception.getErrorCode();
        return code == SQLITE_READONLY_DBMOVED || code == SQLITE_READONLY_DIRECTORY_MOVED;
    }

    private static final class DatabaseInitializationException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private DatabaseInitializationException(Throwable cause) {
            super(cause);
        }
    }
}

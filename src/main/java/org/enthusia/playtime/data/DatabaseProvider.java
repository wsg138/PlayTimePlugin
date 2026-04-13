package org.enthusia.playtime.data;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.plugin.java.JavaPlugin;
import org.enthusia.playtime.config.PlaytimeConfig;
import org.sqlite.SQLiteException;

import javax.sql.DataSource;
import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import java.util.logging.Logger;

public final class DatabaseProvider {

    private final JavaPlugin plugin;
    private final PlaytimeConfig config;
    private final Object lock = new Object();
    private HikariDataSource dataSource;
    private SqlDialect dialect;
    private StorageType storageType;
    private File sqliteFile;

    public DatabaseProvider(JavaPlugin plugin, PlaytimeConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void init(StorageType type) {
        this.storageType = type;
        this.dialect = SqlDialect.fromStorageType(type);

        if (type == StorageType.SQLITE) {
            this.sqliteFile = new File(plugin.getDataFolder(), config.getSqliteFile()).getAbsoluteFile();
        }

        synchronized (lock) {
            rebuildDataSource();
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
        log.warning("SQLite database file appears to have been moved; reopening the connection pool.");

        synchronized (lock) {
            try {
                rebuildDataSource();
                return true;
            } catch (RuntimeException rte) {
                log.severe("Failed to reopen SQLite database after move: " + rte.getMessage());
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
                dataSource = null;
            }
        }
    }

    private void rebuildDataSource() {
        HikariDataSource newDs = createDataSource();
        validateConnection(newDs);

        if (this.dataSource != null) {
            this.dataSource.close();
        }

        this.dataSource = newDs;
    }

    private HikariDataSource createDataSource() {
        HikariConfig hikariConfig = new HikariConfig();

        if (storageType == StorageType.SQLITE) {
            File parent = sqliteFile.getParentFile();
            if (parent != null) {
                // Ensure directory exists in case the data folder was moved or recreated
                parent.mkdirs();
            }

            String jdbcUrl = "jdbc:sqlite:" + sqliteFile.getAbsolutePath();
            hikariConfig.setJdbcUrl(jdbcUrl);
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
            hikariConfig.setUsername(config.getMysqlUsername());
            hikariConfig.setPassword(config.getMysqlPassword());
            hikariConfig.setMaximumPoolSize(config.getMysqlPoolSize());
            hikariConfig.setConnectionTestQuery("SELECT 1");
        }

        hikariConfig.setPoolName("EnthusiaPlaytimePool");
        hikariConfig.setLeakDetectionThreshold(0);

        return new HikariDataSource(hikariConfig);
    }

    private void validateConnection(DataSource ds) {
        try (var conn = ds.getConnection()) {
            // ok
        } catch (Exception e) {
            Logger log = plugin.getLogger();
            log.severe("Failed to open initial database connection: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    private boolean isSqliteDbMoved(Throwable throwable) {
        while (throwable != null) {
            if (throwable instanceof SQLiteException se) {
                String msg = se.getMessage();
                if (msg != null) {
                    String upper = msg.toUpperCase(Locale.ROOT);
                    if (upper.contains("READONLY_DBMOVED") || upper.contains("DATABASE FILE HAS BEEN MOVED")) {
                        return true;
                    }
                }
                int code = se.getErrorCode();
                // Extended error code for READONLY_DBMOVED is 1038; include nearby values just in case.
                if (code == 1038 || code == 1039) {
                    return true;
                }
            }
            throwable = throwable.getCause();
        }
        return false;
    }
}

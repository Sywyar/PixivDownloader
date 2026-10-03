package top.sywyar.pixivdownload.core.db.sqlite;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.sqlite.SQLiteConfig;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/** 宿主 SQLite 连接与维护设置；路径定位和历史文件迁移仍由 RuntimeFiles 负责。 */
public final class SqliteDatabase {

    private SqliteDatabase() {}

    public static String jdbcUrl(Path databasePath) {
        return "jdbc:sqlite:" + databasePath;
    }

    private static Properties connectionProperties() {
        SQLiteConfig configuration = new SQLiteConfig();
        configuration.setBusyTimeout(5000);
        configuration.setJournalMode(SQLiteConfig.JournalMode.WAL);
        return configuration.toProperties();
    }

    /** 既有按路径访问的启动检查、桌面及迁移工具使用；运行期业务使用宿主连接池。 */
    public static Connection openConnection(Path databasePath) throws SQLException {
        return DriverManager.getConnection(jdbcUrl(databasePath), connectionProperties());
    }

    public static HikariDataSource openPool(Path databasePath, int maximumPoolSize) {
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("pixiv-sqlite");
        hikari.setDriverClassName("org.sqlite.JDBC");
        hikari.setJdbcUrl(jdbcUrl(databasePath));
        // 设置传给每条物理连接；SQLite 单写者的等待与宿主池容量分别控制。
        hikari.setDataSourceProperties(connectionProperties());
        hikari.setMaximumPoolSize(maximumPoolSize);
        hikari.setMinimumIdle(1);
        hikari.setConnectionInitSql("PRAGMA busy_timeout=5000");
        return new HikariDataSource(hikari);
    }

    /** 由维护协调器借用宿主池连接执行，不创建额外连接或调度。 */
    public static void optimize(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA optimize");
        }
    }
}

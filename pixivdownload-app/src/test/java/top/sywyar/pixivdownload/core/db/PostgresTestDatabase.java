package top.sywyar.pixivdownload.core.db;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;

/** 每个测试独占随机 schema，只删除本测试创建的对象。连接信息由测试环境提供。 */
public final class PostgresTestDatabase implements AutoCloseable {
    private final String schema = "test_" + UUID.randomUUID().toString().replace("-", "");
    private final Connection admin;
    private final HikariDataSource pool;
    private final DatabasePoolProperties properties;
    private final DatabaseTarget target;

    public PostgresTestDatabase() throws Exception {
        String url = System.getenv("PIXIV_TEST_POSTGRES_URL");
        String username = System.getenv("PIXIV_TEST_POSTGRES_USER");
        String password = System.getenv("PIXIV_TEST_POSTGRES_PASSWORD");
        admin = DriverManager.getConnection(url, username, password);
        boolean created = false;
        try {
            try (var statement = admin.createStatement()) {
                statement.execute("CREATE SCHEMA " + schema);
                created = true;
            }
            properties = new DatabasePoolProperties();
            properties.setEngine("postgresql");
            properties.setJdbcUrl(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema);
            properties.setUsername(username);
            properties.setPassword(password);
            target = top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.resolve(properties, "unused");
            pool = target.openPool(DatabasePoolProperties.MINIMUM_POOL_SIZE);
        } catch (Exception | Error failure) {
            try (admin) {
                if (created) try (var statement = admin.createStatement()) {
                    statement.execute("DROP SCHEMA " + schema + " CASCADE");
                }
            } catch (VirtualMachineError | ThreadDeath fatal) {
                if (fatal != failure) fatal.addSuppressed(failure);
                throw fatal;
            } catch (Exception | Error cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    public HikariDataSource pool() { return pool; }
    public DatabasePoolProperties properties() { return properties; }

    public org.springframework.jdbc.support.JdbcTransactionManager transactionManager() {
        return new DatabaseConfig(null, null).transactionManager(pool, target);
    }

    @Override
    public void close() throws Exception {
        pool.close();
        try (admin; var statement = admin.createStatement()) {
            statement.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }
}

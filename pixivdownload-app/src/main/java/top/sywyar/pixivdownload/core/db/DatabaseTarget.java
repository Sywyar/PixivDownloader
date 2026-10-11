package top.sywyar.pixivdownload.core.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.postgresql.Driver;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.StandardEnvironment;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.core.db.sqlite.SqliteDatabase;
import top.sywyar.pixivdownload.i18n.MessageBundles;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import java.util.Set;

/** Spring、启动检查和停机工具共用的主库目标；字符串表示不含地址或凭据。 */
public final class DatabaseTarget {
    public static final int SUPPORTED_POSTGRES_MAJOR = 17;
    private static final Set<String> URL_OPTIONS = Set.of(
            "sslmode", "sslrootcert", "currentSchema", "connectTimeout", "socketTimeout",
            "tcpKeepAlive", "ApplicationName");

    private final DatabaseEngine engine;
    private final Path sqlitePath;
    private final String jdbcUrl;
    private final String username;
    private final String password;

    /** 与后端共用 Boot 配置加载及命令行、系统属性、环境变量的优先级。 */
    public static DatabaseTarget fromConfiguration(String... args) throws IOException {
        RuntimeFiles.resolveConfigYamlPath();
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new SimpleCommandLinePropertySource(args));
        ConfigurationPropertySources.attach(environment);
        ConfigDataEnvironmentPostProcessor.applyTo(environment);
        DatabasePoolProperties properties = Binder.get(environment)
                .bind("database", DatabasePoolProperties.class).orElseGet(DatabasePoolProperties::new);
        return resolve(properties, environment.getProperty("download.root-folder", RuntimeFiles.DEFAULT_DOWNLOAD_ROOT));
    }

    public static DatabaseTarget sqlite(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        return new DatabaseTarget(DatabaseEngine.SQLITE, normalized, SqliteDatabase.jdbcUrl(normalized), "", "");
    }

    public String displayName() {
        return engine == DatabaseEngine.SQLITE ? sqlitePath.toString() : engine.databaseId();
    }

    private DatabaseTarget(DatabaseEngine engine, Path sqlitePath, String jdbcUrl,
                           String username, String password) {
        this.engine = engine;
        this.sqlitePath = sqlitePath;
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
    }

    public static DatabaseTarget resolve(DatabasePoolProperties config, String rootFolder) throws IOException {
        DatabaseEngine engine = DatabaseEngine.parse(config.getEngine());
        String url = config.getJdbcUrl().trim();
        if (engine == DatabaseEngine.SQLITE) {
            if (!config.getUsername().isEmpty() || !config.getPassword().isEmpty()) {
                throw invalid("database.username / database.password");
            }
            Path path;
            if (url.isEmpty()) {
                path = RuntimeFiles.resolveDatabasePath(rootFolder);
            } else {
                if (!url.startsWith("jdbc:sqlite:") || url.length() == "jdbc:sqlite:".length()
                        || url.contains("?") || url.contains(":memory:")) {
                    throw invalid("database.jdbc-url");
                }
                path = Path.of(url.substring("jdbc:sqlite:".length())).toAbsolutePath().normalize();
            }
            return new DatabaseTarget(engine, path, SqliteDatabase.jdbcUrl(path), "", "");
        }
        // 连接目标必须明确；不采用驱动的本机地址、用户名或数据库名推断。
        if (!url.startsWith("jdbc:postgresql://") || config.getUsername().isBlank()) {
            throw invalid("database.jdbc-url / database.username");
        }
        int query = url.indexOf('?');
        String address = query < 0 ? url : url.substring(0, query);
        int pathStart = address.indexOf('/', "jdbc:postgresql://".length());
        if (address.contains("@") || address.endsWith("/") || pathStart <= "jdbc:postgresql://".length()) {
            throw invalid("database.jdbc-url");
        }
        for (String host : address.substring("jdbc:postgresql://".length(), pathStart).split(",", -1)) {
            if (host.isBlank() || host.startsWith(":")) throw invalid("database.jdbc-url");
        }
        if (query >= 0) {
            Set<String> seen = new java.util.HashSet<>();
            for (String option : url.substring(query + 1).split("&", -1)) {
                String[] pair = option.split("=", 2);
                String key;
                try {
                    key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
                    if (pair.length == 2) {
                        URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                    }
                } catch (IllegalArgumentException malformedEscape) {
                    throw invalid("database.jdbc-url");
                }
                if (pair.length != 2 || !URL_OPTIONS.contains(key) || !seen.add(key)) {
                    throw invalid("database.jdbc-url");
                }
            }
        }
        Properties parsed = Driver.parseURL(url, new Properties());
        if (parsed == null || parsed.getProperty("PGDBNAME", "").isBlank()
                || !parsed.getProperty("currentSchema", "public").matches("[a-z_][a-z0-9_]{0,62}")) {
            throw invalid("database.jdbc-url");
        }
        return new DatabaseTarget(engine, null, url, config.getUsername(), config.getPassword());
    }

    public DatabaseEngine engine() {
        return engine;
    }

    public Path sqlitePath() {
        if (engine != DatabaseEngine.SQLITE) {
            throw invalid("database.engine");
        }
        return sqlitePath;
    }

    /** 借用活动连接时验证其物理目标，防止工具把指定路径或 schema 写入另一数据库。 */
    public void requireConnectionTarget(Connection connection) throws SQLException {
        boolean matches = DatabaseEngine.detect(connection) == engine;
        String actualUrl = connection.getMetaData().getURL();
        if (matches && engine == DatabaseEngine.SQLITE) {
            matches = actualUrl != null && actualUrl.startsWith("jdbc:sqlite:")
                    && sqlitePath.equals(Path.of(actualUrl.substring("jdbc:sqlite:".length())).toAbsolutePath().normalize());
        } else if (matches) {
            Properties expected = Driver.parseURL(jdbcUrl, new Properties());
            Properties actual = Driver.parseURL(actualUrl, new Properties());
            matches = actual != null && expected.getProperty("currentSchema", "public").equals(connection.getSchema());
            for (String key : Set.of("PGHOST", "PGPORT", "PGDBNAME")) {
                matches &= actual != null && java.util.Objects.equals(expected.getProperty(key), actual.getProperty(key));
            }
        }
        if (!matches) throw new SQLException(MessageBundles.get("core.db.invalid-config", "database target"));
    }

    public Connection openConnection() throws SQLException {
        if (engine == DatabaseEngine.SQLITE) {
            return SqliteDatabase.openConnection(sqlitePath);
        }
        Properties properties = new Properties();
        properties.setProperty("user", username);
        properties.setProperty("password", password);
        properties.setProperty("connectTimeout", "10");
        properties.setProperty("ApplicationName", "PixivDownloader");
        properties.setProperty("currentSchema", "public");
        Connection connection = null;
        try {
            connection = DriverManager.getConnection(jdbcUrl, properties);
            validate(connection);
            return connection;
        } catch (SQLException failure) {
            if (connection != null) {
                try { connection.close(); } catch (SQLException ignored) { }
            }
            // 驱动消息可能包含服务器地址与认证材料，用户诊断只暴露 SQLState。
            throw new SQLException(MessageBundles.get("core.db.connection-failed",
                    engine.databaseId(), failure.getSQLState()), failure.getSQLState());
        }
    }

    public HikariDataSource openPool(int maximumPoolSize) throws IOException {
        if (engine == DatabaseEngine.SQLITE) {
            Files.createDirectories(sqlitePath.toAbsolutePath().getParent());
            return SqliteDatabase.openPool(sqlitePath, maximumPoolSize);
        }
        try (Connection ignored = openConnection()) {
            HikariConfig config = new HikariConfig();
            config.setPoolName("pixiv-postgresql");
            config.setDriverClassName("org.postgresql.Driver");
            config.setJdbcUrl(jdbcUrl);
            config.setUsername(username);
            config.setPassword(password);
            config.addDataSourceProperty("currentSchema", "public");
            config.addDataSourceProperty("connectTimeout", "10");
            config.addDataSourceProperty("ApplicationName", "PixivDownloader");
            config.setMaximumPoolSize(maximumPoolSize);
            config.setMinimumIdle(1);
            return new HikariDataSource(config);
        } catch (SQLException failure) {
            throw new IOException(failure.getMessage());
        } catch (RuntimeException failure) {
            // 预检与建池之间也可能断线，不让连接池包装异常泄露驱动原文。
            String state = "unknown";
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sql) { state = sql.getSQLState(); break; }
            }
            throw new IOException(MessageBundles.get("core.db.connection-failed", engine.databaseId(), state));
        }
    }

    private void validate(Connection connection) throws SQLException {
        if (DatabaseEngine.detect(connection) != engine
                || connection.getMetaData().getDatabaseMajorVersion() != SUPPORTED_POSTGRES_MAJOR
                || connection.getSchema() == null) {
            throw new SQLException(MessageBundles.get("core.db.unsupported-product"), "0A000");
        }
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SHOW server_encoding")) {
            if (!rows.next() || !"UTF8".equals(rows.getString(1))) {
                throw new SQLException(MessageBundles.get("core.db.unsupported-product"), "0A000");
            }
        }
    }

    private static IllegalArgumentException invalid(String key) {
        return new IllegalArgumentException(MessageBundles.get("core.db.invalid-config", key));
    }

    @Override
    public String toString() {
        return engine.databaseId();
    }
}

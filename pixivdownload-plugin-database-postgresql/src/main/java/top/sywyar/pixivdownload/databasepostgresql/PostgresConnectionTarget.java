package top.sywyar.pixivdownload.databasepostgresql;

import org.postgresql.Driver;
import top.sywyar.pixivdownload.plugin.api.database.DatabaseConnectionSettings;
import top.sywyar.pixivdownload.plugin.api.database.DatabaseConnectionTarget;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/** 固定 PostgreSQL 数据库和 schema；驱动、连接限制与目标校验由外置插件拥有。 */
final class PostgresConnectionTarget implements DatabaseConnectionTarget {
    @Override public void optimize(Connection connection) throws SQLException { PostgresStorageSchema.optimize(connection); }

    @Override public SchemaApplyResult applySchema(Connection connection,
            top.sywyar.pixivdownload.plugin.api.database.migration.MigrationSchema schema, boolean strict) throws SQLException {
        return PostgresStorageSchema.apply(connection, schema, strict);
    }
    @Override public top.sywyar.pixivdownload.plugin.api.database.migration.jdbc.PhysicalDatabaseSchema.DatabaseSchema physicalSchema(
            top.sywyar.pixivdownload.plugin.api.database.migration.jdbc.PhysicalDatabaseSchema.DatabaseSchema schema) {
        return PostgresMigrationSchema.physicalSchema(schema);
    }
    @Override public java.util.Map<String, top.sywyar.pixivdownload.plugin.api.database.migration.jdbc.PhysicalDatabaseSchema.TableSpec>
            readSchema(Connection connection) throws SQLException {
        return PostgresMigrationSchema.readActualTables(connection);
    }
    @Override public void initializeStorage(Connection connection,
            top.sywyar.pixivdownload.plugin.api.database.migration.MigrationSchema schema) throws SQLException {
        PostgresStorageSession.initialize(connection, schema);
    }
    @Override public top.sywyar.pixivdownload.plugin.api.storage.relational.DatabaseSession storageSession(
            Connection connection, top.sywyar.pixivdownload.plugin.api.database.migration.MigrationSchema schema) {
        return new PostgresStorageSession(connection, schema);
    }
    private static final Set<Integer> SUPPORTED_MAJORS = Set.of(17, 18);
    private static final String PREFIX = "jdbc:postgresql://";
    private static final Set<String> URL_OPTIONS = Set.of("sslmode", "sslrootcert", "currentSchema",
            "connectTimeout", "socketTimeout", "tcpKeepAlive", "ApplicationName");
    private final String address;
    private final Properties properties;

    PostgresConnectionTarget(DatabaseConnectionSettings settings) throws SQLException {
        String url = settings.jdbcUrl().trim();
        if (!url.startsWith(PREFIX) || settings.username().isBlank()) throw invalid();
        int query = url.indexOf('?');
        address = query < 0 ? url : url.substring(0, query);
        int pathStart = address.indexOf('/', PREFIX.length());
        if (address.contains("@") || address.endsWith("/") || pathStart <= PREFIX.length()) throw invalid();
        for (String host : address.substring(PREFIX.length(), pathStart).split(",", -1)) {
            if (host.isBlank() || host.startsWith(":")) throw invalid();
        }
        if (query >= 0) {
            var seen = new HashSet<String>();
            for (String option : url.substring(query + 1).split("&", -1)) {
                String[] pair = option.split("=", 2);
                if (pair.length != 2) throw invalid();
                try {
                    String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
                    URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                    if (!URL_OPTIONS.contains(key) || !seen.add(key)) throw invalid();
                } catch (IllegalArgumentException failure) {
                    throw invalid();
                }
            }
        }
        properties = Driver.parseURL(url, new Properties());
        if (properties == null || properties.getProperty("PGDBNAME", "").isBlank()
                || !properties.getProperty("currentSchema", "public").matches("[a-z_][a-z0-9_]{0,62}")) {
            throw invalid();
        }
        properties.setProperty("user", settings.username());
        properties.setProperty("password", settings.password());
        properties.putIfAbsent("currentSchema", "public");
        properties.putIfAbsent("connectTimeout", "10");
        properties.putIfAbsent("ApplicationName", "PixivDownloader");
    }

    @Override public String description() {
        return "postgresql: " + properties.getProperty("PGHOST") + ":" + properties.getProperty("PGPORT")
                + "/" + properties.getProperty("PGDBNAME") + " [" + properties.getProperty("currentSchema") + "]";
    }

    @Override public Optional<Path> localFile() { return Optional.empty(); }

    @Override public DatabaseConnectionTarget privateStorage(
            top.sywyar.pixivdownload.plugin.api.database.DatabasePrivateStorageSettings settings) throws SQLException {
        return new PostgresPrivateStorage(this, settings);
    }

    Properties connectionProperties() {
        var copy = new Properties();
        copy.putAll(properties);
        return copy;
    }

    String address() { return address; }

    PostgresConnectionTarget forOwner(String role, String password, String schema) {
        var copy = connectionProperties();
        copy.setProperty("user", role);
        copy.setProperty("password", password);
        copy.setProperty("currentSchema", schema);
        return new PostgresConnectionTarget(address, copy);
    }

    private PostgresConnectionTarget(String address, Properties properties) {
        this.address = address;
        this.properties = properties;
    }

    @Override public Connection openConnection(OpenMode mode) throws SQLException {
        if (mode == null) throw invalid();
        return connect(properties, mode == OpenMode.READ_ONLY);
    }

    private Connection connect(Properties configuration, boolean readOnly) throws SQLException {
        Connection connection = null;
        try {
            connection = new Driver().connect(address, configuration);
            if (connection == null) throw invalid();
            validate(connection);
            requireConnectionTarget(connection);
            if (readOnly) connection.setReadOnly(true);
            return connection;
        } catch (SQLException failure) {
            if (connection != null) {
                try { connection.close(); } catch (SQLException ignored) { }
            }
            throw new SQLException("DATABASE_CONNECTION_FAILED", failure.getSQLState());
        }
    }

    @Override public void checkConnection() throws SQLException {
        try (var connection = openInspection()) { }
    }

    Connection openInspection() throws SQLException {
        var probe = new Properties();
        probe.putAll(properties);
        // URL 参数已经解析，不能覆盖本次探测的有限等待。
        for (String timeout : Set.of("connectTimeout", "socketTimeout")) {
            try {
                int configured = Integer.parseInt(probe.getProperty(timeout, "10"));
                if (configured < 0) throw new NumberFormatException();
                probe.setProperty(timeout, Integer.toString(configured == 0 ? 10 : Math.min(configured, 10)));
            } catch (NumberFormatException failure) {
                throw invalid();
            }
        }
        probe.setProperty("loginTimeout", "10");
        return connect(probe, true);
    }

    @Override public void requireConnectionTarget(Connection connection) throws SQLException {
        Properties actual = Driver.parseURL(connection.getMetaData().getURL(), new Properties());
        boolean matches = "PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())
                && actual != null && properties.getProperty("currentSchema").equals(connection.getSchema());
        for (String key : Set.of("PGHOST", "PGPORT", "PGDBNAME")) {
            matches &= actual != null && Objects.equals(properties.getProperty(key), actual.getProperty(key));
        }
        if (!matches) throw new SQLException("DATABASE_TARGET_MISMATCH", "08001");
    }

    static void validate(Connection connection) throws SQLException {
        if (!"PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())
                || !SUPPORTED_MAJORS.contains(connection.getMetaData().getDatabaseMajorVersion()) || connection.getSchema() == null) {
            throw new SQLException("DATABASE_PRODUCT_UNSUPPORTED", "0A000");
        }
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SHOW server_encoding")) {
            if (!rows.next() || !"UTF8".equals(rows.getString(1))) {
                throw new SQLException("DATABASE_ENCODING_UNSUPPORTED", "0A000");
            }
        }
    }

    @Override public void prepareTransaction(Connection connection, boolean readOnly) throws SQLException {
        requireConnectionTarget(connection);
    }

    private static SQLException invalid() { return new SQLException("DATABASE_CONFIGURATION_INVALID", "08001"); }
    @Override public String toString() { return "postgresql"; }
}

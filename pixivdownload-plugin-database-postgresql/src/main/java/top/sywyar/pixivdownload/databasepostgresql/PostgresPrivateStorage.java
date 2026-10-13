package top.sywyar.pixivdownload.databasepostgresql;

import top.sywyar.pixivdownload.plugin.api.database.DatabaseConnectionTarget;
import top.sywyar.pixivdownload.plugin.api.database.DatabasePrivateStorageSettings;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.Optional;

/** 私有 schema 使用独立受限登录角色；管理连接从不交给业务插件。 */
final class PostgresPrivateStorage implements DatabaseConnectionTarget {
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
    private final PostgresConnectionTarget administration;
    private final PostgresConnectionTarget privateTarget;
    private final String role;
    private final String identity;
    private final String password;
    private volatile boolean prepared;

    PostgresPrivateStorage(PostgresConnectionTarget administration, DatabasePrivateStorageSettings settings) {
        this.administration = administration;
        var properties = administration.connectionProperties();
        identity = "pixivdownload/private/" + settings.installationId() + "/" + settings.owner()
                + "/" + properties.getProperty("PGDBNAME") + "/" + properties.getProperty("currentSchema");
        try {
            role = "pixiv_owner_" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8)), 0, 16);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA256_UNAVAILABLE");
        }
        password = settings.password();
        privateTarget = administration.forOwner(role, password, role);
    }

    @Override public String description() { return privateTarget.description(); }
    String schemaName() { return role; }
    /** 预检只查询管理目录，不创建角色或 schema，也不保存试探密码。 */
    boolean inspectMigrationTarget() throws SQLException {
        try (var connection = administration.openInspection()) {
            if (roleExists(connection)) {
                verifyIsolation(connection);
                return PostgresMigration.empty(connection, role);
            }
            try (var statement = connection.prepareStatement("""
                    SELECT (rolsuper OR rolcreaterole) AND has_database_privilege(current_database(),'CREATE')
                      AND NOT EXISTS(SELECT 1 FROM pg_namespace WHERE nspname=?)
                    FROM pg_roles WHERE rolname=current_user
                    """)) {
                statement.setString(1, role);
                try (var rows = statement.executeQuery()) {
                    if (!rows.next() || !rows.getBoolean(1)) throw denied("42501");
                }
            }
            return true;
        }
    }
    Connection openInspection() throws SQLException {
        if (password.isBlank()) throw denied("28000");
        return privateTarget.openInspection();
    }
    @Override public Optional<Path> localFile() { return Optional.empty(); }

    @Override public Connection openConnection(OpenMode mode) throws SQLException {
        if (password.isBlank()) throw denied("28000");
        if (mode == OpenMode.READ_WRITE && !prepared) prepare();
        return privateTarget.openConnection(mode);
    }

    private synchronized void prepare() throws SQLException {
        if (prepared) return;
        try (var connection = administration.openConnection(OpenMode.READ_WRITE)) {
            connection.setAutoCommit(false);
            try {
                try (var lock = connection.prepareStatement("SELECT pg_try_advisory_xact_lock(hashtextextended(?, 0))")) {
                    lock.setString(1, identity);
                    try (var rows = lock.executeQuery()) {
                        if (!rows.next() || !rows.getBoolean(1)) throw denied("55P03");
                    }
                }
                if (!roleExists(connection)) {
                    try (var statement = connection.createStatement()) {
                        statement.execute("CREATE ROLE " + role + " LOGIN NOINHERIT NOSUPERUSER NOCREATEDB"
                                + " NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD " + literal(password));
                        statement.execute("GRANT " + role + " TO CURRENT_USER WITH SET TRUE");
                        statement.execute("CREATE SCHEMA " + role + " AUTHORIZATION " + role);
                        statement.execute("REVOKE ALL ON SCHEMA " + role + " FROM PUBLIC");
                        statement.execute("COMMENT ON SCHEMA " + role + " IS " + literal(identity));
                    }
                }
                verifyIsolation(connection);
                connection.commit();
                prepared = true;
            } catch (SQLException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (SQLException failure) {
            throw denied(failure.getSQLState());
        }
    }

    private boolean roleExists(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT 1 FROM pg_roles WHERE rolname=?")) {
            statement.setString(1, role);
            try (var rows = statement.executeQuery()) { return rows.next(); }
        }
    }

    private void verifyIsolation(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT r.rolcanlogin AND NOT (r.rolsuper OR r.rolcreatedb OR r.rolcreaterole
                       OR r.rolreplication OR r.rolbypassrls),
                       n.nspowner=r.oid, obj_description(n.oid, 'pg_namespace'),
                       NOT EXISTS(SELECT 1 FROM pg_auth_members m WHERE m.member=r.oid)
                FROM pg_roles r JOIN pg_namespace n ON n.nspname=r.rolname WHERE r.rolname=?
                """)) {
            statement.setString(1, role);
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || !rows.getBoolean(1) || !rows.getBoolean(2)
                        || !identity.equals(rows.getString(3)) || !rows.getBoolean(4)) throw denied("42501");
            }
        }
        // PUBLIC 的额外授权也会流入独立角色，不能只检查显式 GRANT。
        try (var statement = connection.prepareStatement("""
                SELECT has_database_privilege(?, current_database(), 'CREATE') OR EXISTS(
                    SELECT 1 FROM pg_namespace n
                    WHERE n.nspname<>? AND left(n.nspname, 3)<>'pg_' AND n.nspname<>'information_schema'
                      AND has_schema_privilege(?, n.oid, 'CREATE')
                ) OR EXISTS(
                    SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                    WHERE n.nspname<>? AND left(n.nspname, 3)<>'pg_' AND n.nspname<>'information_schema'
                      AND CASE WHEN c.relkind IN ('r','p','v','m','f') THEN
                          has_table_privilege(?, c.oid, 'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')
                          OR has_any_column_privilege(?, c.oid, 'SELECT,INSERT,UPDATE,REFERENCES')
                          ELSE FALSE END
                ) OR EXISTS(
                    SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                    WHERE n.nspname<>? AND left(n.nspname, 3)<>'pg_' AND n.nspname<>'information_schema'
                      AND CASE WHEN c.relkind='S' THEN has_sequence_privilege(?, c.oid, 'SELECT,UPDATE,USAGE')
                          ELSE FALSE END
                ) OR EXISTS(
                    SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
                    WHERE n.nspname<>? AND left(n.nspname, 3)<>'pg_' AND n.nspname<>'information_schema'
                      AND p.prosecdef AND has_function_privilege(?, p.oid, 'EXECUTE')
                )
                """)) {
            for (int index = 1; index <= 10; index++) statement.setString(index, role);
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || rows.getBoolean(1)) throw denied("42501");
            }
        }
    }

    @Override public void checkConnection() throws SQLException { privateTarget.checkConnection(); }
    @Override public void requireConnectionTarget(Connection connection) throws SQLException {
        privateTarget.requireConnectionTarget(connection);
        if (!role.equals(connection.getMetaData().getUserName())) throw denied("42501");
    }
    @Override public void prepareTransaction(Connection connection, boolean readOnly) throws SQLException {
        requireConnectionTarget(connection);
    }
    @Override public DatabaseConnectionTarget privateStorage(DatabasePrivateStorageSettings settings) throws SQLException {
        throw denied("42501");
    }
    private static String literal(String value) { return "'" + value.replace("'", "''") + "'"; }
    private static SQLException denied(String state) { return new SQLException("DATABASE_PRIVATE_STORAGE_UNAVAILABLE", state); }
    @Override public String toString() { return "postgresql-private"; }
}

package top.sywyar.pixivdownload.core.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.datasource.AbstractDataSource;
import top.sywyar.pixivdownload.plugin.api.database.DatabaseConnectionSettings;
import top.sywyar.pixivdownload.plugin.api.database.DatabaseConnectionTarget;
import top.sywyar.pixivdownload.plugin.api.database.DatabasePrivateStorageSettings;
import top.sywyar.pixivdownload.plugin.runtime.bootstrap.database.DatabaseProviderBinding;
import top.sywyar.pixivdownload.plugin.runtime.bootstrap.database.DatabaseProviderSelection;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;

/** 宿主只持有已验证 provider 的固定目标，连接和身份核验由该 provider 完整拥有。 */
public final class DatabaseTarget {
    private final DatabaseProviderBinding binding;

    private DatabaseTarget(DatabaseProviderBinding binding) {
        this.binding = Objects.requireNonNull(binding);
    }

    public static DatabaseTarget fromBinding(DatabaseProviderBinding binding) { return new DatabaseTarget(binding); }

    public static DatabaseTarget unavailable() {
        return fromBinding(DatabaseProviderBinding.unavailable(new DatabaseProviderSelection.Result(
                "", "", Optional.empty(), DatabaseProviderSelection.Failure.NOT_SELECTED)));
    }

    /** 草稿与显式停机工具不绑定运行目标，也不保存配置或搬迁旧文件。 */
    public static DatabaseTarget inspect(DatabaseProviderSelection.Result selected, DatabaseConnectionSettings settings) {
        var target = fromBinding(DatabaseProviderBinding.resolve(selected, () -> settings, UnaryOperator.identity()));
        if (!target.available()) throw new IllegalArgumentException("DATABASE_CONFIGURATION_INVALID");
        return target;
    }

    /** 与后端共用 Boot 配置及命令行、系统属性、环境变量优先级。 */
    public static StandardEnvironment configurationEnvironment(String... args) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new SimpleCommandLinePropertySource(args));
        ConfigurationPropertySources.attach(environment);
        ConfigDataEnvironmentPostProcessor.applyTo(environment);
        return environment;
    }

    public boolean available() { return binding.available(); }
    public void invalidate() { binding.invalidate(); }
    public String providerOwner() { return binding.selection().owner(); }
    public String databaseId() { return binding.selection().databaseId(); }
    public Optional<Path> localFile() { return binding.localFile(); }
    public top.sywyar.pixivdownload.migration.database.MigrationEndpoint migrationEndpoint() throws SQLException {
        return new top.sywyar.pixivdownload.migration.database.MigrationEndpoint(providerOwner(),
                binding.selection().provider().orElseThrow(), endpoint());
    }
    /** 文件工具沿用当前 provider，不隐式加载其它引擎。 */
    public DatabaseTarget withLocalFile(Path file) {
        if (!available() || binding.localFile().isEmpty()) throw new IllegalStateException("DATABASE_UNAVAILABLE");
        try {
            return inspect(binding.selection(), binding.selection().provider().orElseThrow()
                    .localFileSettings(binding.settings().orElseThrow(), file));
        } catch (SQLException failure) { throw new IllegalArgumentException("DATABASE_LOCAL_FILE_UNSUPPORTED"); }
    }

    public String displayName() {
        if (!available()) return "DATABASE_UNAVAILABLE";
        return binding.localFile().map(Path::toString).orElse(binding.selection().databaseId());
    }

    public String description() {
        return available() ? binding.target().orElseThrow().description() : "DATABASE_UNAVAILABLE";
    }

    /** 包含认证与 TLS 配置，但不向展示面返回秘密值。 */
    public boolean sameConfiguration(DatabaseTarget other) {
        if (!available() || other == null || !other.available()) return false;
        var left = binding.settings().orElseThrow();
        var right = other.binding.settings().orElseThrow();
        return providerOwner().equals(other.providerOwner())
                && binding.selection().databaseId().equals(other.binding.selection().databaseId())
                && binding.localFile().equals(other.binding.localFile()) && left.jdbcUrl().equals(right.jdbcUrl())
                && left.username().equals(right.username()) && left.password().equals(right.password());
    }

    public DatabaseConnectionTarget privateStorage(DatabasePrivateStorageSettings settings) throws SQLException {
        return endpoint().privateStorage(settings);
    }

    public void checkConnection() throws SQLException { endpoint().checkConnection(); }
    public void optimize(Connection connection) throws SQLException { endpoint().optimize(connection); }
    public DatabaseConnectionTarget.SchemaApplyResult applySchema(Connection connection,
            top.sywyar.pixivdownload.plugin.api.database.migration.MigrationSchema schema, boolean strict) throws SQLException {
        return endpoint().applySchema(connection, schema, strict);
    }
    public top.sywyar.pixivdownload.plugin.api.database.migration.jdbc.PhysicalDatabaseSchema.DatabaseSchema physicalSchema(
            top.sywyar.pixivdownload.plugin.api.database.migration.jdbc.PhysicalDatabaseSchema.DatabaseSchema schema) throws SQLException {
        return endpoint().physicalSchema(schema);
    }
    public java.util.Map<String, top.sywyar.pixivdownload.plugin.api.database.migration.jdbc.PhysicalDatabaseSchema.TableSpec>
            readSchema(Connection connection) throws SQLException {
        return endpoint().readSchema(connection);
    }
    public top.sywyar.pixivdownload.plugin.api.storage.relational.DatabaseSession storageSession(
            Connection connection, top.sywyar.pixivdownload.plugin.api.database.migration.MigrationSchema schema) throws SQLException {
        return endpoint().storageSession(connection, schema);
    }
    public void requireConnectionTarget(Connection connection) throws SQLException { endpoint().requireConnectionTarget(connection); }
    public Connection openConnection() throws SQLException { return openConnection(DatabaseConnectionTarget.OpenMode.READ_WRITE); }
    public Connection openConnection(DatabaseConnectionTarget.OpenMode mode) throws SQLException { return endpoint().openConnection(mode); }
    public void prepareTransaction(Connection connection, boolean readOnly) throws SQLException {
        top.sywyar.pixivdownload.core.db.provider.DatabaseConnectionLease.prepareTransaction(connection, endpoint(), readOnly);
    }

    public HikariDataSource openPool(int maximumPoolSize) throws IOException {
        try (Connection ignored = openConnection()) {
            HikariConfig config = new HikariConfig();
            config.setPoolName("pixiv-database");
            config.setDataSource(new AbstractDataSource() {
                @Override public Connection getConnection() throws SQLException { return openConnection(); }
                @Override public Connection getConnection(String user, String secret) throws SQLException {
                    throw new SQLException("DATABASE_CREDENTIAL_OVERRIDE_DENIED", "08004");
                }
            });
            config.setMaximumPoolSize(maximumPoolSize);
            config.setMinimumIdle(1);
            return new HikariDataSource(config);
        } catch (VirtualMachineError | ThreadDeath fatal) {
            throw fatal;
        } catch (Throwable failure) {
            throw new IOException("DATABASE_CONNECTION_FAILED");
        }
    }

    private DatabaseConnectionTarget endpoint() throws SQLException {
        if (!available()) throw new SQLNonTransientConnectionException("DATABASE_UNAVAILABLE", "08003");
        return binding.target().orElseThrow();
    }

    @Override public String toString() { return available() ? binding.selection().databaseId() : "DATABASE_UNAVAILABLE"; }
}

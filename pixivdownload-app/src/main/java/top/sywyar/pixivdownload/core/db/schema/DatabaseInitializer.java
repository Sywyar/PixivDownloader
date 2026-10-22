package top.sywyar.pixivdownload.core.db.schema;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import top.sywyar.pixivdownload.core.db.DatabaseTarget;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.plugin.api.schema.SchemaContribution;
import java.util.*;

/** 宿主编排结构事务和就绪事件；DDL、类型映射及约束核验均由当前提供者执行。 */
@Slf4j
@RequiredArgsConstructor
public class DatabaseInitializer {
    private final DatabaseTarget target;
    private final JdbcTemplate jdbcTemplate;
    private final List<SchemaContribution> contributions;
    private final ManagedDatabaseSchema.DatabaseSchema mergedSchema;
    private final AppMessages messages;
    private final ApplicationEventPublisher eventPublisher;

    public static void prepareOffline(java.sql.Connection connection, DatabaseTarget target) throws java.sql.SQLException {
        var registry = top.sywyar.pixivdownload.plugin.registry.schema.DatabaseSchemaRegistry.forBuiltInPlugins();
        try (var transaction = target.beginToolTransaction(connection)) {
            target.applySchema(connection, CoreMigrationSchema.create(registry, true), true);
            transaction.commit();
        }
    }

    public static DatabaseInitializer offline(java.sql.Connection connection, DatabaseTarget target,
            List<SchemaContribution> contributions, ManagedDatabaseSchema.DatabaseSchema schema) {
        var source = new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true);
        var bundle = new org.springframework.context.support.ResourceBundleMessageSource();
        bundle.setBasename("i18n/messages");
        bundle.setDefaultEncoding("UTF-8");
        return new DatabaseInitializer(target, new JdbcTemplate(source), contributions, schema,
                new AppMessages(bundle), event -> {});
    }

    @PostConstruct
    public void initialize() {
        if (isUnavailable()) return;
        var transaction = new TransactionTemplate(
                new DataSourceTransactionManager(Objects.requireNonNull(jdbcTemplate.getDataSource())));
        transaction.executeWithoutResult(status -> {
            jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
                target.prepareTransaction(connection, false);
                return null;
            });
            applySchema(contributions, mergedSchema, mergedSchema.tables().keySet(), true);
            var comparison = jdbcTemplate.execute(
                    (ConnectionCallback<DatabaseSchemaInspector.SchemaComparison>) connection ->
                            DatabaseSchemaInspector.compare(connection, target, mergedSchema));
            if (comparison == null || !comparison.matches()) {
                throw new IllegalStateException(messages.getForLog("core.db.schema-mismatch",
                        comparison == null ? "" : comparison.summary(12)));
            }
        });
        eventPublisher.publishEvent(new DatabaseReadyEvent());
    }

    /** 不可用不等于空库，缺席时不发布就绪事件。 */
    public boolean isUnavailable() {
        return !target.available() || top.sywyar.pixivdownload.core.db.UnavailableDataSource.isUnavailable(jdbcTemplate.getDataSource());
    }

    /** 启动修复工具可安全补齐现存结构，并从结果和后续检查观察未能补齐的列。 */
    public synchronized SchemaApplyResult applySchema(List<SchemaContribution> contributions,
            ManagedDatabaseSchema.DatabaseSchema schema) {
        return applySchema(contributions, schema, Set.of(), false);
    }

    /** 运行期 owner 声明仅在同一事务核验成功后才可发布 registry 快照。 */
    public synchronized SchemaApplyResult applyRuntimeOwnerSchema(List<SchemaContribution> contributions,
            ManagedDatabaseSchema.DatabaseSchema schema, Set<String> ownerTableNames) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("runtime owner schema must be applied inside an active transaction");
        }
        return applySchema(contributions, schema, ownerTableNames, true);
    }

    private SchemaApplyResult applySchema(List<SchemaContribution> contributions,
            ManagedDatabaseSchema.DatabaseSchema schema, Set<String> ownerTableNames, boolean strict) {
        Objects.requireNonNull(contributions);
        Objects.requireNonNull(schema);
        Objects.requireNonNull(ownerTableNames);
        var declaration = CoreMigrationSchema.create(contributions, schema, false);
        var result = jdbcTemplate.execute((ConnectionCallback<top.sywyar.pixivdownload.plugin.api.database.DatabaseConnectionTarget.SchemaApplyResult>) connection -> {
            try {
                return target.applySchema(connection, declaration, strict);
            } catch (java.sql.SQLException failure) {
                if ("0A000".equals(failure.getSQLState())) {
                    throw new IllegalStateException(messages.getForLog("core.db.schema-mismatch", failure.getMessage()));
                }
                throw failure;
            }
        });
        Objects.requireNonNull(result);
        for (String skipped : result.skippedColumns()) {
            String[] parts = skipped.split("\\.", 2);
            log.warn(messages.getForLog("core.db.log.add-column-skipped", parts[0], parts[1]));
        }
        if (strict) {
            var comparison = jdbcTemplate.execute((ConnectionCallback<DatabaseSchemaInspector.SchemaComparison>) connection ->
                    DatabaseSchemaInspector.compareTables(connection, target, schema, ownerTableNames));
            if (comparison == null || !comparison.matches()) {
                throw new IllegalStateException("runtime owner schema does not match the physical database:\n"
                        + (comparison == null ? "schema comparison returned no result" : comparison.summary(12)));
            }
        }
        log.info(messages.getForLog("core.db.log.schema-initialized", schema.tables().size(), result.createdTables(),
                result.addedColumns(), result.createdIndexes()));
        return new SchemaApplyResult(result.createdTables(), result.addedColumns(), result.createdIndexes());
    }

    public record SchemaApplyResult(int createdTables, int addedColumns, int createdIndexes) {}
}

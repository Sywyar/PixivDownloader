package top.sywyar.pixivdownload.core.db.schema;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.plugin.api.schema.SchemaContribution;
import top.sywyar.pixivdownload.plugin.api.schema.TableSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 受管 schema 的唯一 DDL 执行入口：按 contribution 声明依次执行
 * 建表（{@code CREATE TABLE IF NOT EXISTS}）→ 安全补列（旧库缺列的幂等
 * {@code ALTER TABLE ADD COLUMN}，含跨 contribution 的 {@code columnMigrations}，
 * 已折叠进合并结果）→ 索引（{@code CREATE INDEX IF NOT EXISTS}），
 * 完成后发布 {@link DatabaseReadyEvent}。
 * <p>
 * 建表 DDL 从 contribution 的原始规格（plugin.api 类型）渲染——{@code AUTOINCREMENT}
 * 与表级 {@code CHECK} 只存在于原始规格、PRAGMA 不可见，schema 检查比对仍走合并后的
 * 归一化模型（{@link ManagedDatabaseSchema}）。补列与索引从合并结果驱动（标识符已归一化、
 * 补列规则已并入），保证与启动校验看到的是同一份事实。
 * <p>
 * 依赖表结构就绪的业务初始化必须排在本类之后：bean 经构造器注入本类表达顺序依赖，
 * 或监听 {@link DatabaseReadyEvent}（注意事件发布于单例实例化早期，监听方须为
 * {@code ApplicationListener} bean，{@code @EventListener} 注解方法注册不及）。
 */
@Slf4j
@RequiredArgsConstructor
public class DatabaseInitializer {

    private final JdbcTemplate jdbcTemplate;
    private final List<SchemaContribution> contributions;
    private final ManagedDatabaseSchema.DatabaseSchema mergedSchema;
    private final AppMessages messages;
    private final ApplicationEventPublisher eventPublisher;

    @PostConstruct
    public void initialize() {
        applySchema(contributions, mergedSchema);
        eventPublisher.publishEvent(new DatabaseReadyEvent());
    }

    /**
     * 幂等应用一份完整受管 schema 快照。启动期和运行期插件 schema 新代共用同一 DDL 路径；
     * 运行期调用方必须在事务内执行，并只在事务成功后发布对应 registry 快照。
     */
    public synchronized SchemaApplyResult applySchema(
            List<SchemaContribution> schemaContributions,
            ManagedDatabaseSchema.DatabaseSchema schema) {
        return applySchema(schemaContributions, schema, Set.of(), false);
    }

    /**
     * 在调用方已开启的事务内应用并精确验证一个 owner 的累计 schema ledger。
     * 验证复用 Spring 事务绑定的同一连接；任何不可安全补列或磁盘结构漂移都会抛错，使全部 DDL 回滚。
     */
    public synchronized SchemaApplyResult applyRuntimeOwnerSchema(
            List<SchemaContribution> ownerContributions,
            ManagedDatabaseSchema.DatabaseSchema ownerSchema,
            Set<String> ownerTableNames) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("runtime owner schema must be applied inside an active transaction");
        }
        return applySchema(ownerContributions, ownerSchema, ownerTableNames, true);
    }

    private SchemaApplyResult applySchema(
            List<SchemaContribution> schemaContributions,
            ManagedDatabaseSchema.DatabaseSchema schema,
            Set<String> ownerTableNames,
            boolean strictRuntimeValidation) {
        Objects.requireNonNull(schemaContributions, "schema contributions");
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(ownerTableNames, "owner table names");
        Set<String> existingTables = SqliteSchema.existingTableNames(jdbcTemplate);
        int createdTables = 0;
        for (SchemaContribution contribution : schemaContributions) {
            for (TableSpec table : contribution.tables()) {
                String normalizedName = ManagedDatabaseSchema.normalizeIdentifier(table.name());
                jdbcTemplate.execute(SqliteSchema.renderCreateTable(table));
                if (existingTables.add(normalizedName)) {
                    createdTables++;
                }
            }
        }
        ColumnApplyResult columnResult = addMissingColumns(schema);
        if (strictRuntimeValidation && !columnResult.skippedColumns().isEmpty()) {
            throw new IllegalStateException("runtime owner schema contains missing columns that SQLite cannot "
                    + "append safely: " + String.join(", ", columnResult.skippedColumns()));
        }
        int createdIndexes = createIndexes(schema);
        if (strictRuntimeValidation) {
            DatabaseSchemaInspector.SchemaComparison comparison = jdbcTemplate.execute(
                    (ConnectionCallback<DatabaseSchemaInspector.SchemaComparison>) connection ->
                            DatabaseSchemaInspector.compareTables(connection, schema, ownerTableNames));
            if (comparison == null || !comparison.matches()) {
                String summary = comparison == null ? "schema comparison returned no result" : comparison.summary(12);
                throw new IllegalStateException("runtime owner schema does not match the physical database:\n"
                        + summary);
            }
        }
        log.info(messages.getForLog("core.db.log.schema-initialized",
                schema.tables().size(), createdTables, columnResult.addedColumns(), createdIndexes));
        return new SchemaApplyResult(createdTables, columnResult.addedColumns(), createdIndexes);
    }

    /** 旧库安全补列：受管列在磁盘上缺失且可安全追加（非主键、非「NOT NULL 无默认值」）时 ALTER 补齐。 */
    private ColumnApplyResult addMissingColumns(ManagedDatabaseSchema.DatabaseSchema schema) {
        int added = 0;
        List<String> skipped = new ArrayList<>();
        for (ManagedDatabaseSchema.TableSpec table : schema.tables().values()) {
            Set<String> actualColumns = SqliteSchema.existingColumnNames(jdbcTemplate, table.name());
            for (ManagedDatabaseSchema.ColumnSpec column : table.columns()) {
                if (actualColumns.contains(column.name())) {
                    continue;
                }
                if (!SqliteSchema.canAddColumn(column)) {
                    // SQLite 的 ADD COLUMN 加不了主键列；NOT NULL 无默认值时旧行无值也会失败。
                    // 留给启动校验报漂移并提示人工迁移，不在这里带病硬加。
                    log.warn(messages.getForLog("core.db.log.add-column-skipped",
                            table.name(), column.name()));
                    skipped.add(table.name() + "." + column.name());
                    continue;
                }
                jdbcTemplate.execute(SqliteSchema.renderAddColumn(table.name(), column));
                added++;
            }
        }
        return new ColumnApplyResult(added, skipped);
    }

    /** 显式索引幂等创建；UNIQUE 约束的自动索引随建表语句生成，旧表无法追加、不在此处理。 */
    private int createIndexes(ManagedDatabaseSchema.DatabaseSchema schema) {
        Set<String> existingIndexes = SqliteSchema.existingIndexNames(jdbcTemplate);
        int created = 0;
        for (ManagedDatabaseSchema.TableSpec table : schema.tables().values()) {
            for (ManagedDatabaseSchema.IndexSpec index : table.indexes()) {
                if (index.origin() != ManagedDatabaseSchema.IndexOrigin.CREATE_INDEX) {
                    continue;
                }
                jdbcTemplate.execute(SqliteSchema.renderCreateIndex(table.name(), index));
                if (existingIndexes.add(index.name())) {
                    created++;
                }
            }
        }
        return created;
    }

    /** 本次幂等 schema 应用实际创建的对象数量。 */
    public record SchemaApplyResult(int createdTables, int addedColumns, int createdIndexes) {
    }

    private record ColumnApplyResult(int addedColumns, List<String> skippedColumns) {
        private ColumnApplyResult {
            skippedColumns = List.copyOf(skippedColumns);
        }
    }
}

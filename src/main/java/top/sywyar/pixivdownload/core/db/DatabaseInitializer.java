package top.sywyar.pixivdownload.core.db;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.plugin.api.ColumnSpec;
import top.sywyar.pixivdownload.plugin.api.IndexOrigin;
import top.sywyar.pixivdownload.plugin.api.SchemaContribution;
import top.sywyar.pixivdownload.plugin.api.TableSpec;
import top.sywyar.pixivdownload.plugin.api.event.DatabaseReadyEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
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
        Set<String> existingTables = existingTableNames();
        int createdTables = 0;
        for (SchemaContribution contribution : contributions) {
            for (TableSpec table : contribution.tables()) {
                String normalizedName = ManagedDatabaseSchema.normalizeIdentifier(table.name());
                jdbcTemplate.execute(renderCreateTable(table));
                if (existingTables.add(normalizedName)) {
                    createdTables++;
                }
            }
        }
        int addedColumns = addMissingColumns();
        int createdIndexes = createIndexes();
        log.info(messages.getForLog("core.db.log.schema-initialized",
                mergedSchema.tables().size(), createdTables, addedColumns, createdIndexes));
        eventPublisher.publishEvent(new DatabaseReadyEvent());
    }

    private Set<String> existingTableNames() {
        List<String> names = jdbcTemplate.queryForList(
                "SELECT name FROM sqlite_master WHERE type = 'table'", String.class);
        Set<String> normalized = new HashSet<>();
        for (String name : names) {
            normalized.add(ManagedDatabaseSchema.normalizeIdentifier(name));
        }
        return normalized;
    }

    /** 旧库安全补列：受管列在磁盘上缺失且可安全追加（非主键、非「NOT NULL 无默认值」）时 ALTER 补齐。 */
    private int addMissingColumns() {
        int added = 0;
        for (ManagedDatabaseSchema.TableSpec table : mergedSchema.tables().values()) {
            Set<String> actualColumns = new HashSet<>(jdbcTemplate.queryForList(
                    "SELECT lower(name) FROM pragma_table_info(?)", String.class, table.name()));
            for (ManagedDatabaseSchema.ColumnSpec column : table.columns()) {
                if (actualColumns.contains(column.name())) {
                    continue;
                }
                if (column.primaryKeyPosition() > 0
                        || (column.notNull() && column.defaultValue() == null)) {
                    // SQLite 的 ADD COLUMN 加不了主键列；NOT NULL 无默认值时旧行无值也会失败。
                    // 留给启动校验报漂移并提示人工迁移，不在这里带病硬加。
                    log.warn(messages.getForLog("core.db.log.add-column-skipped",
                            table.name(), column.name()));
                    continue;
                }
                StringBuilder ddl = new StringBuilder("ALTER TABLE ")
                        .append(quote(table.name()))
                        .append(" ADD COLUMN ")
                        .append(quote(column.name()))
                        .append(' ')
                        .append(column.type());
                if (column.notNull()) {
                    ddl.append(" NOT NULL");
                }
                if (column.defaultValue() != null) {
                    ddl.append(" DEFAULT ").append(column.defaultValue());
                }
                jdbcTemplate.execute(ddl.toString());
                added++;
            }
        }
        return added;
    }

    /** 显式索引幂等创建；UNIQUE 约束的自动索引随建表语句生成，旧表无法追加、不在此处理。 */
    private int createIndexes() {
        Set<String> existingIndexes = new HashSet<>(jdbcTemplate.queryForList(
                "SELECT lower(name) FROM sqlite_master WHERE type = 'index'", String.class));
        int created = 0;
        for (ManagedDatabaseSchema.TableSpec table : mergedSchema.tables().values()) {
            for (ManagedDatabaseSchema.IndexSpec index : table.indexes()) {
                if (index.origin() != ManagedDatabaseSchema.IndexOrigin.CREATE_INDEX) {
                    continue;
                }
                StringBuilder ddl = new StringBuilder("CREATE ");
                if (index.unique()) {
                    ddl.append("UNIQUE ");
                }
                ddl.append("INDEX IF NOT EXISTS ")
                        .append(quote(index.name()))
                        .append(" ON ")
                        .append(quote(table.name()))
                        .append(" (")
                        .append(String.join(", ", index.columns().stream().map(DatabaseInitializer::quote).toList()))
                        .append(')');
                jdbcTemplate.execute(ddl.toString());
                if (existingIndexes.add(index.name())) {
                    created++;
                }
            }
        }
        return created;
    }

    private static String renderCreateTable(TableSpec table) {
        List<ColumnSpec> primaryKeyColumns = table.columns().stream()
                .filter(column -> column.primaryKeyPosition() > 0)
                .sorted(Comparator.comparingInt(ColumnSpec::primaryKeyPosition))
                .toList();
        boolean inlinePrimaryKey = primaryKeyColumns.size() == 1;
        List<String> parts = new ArrayList<>();
        for (ColumnSpec column : table.columns()) {
            StringBuilder part = new StringBuilder(quote(column.name()))
                    .append(' ')
                    .append(column.type());
            if (column.notNull()) {
                part.append(" NOT NULL");
            }
            if (column.defaultValue() != null) {
                part.append(" DEFAULT ").append(column.defaultValue());
            }
            if (inlinePrimaryKey && column.primaryKeyPosition() == 1) {
                part.append(" PRIMARY KEY");
                if (column.autoIncrement()) {
                    part.append(" AUTOINCREMENT");
                }
            }
            parts.add(part.toString());
        }
        if (!inlinePrimaryKey && !primaryKeyColumns.isEmpty()) {
            parts.add("PRIMARY KEY ("
                    + String.join(", ", primaryKeyColumns.stream()
                            .map(column -> quote(column.name())).toList())
                    + ')');
        }
        for (var index : table.indexes()) {
            if (index.origin() == IndexOrigin.UNIQUE_CONSTRAINT) {
                parts.add("UNIQUE ("
                        + String.join(", ", index.columns().stream()
                                .map(DatabaseInitializer::quote).toList())
                        + ')');
            }
        }
        if (table.checkExpression() != null && !table.checkExpression().isBlank()) {
            parts.add("CHECK (" + table.checkExpression() + ')');
        }
        return "CREATE TABLE IF NOT EXISTS " + quote(table.name())
                + " (" + String.join(", ", parts) + ')';
    }

    private static String quote(String identifier) {
        return '"' + identifier.trim() + '"';
    }
}

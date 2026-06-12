package top.sywyar.pixivdownload.core.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.plugin.DatabaseSchemaRegistry;
import top.sywyar.pixivdownload.plugin.PluginRegistry;
import top.sywyar.pixivdownload.plugin.api.SchemaContribution;
import top.sywyar.pixivdownload.plugin.api.TableSpec;
import top.sywyar.pixivdownload.plugin.api.event.DatabaseReadyEvent;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static top.sywyar.pixivdownload.core.db.SchemaSpecs.column;

@DisplayName("DatabaseInitializer 统一建表 / 补列 / 索引")
class DatabaseInitializerTest {

    /** 受管 schema 的期望形态：内置插件 contribution 经 DatabaseSchemaRegistry 合并的结果。 */
    private static final DatabaseSchemaRegistry REGISTRY = DatabaseSchemaRegistry.forBuiltInPlugins();

    private SingleConnectionDataSource newDataSource() {
        SingleConnectionDataSource ds = new SingleConnectionDataSource();
        ds.setDriverClassName("org.sqlite.JDBC");
        ds.setUrl("jdbc:sqlite::memory:");
        ds.setSuppressClose(true);
        return ds;
    }

    private DatabaseInitializer newInitializer(SingleConnectionDataSource ds, List<Object> events) {
        return new DatabaseInitializer(new JdbcTemplate(ds),
                REGISTRY.contributions(), REGISTRY.mergedSchema(),
                TestI18nBeans.appMessages(), events::add);
    }

    private DatabaseInitializer newInitializer(SingleConnectionDataSource ds) {
        return newInitializer(ds, new ArrayList<>());
    }

    @Test
    @DisplayName("全新库执行后应与受管 schema 完全匹配")
    void shouldCreateFreshDatabaseMatchingManagedSchema() throws Exception {
        SingleConnectionDataSource ds = newDataSource();
        try {
            newInitializer(ds).initialize();
            try (Connection c = ds.getConnection()) {
                DatabaseSchemaInspector.SchemaComparison comparison =
                        DatabaseSchemaInspector.compare(c, REGISTRY.mergedSchema());
                assertThat(comparison.matches())
                        .as("全新库与受管 schema 的差异：%s", comparison.details())
                        .isTrue();
            }
        } finally {
            ds.destroy();
        }
    }

    @Test
    @DisplayName("双路建库对照：生成 DDL 与旧版逐表 DDL 建出的库结构完全等价")
    void shouldProduceSameSchemaAsLegacyDdl() throws Exception {
        SingleConnectionDataSource legacyDs = newDataSource();
        SingleConnectionDataSource generatedDs = newDataSource();
        try {
            try (Connection c = legacyDs.getConnection(); Statement st = c.createStatement()) {
                for (String statement : LegacyDdlBaseline.STATEMENTS) {
                    st.executeUpdate(statement);
                }
            }
            newInitializer(generatedDs).initialize();

            Map<String, TableShape> legacy;
            Map<String, TableShape> generated;
            try (Connection c = legacyDs.getConnection()) {
                legacy = snapshotSchema(c);
            }
            try (Connection c = generatedDs.getConnection()) {
                generated = snapshotSchema(c);
            }
            assertThat(generated.keySet()).isEqualTo(legacy.keySet());
            for (Map.Entry<String, TableShape> entry : legacy.entrySet()) {
                assertThat(generated.get(entry.getKey()))
                        .as("表 %s 的结构（列序 / 索引 / AUTOINCREMENT / CHECK）应与旧版 DDL 等价", entry.getKey())
                        .isEqualTo(entry.getValue());
            }
        } finally {
            legacyDs.destroy();
            generatedDs.destroy();
        }
    }

    @Test
    @DisplayName("旧库缺列应被安全补齐，且建索引排在补列之后")
    void shouldAddMissingColumnsBeforeIndexes() throws Exception {
        SingleConnectionDataSource ds = newDataSource();
        try {
            // 最初版本的 artworks（无 R18 起的 9 个后续列），idx_artworks_series_order 依赖补列后的 series_id
            try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
                st.executeUpdate("CREATE TABLE artworks ("
                        + "artwork_id INTEGER PRIMARY KEY,"
                        + "title TEXT NOT NULL,"
                        + "folder TEXT NOT NULL,"
                        + "count INTEGER NOT NULL,"
                        + "extensions TEXT NOT NULL,"
                        + "time INTEGER NOT NULL UNIQUE,"
                        + "moved INTEGER DEFAULT 0,"
                        + "move_folder TEXT,"
                        + "move_time INTEGER)");
            }
            newInitializer(ds).initialize();
            try (Connection c = ds.getConnection()) {
                assertThat(columnNames(c, "artworks")).contains(
                        "r18", "is_ai", "author_id", "description", "file_name",
                        "file_author_name_id", "series_id", "series_order", "deleted");
                DatabaseSchemaInspector.SchemaComparison comparison =
                        DatabaseSchemaInspector.compare(c, REGISTRY.mergedSchema());
                assertThat(comparison.matches())
                        .as("旧库补列后与受管 schema 的差异：%s", comparison.details())
                        .isTrue();
            }
        } finally {
            ds.destroy();
        }
    }

    @Test
    @DisplayName("主键列与 NOT NULL 无默认值的缺列不可安全补齐：跳过且不中断启动")
    void shouldSkipUnsafeMissingColumns() throws Exception {
        SingleConnectionDataSource ds = newDataSource();
        try {
            try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
                st.executeUpdate("CREATE TABLE t (id INTEGER PRIMARY KEY)");
            }
            DatabaseSchemaRegistry registry = new DatabaseSchemaRegistry(new PluginRegistry(List.of()));
            registry.register(new SchemaContribution("core", List.of(
                    new TableSpec("t", List.of(
                            column("id", "INTEGER", false, null, 1),
                            column("required", "TEXT", true, null, 0),
                            column("optional", "TEXT", false, null, 0)
                    ), List.of())
            ), List.of(), List.of(), List.of()));
            DatabaseInitializer initializer = new DatabaseInitializer(new JdbcTemplate(ds),
                    registry.contributions(), registry.mergedSchema(),
                    TestI18nBeans.appMessages(), event -> {});

            assertThatCode(initializer::initialize).doesNotThrowAnyException();
            try (Connection c = ds.getConnection()) {
                assertThat(columnNames(c, "t"))
                        .contains("optional")
                        .doesNotContain("required");
            }
        } finally {
            ds.destroy();
        }
    }

    @Test
    @DisplayName("重复执行应幂等且每次都发布 DatabaseReadyEvent")
    void shouldBeIdempotentAndPublishReadyEvent() throws Exception {
        SingleConnectionDataSource ds = newDataSource();
        try {
            List<Object> events = new ArrayList<>();
            DatabaseInitializer initializer = newInitializer(ds, events);
            initializer.initialize();
            assertThatCode(initializer::initialize).doesNotThrowAnyException();
            assertThat(events).hasSize(2).allMatch(DatabaseReadyEvent.class::isInstance);
            try (Connection c = ds.getConnection()) {
                DatabaseSchemaInspector.SchemaComparison comparison =
                        DatabaseSchemaInspector.compare(c, REGISTRY.mergedSchema());
                assertThat(comparison.matches())
                        .as("重复执行后与受管 schema 的差异：%s", comparison.details())
                        .isTrue();
            }
        } finally {
            ds.destroy();
        }
    }

    @Test
    @DisplayName("AUTOINCREMENT 与 CHECK 形态应逐字保真进生成的 DDL")
    void shouldRenderAutoIncrementAndCheckVerbatim() throws Exception {
        SingleConnectionDataSource ds = newDataSource();
        try {
            newInitializer(ds).initialize();
            try (Connection c = ds.getConnection()) {
                assertThat(createTableSql(c, "tags")).contains("AUTOINCREMENT");
                assertThat(createTableSql(c, "statistics")).contains("CHECK (id = 1)");
                // 非自增主键不得被「顺手」加上 AUTOINCREMENT（行为语义不同：禁止 rowid 复用）
                assertThat(createTableSql(c, "artworks")).doesNotContain("AUTOINCREMENT");
            }
        } finally {
            ds.destroy();
        }
    }

    // ── 结构快照助手 ────────────────────────────────────────────────────────────

    /**
     * 单表结构签名：列按声明序（cid 序）的归一化五元组、索引（含 UNIQUE 约束自动索引）的
     * 归一化签名集合，以及 sqlite_master 原文中 PRAGMA 不可见的 AUTOINCREMENT / CHECK 形态位。
     */
    private record TableShape(List<String> columns, List<String> indexes,
                              boolean autoIncrement, boolean hasCheck) {}

    private Map<String, TableShape> snapshotSchema(Connection connection) throws Exception {
        Map<String, String> sqlByTable = new HashMap<>();
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(
                "SELECT name, sql FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'")) {
            while (rs.next()) {
                sqlByTable.put(rs.getString(1).toLowerCase(Locale.ROOT), rs.getString(2));
            }
        }
        Map<String, TableShape> shapes = new TreeMap<>();
        for (Map.Entry<String, String> entry : sqlByTable.entrySet()) {
            String table = entry.getKey();
            String upperSql = entry.getValue().toUpperCase(Locale.ROOT);
            List<String> columns = new ArrayList<>();
            try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(
                    "PRAGMA table_info(\"" + table + "\")")) {
                while (rs.next()) {
                    columns.add(String.join("|",
                            rs.getString("name").toLowerCase(Locale.ROOT),
                            rs.getString("type").toUpperCase(Locale.ROOT),
                            String.valueOf(rs.getInt("notnull")),
                            String.valueOf(ManagedDatabaseSchema.normalizeDefault(rs.getString("dflt_value"))),
                            String.valueOf(rs.getInt("pk"))));
                }
            }
            List<String> indexes = new ArrayList<>();
            try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(
                    "PRAGMA index_list(\"" + table + "\")")) {
                while (rs.next()) {
                    String indexName = rs.getString("name").toLowerCase(Locale.ROOT);
                    StringBuilder signature = new StringBuilder(indexName)
                            .append('|').append(rs.getInt("unique"))
                            .append('|').append(rs.getString("origin"));
                    try (Statement st2 = connection.createStatement(); ResultSet cols = st2.executeQuery(
                            "PRAGMA index_info(\"" + indexName + "\")")) {
                        while (cols.next()) {
                            signature.append('|').append(cols.getString("name").toLowerCase(Locale.ROOT));
                        }
                    }
                    indexes.add(signature.toString());
                }
            }
            indexes.sort(String::compareTo);
            shapes.put(table, new TableShape(columns, indexes,
                    upperSql.contains("AUTOINCREMENT"), upperSql.contains("CHECK")));
        }
        return shapes;
    }

    private List<String> columnNames(Connection connection, String table) throws Exception {
        List<String> names = new ArrayList<>();
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(
                "PRAGMA table_info(\"" + table + "\")")) {
            while (rs.next()) {
                names.add(rs.getString("name").toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }

    private String createTableSql(Connection connection, String table) throws Exception {
        try (var ps = connection.prepareStatement(
                "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : "";
            }
        }
    }
}

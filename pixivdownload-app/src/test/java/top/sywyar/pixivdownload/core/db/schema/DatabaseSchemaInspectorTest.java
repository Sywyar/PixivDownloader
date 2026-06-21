package top.sywyar.pixivdownload.core.db.schema;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.core.db.PixivMapper;
import top.sywyar.pixivdownload.core.db.pathprefix.PathPrefixCodec;
import top.sywyar.pixivdownload.core.db.pathprefix.PathPrefixMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DatabaseSchemaInspector 集成测试")
class DatabaseSchemaInspectorTest {

    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    private void exec(String sql) throws Exception {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate(sql);
        }
    }

    private ManagedDatabaseSchema.DatabaseSchema schemaOf(ManagedDatabaseSchema.TableSpec... specs) {
        java.util.LinkedHashMap<String, ManagedDatabaseSchema.TableSpec> map = new java.util.LinkedHashMap<>();
        for (ManagedDatabaseSchema.TableSpec spec : specs) {
            map.put(spec.name(), spec);
        }
        return new ManagedDatabaseSchema.DatabaseSchema(Map.copyOf(map));
    }

    /** 受管 schema 的期望形态：内置插件 contribution 经 DatabaseSchemaRegistry 合并的结果。 */
    private static final ManagedDatabaseSchema.DatabaseSchema MERGED_SPEC =
            top.sywyar.pixivdownload.plugin.DatabaseSchemaRegistry.forBuiltInPlugins().mergedSchema();

    private ManagedDatabaseSchema.DatabaseSchema specSubset(Set<String> tableNames) {
        java.util.LinkedHashMap<String, ManagedDatabaseSchema.TableSpec> subset = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, ManagedDatabaseSchema.TableSpec> e : MERGED_SPEC.tables().entrySet()) {
            if (tableNames.contains(e.getKey())) {
                subset.put(e.getKey(), e.getValue());
            }
        }
        return new ManagedDatabaseSchema.DatabaseSchema(Map.copyOf(subset));
    }

    private ManagedDatabaseSchema.TableSpec table(String name,
                                                  List<ManagedDatabaseSchema.ColumnSpec> cols,
                                                  List<ManagedDatabaseSchema.IndexSpec> idx) {
        return new ManagedDatabaseSchema.TableSpec(name, cols, idx);
    }

    private ManagedDatabaseSchema.ColumnSpec col(String name, String type, boolean notNull,
                                                 String defaultValue, int pkPosition) {
        return new ManagedDatabaseSchema.ColumnSpec(name, type, notNull, defaultValue, pkPosition);
    }

    @Test
    @DisplayName("on-disk 与期望 schema 完全匹配应返回 matches=true")
    void shouldReportMatchesWhenSchemaIsIdentical() throws Exception {
        exec("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT NOT NULL)");

        ManagedDatabaseSchema.DatabaseSchema expected = schemaOf(table(
                "t",
                List.of(
                        col("id", "INTEGER", false, null, 1),
                        col("name", "TEXT", true, null, 0)
                ),
                List.of()
        ));

        DatabaseSchemaInspector.SchemaComparison result =
                DatabaseSchemaInspector.compare(connection, expected);

        assertThat(result.matches()).isTrue();
        assertThat(result.details()).isEmpty();
    }

    @Nested
    @DisplayName("差异分类")
    class DifferenceTests {

        @Test
        @DisplayName("缺表应产生 MISSING_TABLE")
        void shouldDetectMissingTable() throws Exception {
            ManagedDatabaseSchema.DatabaseSchema expected = schemaOf(table(
                    "t",
                    List.of(col("id", "INTEGER", false, null, 1)),
                    List.of()
            ));

            DatabaseSchemaInspector.SchemaComparison result =
                    DatabaseSchemaInspector.compare(connection, expected);

            assertThat(result.matches()).isFalse();
            assertThat(result.details())
                    .extracting(DatabaseSchemaInspector.SchemaDifference::kind)
                    .containsExactly(DatabaseSchemaInspector.SchemaDifferenceKind.MISSING_TABLE);
        }

        @Test
        @DisplayName("额外表应产生 UNMANAGED_TABLE")
        void shouldDetectUnmanagedTable() throws Exception {
            exec("CREATE TABLE t (id INTEGER PRIMARY KEY)");
            exec("CREATE TABLE extra (id INTEGER PRIMARY KEY)");

            ManagedDatabaseSchema.DatabaseSchema expected = schemaOf(table(
                    "t",
                    List.of(col("id", "INTEGER", false, null, 1)),
                    List.of()
            ));

            DatabaseSchemaInspector.SchemaComparison result =
                    DatabaseSchemaInspector.compare(connection, expected);

            assertThat(result.details())
                    .extracting(DatabaseSchemaInspector.SchemaDifference::kind)
                    .containsExactly(DatabaseSchemaInspector.SchemaDifferenceKind.UNMANAGED_TABLE);
        }

        @Test
        @DisplayName("FTS5 虚拟表及其影子表不应被报为 UNMANAGED_TABLE")
        void shouldIgnoreFts5VirtualAndShadowTables() throws Exception {
            exec("CREATE TABLE t (id INTEGER PRIMARY KEY)");
            // FTS5 虚拟表会附带创建 t_fts_data / t_fts_idx / t_fts_docsize / t_fts_config 等影子表
            exec("CREATE VIRTUAL TABLE t_fts USING fts5(content, tokenize='trigram')");

            ManagedDatabaseSchema.DatabaseSchema expected = schemaOf(table(
                    "t",
                    List.of(col("id", "INTEGER", false, null, 1)),
                    List.of()
            ));

            DatabaseSchemaInspector.SchemaComparison result =
                    DatabaseSchemaInspector.compare(connection, expected);

            assertThat(result.matches())
                    .as("FTS5 内部表不应产生漂移：%s", result.details())
                    .isTrue();
        }

        @Test
        @DisplayName("虚拟表同前缀的普通表仍应产生 UNMANAGED_TABLE")
        void shouldReportNormalTableWithVirtualTablePrefix() throws Exception {
            exec("CREATE TABLE t (id INTEGER PRIMARY KEY)");
            exec("CREATE VIRTUAL TABLE t_fts USING fts5(content, tokenize='trigram')");
            exec("CREATE TABLE t_fts_backup (id INTEGER PRIMARY KEY)");

            ManagedDatabaseSchema.DatabaseSchema expected = schemaOf(table(
                    "t",
                    List.of(col("id", "INTEGER", false, null, 1)),
                    List.of()
            ));

            DatabaseSchemaInspector.SchemaComparison result =
                    DatabaseSchemaInspector.compare(connection, expected);

            assertThat(result.details())
                    .extracting(DatabaseSchemaInspector.SchemaDifference::kind,
                            DatabaseSchemaInspector.SchemaDifference::tableName)
                    .containsExactly(org.assertj.core.api.Assertions.tuple(
                            DatabaseSchemaInspector.SchemaDifferenceKind.UNMANAGED_TABLE, "t_fts_backup"));
        }

        @Test
        @DisplayName("缺列应产生 MISSING_COLUMN")
        void shouldDetectMissingColumn() throws Exception {
            exec("CREATE TABLE t (id INTEGER PRIMARY KEY)");

            ManagedDatabaseSchema.DatabaseSchema expected = schemaOf(table(
                    "t",
                    List.of(
                            col("id", "INTEGER", false, null, 1),
                            col("name", "TEXT", true, null, 0)
                    ),
                    List.of()
            ));

            DatabaseSchemaInspector.SchemaComparison result =
                    DatabaseSchemaInspector.compare(connection, expected);

            assertThat(result.details())
                    .extracting(DatabaseSchemaInspector.SchemaDifference::kind,
                            DatabaseSchemaInspector.SchemaDifference::columnName)
                    .containsExactly(
                            org.assertj.core.api.Assertions.tuple(
                                    DatabaseSchemaInspector.SchemaDifferenceKind.MISSING_COLUMN, "name"));
        }

        @Test
        @DisplayName("额外列应产生 UNMANAGED_COLUMN")
        void shouldDetectUnmanagedColumn() throws Exception {
            exec("CREATE TABLE t (id INTEGER PRIMARY KEY, extra TEXT)");

            ManagedDatabaseSchema.DatabaseSchema expected = schemaOf(table(
                    "t",
                    List.of(col("id", "INTEGER", false, null, 1)),
                    List.of()
            ));

            DatabaseSchemaInspector.SchemaComparison result =
                    DatabaseSchemaInspector.compare(connection, expected);

            assertThat(result.details())
                    .extracting(DatabaseSchemaInspector.SchemaDifference::kind,
                            DatabaseSchemaInspector.SchemaDifference::columnName)
                    .containsExactly(
                            org.assertj.core.api.Assertions.tuple(
                                    DatabaseSchemaInspector.SchemaDifferenceKind.UNMANAGED_COLUMN, "extra"));
        }

        @Test
        @DisplayName("缺索引应产生 MISSING_INDEX")
        void shouldDetectMissingIndex() throws Exception {
            exec("CREATE TABLE t (id INTEGER PRIMARY KEY, tag_id INTEGER)");

            ManagedDatabaseSchema.IndexSpec expectedIdx = new ManagedDatabaseSchema.IndexSpec(
                    "idx_t_tag", ManagedDatabaseSchema.IndexOrigin.CREATE_INDEX, false, List.of("tag_id"));
            ManagedDatabaseSchema.DatabaseSchema expected = schemaOf(table(
                    "t",
                    List.of(
                            col("id", "INTEGER", false, null, 1),
                            col("tag_id", "INTEGER", false, null, 0)
                    ),
                    List.of(expectedIdx)
            ));

            DatabaseSchemaInspector.SchemaComparison result =
                    DatabaseSchemaInspector.compare(connection, expected);

            assertThat(result.details())
                    .extracting(DatabaseSchemaInspector.SchemaDifference::kind)
                    .containsExactly(DatabaseSchemaInspector.SchemaDifferenceKind.MISSING_INDEX);
        }

        @Test
        @DisplayName("额外索引应产生 UNMANAGED_INDEX")
        void shouldDetectUnmanagedIndex() throws Exception {
            exec("CREATE TABLE t (id INTEGER PRIMARY KEY, tag_id INTEGER)");
            exec("CREATE INDEX idx_t_tag ON t(tag_id)");

            ManagedDatabaseSchema.DatabaseSchema expected = schemaOf(table(
                    "t",
                    List.of(
                            col("id", "INTEGER", false, null, 1),
                            col("tag_id", "INTEGER", false, null, 0)
                    ),
                    List.of()
            ));

            DatabaseSchemaInspector.SchemaComparison result =
                    DatabaseSchemaInspector.compare(connection, expected);

            assertThat(result.details())
                    .extracting(DatabaseSchemaInspector.SchemaDifference::kind)
                    .containsExactly(DatabaseSchemaInspector.SchemaDifferenceKind.UNMANAGED_INDEX);
        }

        @Test
        @DisplayName("被括号包裹的默认值应被归一化为相同")
        void shouldNormalizeParenthesizedDefaults() throws Exception {
            // SQLite 写回 PRAGMA dflt_value 时常带括号（如 (0) 或 ('zh')）
            exec("CREATE TABLE t (id INTEGER PRIMARY KEY, flag INTEGER DEFAULT 0)");

            ManagedDatabaseSchema.DatabaseSchema expected = schemaOf(table(
                    "t",
                    List.of(
                            col("id", "INTEGER", false, null, 1),
                            col("flag", "INTEGER", false, "0", 0)
                    ),
                    List.of()
            ));

            DatabaseSchemaInspector.SchemaComparison result =
                    DatabaseSchemaInspector.compare(connection, expected);

            assertThat(result.matches())
                    .as("差异: %s", result.details())
                    .isTrue();
        }

        @Test
        @DisplayName("默认值不一致应产生 COLUMN_DEFAULT_MISMATCH")
        void shouldDetectDefaultMismatch() throws Exception {
            exec("CREATE TABLE t (id INTEGER PRIMARY KEY, flag INTEGER DEFAULT 0)");

            ManagedDatabaseSchema.DatabaseSchema expected = schemaOf(table(
                    "t",
                    List.of(
                            col("id", "INTEGER", false, null, 1),
                            col("flag", "INTEGER", false, "1", 0)
                    ),
                    List.of()
            ));

            DatabaseSchemaInspector.SchemaComparison result =
                    DatabaseSchemaInspector.compare(connection, expected);

            assertThat(result.details())
                    .extracting(DatabaseSchemaInspector.SchemaDifference::kind)
                    .containsExactly(DatabaseSchemaInspector.SchemaDifferenceKind.COLUMN_DEFAULT_MISMATCH);
        }

        @Test
        @DisplayName("列大小写应在比对时被归一化")
        void shouldNormalizeColumnNameCase() throws Exception {
            exec("CREATE TABLE t (Id INTEGER PRIMARY KEY, NAME TEXT NOT NULL)");

            ManagedDatabaseSchema.DatabaseSchema expected = schemaOf(table(
                    "t",
                    List.of(
                            col("ID", "integer", false, null, 1),
                            col("name", "TEXT", true, null, 0)
                    ),
                    List.of()
            ));

            DatabaseSchemaInspector.SchemaComparison result =
                    DatabaseSchemaInspector.compare(connection, expected);

            assertThat(result.matches())
                    .as("差异: %s", result.details())
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("summary 截断")
    class SummaryTests {

        @Test
        @DisplayName("差异多于 limit 时应附加 more-differences 提示")
        void shouldTruncateSummaryAndAppendMoreHint() {
            DatabaseSchemaInspector.SchemaDifference d1 = new DatabaseSchemaInspector.SchemaDifference(
                    DatabaseSchemaInspector.SchemaDifferenceKind.MISSING_TABLE, "t1", null, "缺少表: t1");
            DatabaseSchemaInspector.SchemaDifference d2 = new DatabaseSchemaInspector.SchemaDifference(
                    DatabaseSchemaInspector.SchemaDifferenceKind.MISSING_TABLE, "t2", null, "缺少表: t2");
            DatabaseSchemaInspector.SchemaDifference d3 = new DatabaseSchemaInspector.SchemaDifference(
                    DatabaseSchemaInspector.SchemaDifferenceKind.MISSING_TABLE, "t3", null, "缺少表: t3");

            DatabaseSchemaInspector.SchemaComparison comparison =
                    new DatabaseSchemaInspector.SchemaComparison(false, List.of(d1, d2, d3));

            String summary = comparison.summary(2);

            assertThat(summary).contains("缺少表: t1", "缺少表: t2");
            assertThat(summary).doesNotContain("缺少表: t3");
            // i18n 模板里写着 "另有 {0} 项差异"
            assertThat(summary).contains("1");
        }

        @Test
        @DisplayName("无差异时 summary 应输出占位文案而非空字符串")
        void shouldRenderEmptySummary() {
            DatabaseSchemaInspector.SchemaComparison comparison =
                    new DatabaseSchemaInspector.SchemaComparison(true, List.of());

            String summary = comparison.summary(5);

            assertThat(summary).isNotBlank();
        }
    }

    @Nested
    @DisplayName("受管 schema 合并结果与运行时 init 后 schema 一致")
    class ProductionSchemaTests {

        private DatabaseInitializer newInitializer(javax.sql.DataSource ds) {
            top.sywyar.pixivdownload.plugin.DatabaseSchemaRegistry registry =
                    top.sywyar.pixivdownload.plugin.DatabaseSchemaRegistry.forBuiltInPlugins();
            return new DatabaseInitializer(
                    new org.springframework.jdbc.core.JdbcTemplate(ds),
                    registry.contributions(), registry.mergedSchema(),
                    top.sywyar.pixivdownload.i18n.TestI18nBeans.appMessages(), event -> {});
        }

        @Test
        @DisplayName("DatabaseInitializer + PixivDatabase.init() 后由其管理的所有表都应与受管 schema 完全匹配")
        void shouldMatchProductionSchemaAfterInit() throws Exception {
            org.springframework.jdbc.datasource.SingleConnectionDataSource ds =
                    new org.springframework.jdbc.datasource.SingleConnectionDataSource();
            ds.setDriverClassName("org.sqlite.JDBC");
            ds.setUrl("jdbc:sqlite::memory:");
            ds.setSuppressClose(true);

            org.apache.ibatis.mapping.Environment env = new org.apache.ibatis.mapping.Environment(
                    "test", new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(), ds);
            org.apache.ibatis.session.Configuration config = new org.apache.ibatis.session.Configuration(env);
            config.setMapUnderscoreToCamelCase(true);
            config.addMapper(PixivMapper.class);
            config.addMapper(PathPrefixMapper.class);
            org.apache.ibatis.session.SqlSessionFactory factory =
                    new org.apache.ibatis.session.SqlSessionFactoryBuilder().build(config);

            try (org.apache.ibatis.session.SqlSession session = factory.openSession(true)) {
                PixivMapper mapper = session.getMapper(PixivMapper.class);
                PathPrefixMapper pathPrefixMapper = session.getMapper(PathPrefixMapper.class);
                DatabaseInitializer initializer = newInitializer(ds);
                initializer.initialize();
                PathPrefixCodec codec = new PathPrefixCodec(
                        pathPrefixMapper, new top.sywyar.pixivdownload.core.appconfig.DownloadConfig(),
                        top.sywyar.pixivdownload.i18n.TestI18nBeans.appMessages());
                codec.init();
                PixivDatabase database = new PixivDatabase(
                        mapper, top.sywyar.pixivdownload.i18n.TestI18nBeans.appMessages(), codec, initializer);
                database.init();

                // 仅比对 PixivDatabase 业务面使用的表；authors / collections / artwork_collections
                // 等其余受管表的建表对照由 DatabaseInitializerTest 全表覆盖，超出本测试范围。
                Set<String> initManaged = Set.of(
                        "artworks", "file_author_names", "file_name_templates",
                        "statistics", "tags", "artwork_tags", "artwork_image_hashes");
                ManagedDatabaseSchema.DatabaseSchema sub = specSubset(initManaged);

                try (Connection c = ds.getConnection()) {
                    DatabaseSchemaInspector.SchemaComparison comparison =
                            DatabaseSchemaInspector.compare(c, sub);

                    // 受管 schema 之外的表（这里没有）才会被报为 UNMANAGED_TABLE；过滤掉以聚焦缺失/漂移
                    java.util.List<DatabaseSchemaInspector.SchemaDifference> drift =
                            comparison.details().stream()
                                    .filter(d -> d.kind() != DatabaseSchemaInspector.SchemaDifferenceKind.UNMANAGED_TABLE)
                                    .toList();
                    assertThat(drift)
                            .as("init() 创建的表与受管 schema 之间不应有漂移：%s", drift)
                            .isEmpty();
                }
            } finally {
                ds.destroy();
            }
        }

        @Test
        @DisplayName("DatabaseInitializer + NovelDatabase.init() 后由其管理的所有表都应与受管 schema 完全匹配")
        void shouldMatchNovelProductionSchemaAfterInit() throws Exception {
            org.springframework.jdbc.datasource.SingleConnectionDataSource ds =
                    new org.springframework.jdbc.datasource.SingleConnectionDataSource();
            ds.setDriverClassName("org.sqlite.JDBC");
            ds.setUrl("jdbc:sqlite::memory:");
            ds.setSuppressClose(true);

            org.apache.ibatis.mapping.Environment env = new org.apache.ibatis.mapping.Environment(
                    "test", new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(), ds);
            org.apache.ibatis.session.Configuration config = new org.apache.ibatis.session.Configuration(env);
            config.setMapUnderscoreToCamelCase(true);
            config.addMapper(top.sywyar.pixivdownload.novel.db.NovelMapper.class);
            org.apache.ibatis.session.SqlSessionFactory factory =
                    new org.apache.ibatis.session.SqlSessionFactoryBuilder().build(config);

            try (org.apache.ibatis.session.SqlSession session = factory.openSession(true)) {
                top.sywyar.pixivdownload.novel.db.NovelMapper mapper =
                        session.getMapper(top.sywyar.pixivdownload.novel.db.NovelMapper.class);
                DatabaseInitializer initializer = newInitializer(ds);
                initializer.initialize();
                top.sywyar.pixivdownload.novel.db.NovelDatabase database =
                        new top.sywyar.pixivdownload.novel.db.NovelDatabase(mapper, null, null, initializer, null);
                database.init();

                // 仅比对 NovelDatabase.init() 实际建的表；共享 tags 表由 PixivDatabase 负责。
                Set<String> initManaged = Set.of(
                        "novels", "novel_series", "novel_tags", "novel_series_tags",
                        "novel_collections", "novel_images");
                ManagedDatabaseSchema.DatabaseSchema sub = specSubset(initManaged);

                try (Connection c = ds.getConnection()) {
                    DatabaseSchemaInspector.SchemaComparison comparison =
                            DatabaseSchemaInspector.compare(c, sub);

                    java.util.List<DatabaseSchemaInspector.SchemaDifference> drift =
                            comparison.details().stream()
                                    .filter(d -> d.kind() != DatabaseSchemaInspector.SchemaDifferenceKind.UNMANAGED_TABLE)
                                    .toList();
                    assertThat(drift)
                            .as("init() 创建的小说表与受管 schema 之间不应有漂移：%s", drift)
                            .isEmpty();
                }
            } finally {
                ds.destroy();
            }
        }

        @Test
        @DisplayName("DatabaseInitializer + ScheduledTaskStore.init() 后 scheduled_tasks 应与受管 schema 完全匹配")
        void shouldMatchScheduledTaskProductionSchemaAfterInit() throws Exception {
            org.springframework.jdbc.datasource.SingleConnectionDataSource ds =
                    new org.springframework.jdbc.datasource.SingleConnectionDataSource();
            ds.setDriverClassName("org.sqlite.JDBC");
            ds.setUrl("jdbc:sqlite::memory:");
            ds.setSuppressClose(true);

            org.apache.ibatis.mapping.Environment env = new org.apache.ibatis.mapping.Environment(
                    "test", new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(), ds);
            org.apache.ibatis.session.Configuration config = new org.apache.ibatis.session.Configuration(env);
            config.setMapUnderscoreToCamelCase(true);
            config.addMapper(top.sywyar.pixivdownload.core.schedule.db.ScheduledTaskMapper.class);
            org.apache.ibatis.session.SqlSessionFactory factory =
                    new org.apache.ibatis.session.SqlSessionFactoryBuilder().build(config);

            try (org.apache.ibatis.session.SqlSession session = factory.openSession(true)) {
                top.sywyar.pixivdownload.core.schedule.db.ScheduledTaskMapper mapper =
                        session.getMapper(top.sywyar.pixivdownload.core.schedule.db.ScheduledTaskMapper.class);
                DatabaseInitializer initializer = newInitializer(ds);
                initializer.initialize();
                top.sywyar.pixivdownload.core.schedule.db.ScheduledTaskStoreImpl store =
                        new top.sywyar.pixivdownload.core.schedule.db.ScheduledTaskStoreImpl(mapper, initializer);
                store.init();

                Set<String> initManaged = Set.of("scheduled_tasks", "scheduled_task_pending");
                ManagedDatabaseSchema.DatabaseSchema sub = specSubset(initManaged);

                try (Connection c = ds.getConnection()) {
                    DatabaseSchemaInspector.SchemaComparison comparison =
                            DatabaseSchemaInspector.compare(c, sub);

                    java.util.List<DatabaseSchemaInspector.SchemaDifference> drift =
                            comparison.details().stream()
                                    .filter(d -> d.kind() != DatabaseSchemaInspector.SchemaDifferenceKind.UNMANAGED_TABLE)
                                    .toList();
                    assertThat(drift)
                            .as("init() 创建的计划任务表与受管 schema 之间不应有漂移：%s", drift)
                            .isEmpty();
                }
            } finally {
                ds.destroy();
            }
        }
    }
}

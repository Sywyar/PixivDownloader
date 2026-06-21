package top.sywyar.pixivdownload.core.schedule.db;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import top.sywyar.pixivdownload.core.db.schema.DatabaseInitializer;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.plugin.DatabaseSchemaRegistry;
import top.sywyar.pixivdownload.core.schedule.ScheduledTask;
import top.sywyar.pixivdownload.core.schedule.ScheduledTaskInsert;
import top.sywyar.pixivdownload.core.schedule.ScheduledTaskType;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ScheduledTaskStore 启动初始化")
class ScheduledTaskStoreTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private SingleConnectionDataSource ds;
    private SqlSession session;
    private ScheduledTaskMapper mapper;
    private ScheduledTaskStoreImpl store;

    @BeforeEach
    void setUp() {
        ds = new SingleConnectionDataSource();
        ds.setDriverClassName("org.sqlite.JDBC");
        ds.setUrl("jdbc:sqlite::memory:");
        ds.setSuppressClose(true);

        Environment env = new Environment("test", new JdbcTransactionFactory(), ds);
        Configuration config = new Configuration(env);
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(ScheduledTaskMapper.class);
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(config);
        session = factory.openSession(true);
        mapper = session.getMapper(ScheduledTaskMapper.class);

        // 建表 / 补列 / 索引统一由 DatabaseInitializer 执行；init 只做数据迁移（本身幂等），
        // 插入"旧版本"任务后重跑 init 模拟升级启动
        DatabaseSchemaRegistry registry = DatabaseSchemaRegistry.forBuiltInPlugins();
        DatabaseInitializer initializer = new DatabaseInitializer(new JdbcTemplate(ds),
                registry.contributions(), registry.mergedSchema(),
                TestI18nBeans.appMessages(), event -> {});
        initializer.initialize();
        store = new ScheduledTaskStoreImpl(mapper, initializer);
        store.init();
    }

    @AfterEach
    void tearDown() {
        session.close();
        ds.destroy();
    }

    private long insertTask(String name, String paramsJson) {
        ScheduledTaskInsert row = new ScheduledTaskInsert();
        row.setName(name);
        row.setEnabled(true);
        row.setType(ScheduledTaskType.USER_NEW);
        row.setParamsJson(paramsJson);
        row.setTriggerKind(ScheduledTask.TRIGGER_INTERVAL);
        row.setIntervalMinutes(60);
        row.setCookieMode(ScheduledTask.COOKIE_RESTRICTED);
        row.setNextRunTime(1000L);
        row.setCreatedTime(1_700_000_000_000L);
        mapper.insert(row);
        return row.getId();
    }

    @Test
    @DisplayName("启动迁移为缺少 redownloadDeleted 的旧任务快照补 download.redownloadDeleted=false，其余字段不动")
    void shouldBackfillRedownloadDeletedForLegacyTasks() throws Exception {
        long noDownloadNode = insertTask("无 download 段", "{\"kind\":\"illust\",\"source\":{\"userId\":\"1\"}}");
        long legacyDownload = insertTask("旧 download 段",
                "{\"kind\":\"illust\",\"download\":{\"bookmark\":true,\"concurrent\":3}}");

        store.init();

        JsonNode root1 = JSON.readTree(mapper.findById(noDownloadNode).paramsJson());
        assertThat(root1.path("download").path("redownloadDeleted").asBoolean(true)).isFalse();
        assertThat(root1.path("kind").asText()).isEqualTo("illust");
        assertThat(root1.path("source").path("userId").asText()).isEqualTo("1");

        JsonNode root2 = JSON.readTree(mapper.findById(legacyDownload).paramsJson());
        assertThat(root2.path("download").path("redownloadDeleted").asBoolean(true)).isFalse();
        assertThat(root2.path("download").path("bookmark").asBoolean()).isTrue();
        assertThat(root2.path("download").path("concurrent").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("已有 redownloadDeleted 的任务快照保持原值不被迁移覆盖（幂等）")
    void shouldKeepExistingRedownloadDeletedValue() throws Exception {
        long enabled = insertTask("已显式开启",
                "{\"download\":{\"redownloadDeleted\":true}}");

        store.init();
        store.init();

        JsonNode root = JSON.readTree(mapper.findById(enabled).paramsJson());
        assertThat(root.path("download").path("redownloadDeleted").asBoolean(false)).isTrue();
    }
}

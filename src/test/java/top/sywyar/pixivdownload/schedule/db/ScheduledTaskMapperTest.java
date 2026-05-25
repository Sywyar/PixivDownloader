package top.sywyar.pixivdownload.schedule.db;

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
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import top.sywyar.pixivdownload.schedule.ScheduledTask;
import top.sywyar.pixivdownload.schedule.ScheduledTaskType;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ScheduledTaskMapper 数据访问")
class ScheduledTaskMapperTest {

    private SingleConnectionDataSource ds;
    private SqlSessionFactory factory;

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
        factory = new SqlSessionFactoryBuilder().build(config);

        try (SqlSession session = factory.openSession(true)) {
            ScheduledTaskMapper mapper = session.getMapper(ScheduledTaskMapper.class);
            mapper.createScheduledTasksTable();
            mapper.createScheduledTasksNextRunIndex();
        }
    }

    @AfterEach
    void tearDown() {
        ds.destroy();
    }

    private ScheduledTaskInsert sample(String name, Long nextRun, String cookie) {
        ScheduledTaskInsert row = new ScheduledTaskInsert();
        row.setName(name);
        row.setEnabled(true);
        row.setType(ScheduledTaskType.USER_NEW);
        row.setParamsJson("{\"userId\":\"123\"}");
        row.setTriggerKind(ScheduledTask.TRIGGER_INTERVAL);
        row.setIntervalMinutes(60);
        row.setCookieMode(cookie == null ? ScheduledTask.COOKIE_RESTRICTED : ScheduledTask.COOKIE_BOUND);
        row.setCookieSnapshot(cookie);
        row.setNextRunTime(nextRun);
        row.setCreatedTime(1_700_000_000_000L);
        return row;
    }

    @Test
    @DisplayName("插入后回填自增主键，findById 能读回（不含 cookie 投影）")
    void shouldInsertAndReadBack() {
        try (SqlSession session = factory.openSession(true)) {
            ScheduledTaskMapper mapper = session.getMapper(ScheduledTaskMapper.class);
            ScheduledTaskInsert row = sample("任务A", 1000L, "PHPSESSID=secret; x=y");
            mapper.insert(row);

            assertThat(row.getId()).isNotNull();
            ScheduledTask read = mapper.findById(row.getId());
            assertThat(read).isNotNull();
            assertThat(read.name()).isEqualTo("任务A");
            assertThat(read.type()).isEqualTo(ScheduledTaskType.USER_NEW);
            // cookie 红线：行投影里写入的快照仍可经专用通道读回，但 record 本身不承载 cookie
            assertThat(mapper.findCookieSnapshot(read.id())).isEqualTo("PHPSESSID=secret; x=y");
        }
    }

    @Test
    @DisplayName("findCookieSnapshot 是取 cookie 的唯一通道，findAll 投影不含 cookie")
    void shouldExposeCookieOnlyViaDedicatedAccessor() {
        try (SqlSession session = factory.openSession(true)) {
            ScheduledTaskMapper mapper = session.getMapper(ScheduledTaskMapper.class);
            ScheduledTaskInsert row = sample("任务B", 1000L, "PHPSESSID=topsecret");
            mapper.insert(row);

            List<ScheduledTask> all = mapper.findAll();
            assertThat(all).hasSize(1);
            // 行投影只暴露 cookieMode，凭证本身只能经专用裸标量通道取得
            assertThat(all.get(0).cookieMode()).isEqualTo(ScheduledTask.COOKIE_BOUND);
            assertThat(mapper.findCookieSnapshot(row.getId())).isEqualTo("PHPSESSID=topsecret");
        }
    }

    @Test
    @DisplayName("findDue 只返回 enabled 且 next_run_time<=now 的任务")
    void shouldFilterDueTasks() {
        try (SqlSession session = factory.openSession(true)) {
            ScheduledTaskMapper mapper = session.getMapper(ScheduledTaskMapper.class);
            mapper.insert(sample("到期", 500L, null));
            ScheduledTaskInsert future = sample("未到期", 5000L, null);
            mapper.insert(future);
            ScheduledTaskInsert disabled = sample("已停用", 100L, null);
            disabled.setEnabled(false);
            mapper.insert(disabled);

            List<ScheduledTask> due = mapper.findDue(1000L);
            assertThat(due).extracting(ScheduledTask::name).containsExactly("到期");
        }
    }

    @Test
    @DisplayName("updateCookie 写入快照、updateRunResult 记录运行结果")
    void shouldUpdateCookieAndRunResult() {
        try (SqlSession session = factory.openSession(true)) {
            ScheduledTaskMapper mapper = session.getMapper(ScheduledTaskMapper.class);
            ScheduledTaskInsert row = sample("任务C", 1000L, null);
            mapper.insert(row);
            long id = row.getId();

            mapper.updateCookie(id, "PHPSESSID=new", ScheduledTask.COOKIE_BOUND);
            assertThat(mapper.findCookieSnapshot(id)).isEqualTo("PHPSESSID=new");
            assertThat(mapper.findById(id).cookieMode()).isEqualTo(ScheduledTask.COOKIE_BOUND);

            mapper.updateRunResult(id, 2000L, "OK", 8000L);
            ScheduledTask read = mapper.findById(id);
            assertThat(read.lastRunTime()).isEqualTo(2000L);
            assertThat(read.lastStatus()).isEqualTo("OK");
            assertThat(read.nextRunTime()).isEqualTo(8000L);
        }
    }
}

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import top.sywyar.pixivdownload.core.db.DatabaseInitializer;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.plugin.DatabaseSchemaRegistry;
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

        // 建表 / 补列 / 索引统一由 DatabaseInitializer 执行
        DatabaseSchemaRegistry registry = DatabaseSchemaRegistry.forBuiltInPlugins();
        new DatabaseInitializer(new JdbcTemplate(ds),
                registry.contributions(), registry.mergedSchema(),
                TestI18nBeans.appMessages(), event -> {}).initialize();
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
    @DisplayName("USER_REQUEST 类型经 EnumTypeHandler 按名往返（新增枚举值无需 DDL）")
    void shouldRoundTripUserRequestType() {
        try (SqlSession session = factory.openSession(true)) {
            ScheduledTaskMapper mapper = session.getMapper(ScheduledTaskMapper.class);
            ScheduledTaskInsert row = sample("约稿任务", 1000L, null);
            row.setType(ScheduledTaskType.USER_REQUEST);
            mapper.insert(row);
            assertThat(mapper.findById(row.getId()).type()).isEqualTo(ScheduledTaskType.USER_REQUEST);
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
    @DisplayName("updateProxy 设置 / 清除任务级单独代理，行投影能读回")
    void shouldUpdateAndClearProxySnapshot() {
        try (SqlSession session = factory.openSession(true)) {
            ScheduledTaskMapper mapper = session.getMapper(ScheduledTaskMapper.class);
            ScheduledTaskInsert row = sample("任务P", 1000L, null);
            mapper.insert(row);
            long id = row.getId();
            assertThat(mapper.findById(id).proxySnapshot()).isNull();

            mapper.updateProxy(id, "127.0.0.1:7890");
            assertThat(mapper.findById(id).proxySnapshot()).isEqualTo("127.0.0.1:7890");

            mapper.updateProxy(id, null);
            assertThat(mapper.findById(id).proxySnapshot()).isNull();
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
    @DisplayName("findDue 把残留 run_started_time 的中断任务纳入（next_run 在未来也立即重跑），但挂起态仍被排除")
    void findDueIncludesInterruptedRegardlessOfNextRun() {
        try (SqlSession session = factory.openSession(true)) {
            ScheduledTaskMapper mapper = session.getMapper(ScheduledTaskMapper.class);
            // next_run 仍在未来（典型：立即运行触发、尚未到周期就被强杀），但残留 run_started_time → 应被捡起重跑
            ScheduledTaskInsert interrupted = sample("中断未到期", 9000L, null);
            interrupted.setRunStartedTime(1234L);
            mapper.insert(interrupted);
            // 中断 + 挂起态：状态门优先，仍不重跑
            ScheduledTaskInsert pausedInterrupted = sample("中断且暂停", 9000L, null);
            pausedInterrupted.setRunStartedTime(1234L);
            pausedInterrupted.setLastStatus(ScheduledTask.STATUS_PAUSED);
            mapper.insert(pausedInterrupted);

            List<String> due = mapper.findDue(1000L).stream().map(ScheduledTask::name).toList();
            assertThat(due).contains("中断未到期");
            assertThat(due).doesNotContain("中断且暂停");
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

            mapper.updateRunResult(id, 2000L, "ERROR", "no image urls resolved", 8000L);
            ScheduledTask read = mapper.findById(id);
            assertThat(read.lastRunTime()).isEqualTo(2000L);
            assertThat(read.lastStatus()).isEqualTo("ERROR");
            assertThat(read.lastMessage()).isEqualTo("no image urls resolved");
            assertThat(read.nextRunTime()).isEqualTo(8000L);
        }
    }

    @Test
    @DisplayName("updateRunStarted 落库开始时刻，updateRunResult 一并清空 run_started_time")
    void shouldClearRunStartedOnRunResult() {
        try (SqlSession session = factory.openSession(true)) {
            ScheduledTaskMapper mapper = session.getMapper(ScheduledTaskMapper.class);
            ScheduledTaskInsert row = sample("任务D", 1000L, null);
            mapper.insert(row);
            long id = row.getId();
            // 创建时 run_started_time 为 null
            assertThat(mapper.findById(id).runStartedTime()).isNull();

            mapper.updateRunStarted(id, 3000L);
            assertThat(mapper.findById(id).runStartedTime()).isEqualTo(3000L);

            // 正常结束：updateRunResult 一并把 run_started_time 清为 null（中断信号清除）
            mapper.updateRunResult(id, 4000L, "OK", null, 10000L);
            assertThat(mapper.findById(id).runStartedTime()).isNull();
        }
    }

    @Test
    @DisplayName("updateWatermark 推进水位线，findById 能读回")
    void shouldUpdateWatermark() {
        try (SqlSession session = factory.openSession(true)) {
            ScheduledTaskMapper mapper = session.getMapper(ScheduledTaskMapper.class);
            ScheduledTaskInsert row = sample("任务E", 1000L, null);
            mapper.insert(row);
            long id = row.getId();
            // 创建时水位线为 null
            assertThat(mapper.findById(id).watermarkId()).isNull();

            mapper.updateWatermark(id, 123456L);
            assertThat(mapper.findById(id).watermarkId()).isEqualTo(123456L);
        }
    }

    @Test
    @DisplayName("updateDefinition 编辑任务定义时清空水位线，避免沿用旧来源锚点")
    void shouldResetWatermarkWhenDefinitionChanges() {
        try (SqlSession session = factory.openSession(true)) {
            ScheduledTaskMapper mapper = session.getMapper(ScheduledTaskMapper.class);
            ScheduledTaskInsert row = sample("任务F", 1000L, null);
            mapper.insert(row);
            long id = row.getId();
            mapper.updateWatermark(id, 123456L);

            mapper.updateDefinition(
                    id, "任务F2", ScheduledTaskType.SEARCH,
                    "{\"kind\":\"illust\",\"source\":{\"word\":\"tag\",\"maxPages\":-1}}",
                    ScheduledTask.TRIGGER_INTERVAL, 30, null, 2000L);

            ScheduledTask read = mapper.findById(id);
            assertThat(read.name()).isEqualTo("任务F2");
            assertThat(read.type()).isEqualTo(ScheduledTaskType.SEARCH);
            assertThat(read.watermarkId()).isNull();
        }
    }

    @Test
    @DisplayName("findDue 排除挂起态（OVERUSE_PAUSED / AUTH_EXPIRED / PAUSED），仅返回可运行任务")
    void findDueGatesSuspendedStatuses() {
        try (SqlSession session = factory.openSession(true)) {
            ScheduledTaskMapper mapper = session.getMapper(ScheduledTaskMapper.class);
            long ok = insertWithStatus(mapper, "正常", null);
            long overuse = insertWithStatus(mapper, "过度访问", ScheduledTask.STATUS_OVERUSE_PAUSED);
            long auth = insertWithStatus(mapper, "鉴权失效", ScheduledTask.STATUS_AUTH_EXPIRED);
            long paused = insertWithStatus(mapper, "手动暂停", ScheduledTask.STATUS_PAUSED);

            List<Long> due = mapper.findDue(5000L).stream().map(ScheduledTask::id).toList();
            assertThat(due).contains(ok);
            assertThat(due).doesNotContain(overuse, auth, paused);
        }
    }

    @Test
    @DisplayName("freezeAccount 仅冻结同账号非挂起态任务；clearSuspendForAccount 清挂起并重置 next_run")
    void freezeAndClearAccount() {
        try (SqlSession session = factory.openSession(true)) {
            ScheduledTaskMapper mapper = session.getMapper(ScheduledTaskMapper.class);
            long a1 = insertWithStatus(mapper, "A1", null);
            long a2 = insertWithStatus(mapper, "A2", null);
            mapper.updateAccountId(a1, "12345");
            mapper.updateAccountId(a2, "12345");

            int frozen = mapper.freezeAccount("12345", ScheduledTask.STATUS_OVERUSE_PAUSED, "999");
            assertThat(frozen).isEqualTo(2);
            assertThat(mapper.findById(a1).lastStatus()).isEqualTo(ScheduledTask.STATUS_OVERUSE_PAUSED);
            assertThat(mapper.findById(a1).lastMessage()).isEqualTo("999");

            mapper.clearSuspendForAccount("12345", 8000L);
            assertThat(mapper.findById(a1).lastStatus()).isNull();
            assertThat(mapper.findById(a2).nextRunTime()).isEqualTo(8000L);
            assertThat(mapper.findByAccountId("12345")).hasSize(2);
        }
    }

    @Test
    @DisplayName("clearCookieAndAccount：清 Cookie 转受限的同时清除 account_id 与 ack_warning_time，此后账号级冻结不再命中")
    void clearCookieAlsoClearsAccountBinding() {
        try (SqlSession session = factory.openSession(true)) {
            ScheduledTaskMapper mapper = session.getMapper(ScheduledTaskMapper.class);
            // 绑定 Cookie 的任务：带账号标识 + 管理员「无视风险」放行记录
            ScheduledTaskInsert boundRow = sample("绑定任务", 1000L, "PHPSESSID=12345_abc");
            mapper.insert(boundRow);
            long bound = boundRow.getId();
            mapper.updateAccountId(bound, "12345");
            mapper.updateAckWarning("12345", 999000L);
            // 同账号另一任务，用来在解绑后触发账号级过度访问冻结
            ScheduledTaskInsert siblingRow = sample("同账号兄弟", 1000L, "PHPSESSID=12345_def");
            mapper.insert(siblingRow);
            long sibling = siblingRow.getId();
            mapper.updateAccountId(sibling, "12345");

            // 解除授权 / 失效自动降级：清 Cookie 转受限，账号绑定一并清除
            mapper.clearCookieAndAccount(bound, ScheduledTask.COOKIE_RESTRICTED);

            ScheduledTask read = mapper.findById(bound);
            assertThat(read.cookieMode()).isEqualTo(ScheduledTask.COOKIE_RESTRICTED);
            assertThat(read.accountId()).isNull();
            assertThat(read.ackWarningTime()).isNull();
            assertThat(mapper.findCookieSnapshot(bound)).isNull();

            // 兄弟任务触发同账号过度访问冻结：已解绑的任务不再被牵连
            mapper.freezeAccount("12345", ScheduledTask.STATUS_OVERUSE_PAUSED, "888");
            assertThat(mapper.findById(bound).lastStatus()).isNull();
            assertThat(mapper.findById(sibling).lastStatus()).isEqualTo(ScheduledTask.STATUS_OVERUSE_PAUSED);
            assertThat(mapper.findByAccountId("12345"))
                    .extracting(ScheduledTask::id).containsExactly(sibling);
        }
    }

    @Test
    @DisplayName("updateAckWarning / armRetry / clearRetryArmed 写读一致")
    void ackAndRetryArming() {
        try (SqlSession session = factory.openSession(true)) {
            ScheduledTaskMapper mapper = session.getMapper(ScheduledTaskMapper.class);
            ScheduledTaskInsert row = sample("任务G", 1000L, null);
            mapper.insert(row);
            long id = row.getId();
            mapper.updateAccountId(id, "777");

            mapper.updateAckWarning("777", 1234L);
            assertThat(mapper.findById(id).ackWarningTime()).isEqualTo(1234L);

            mapper.armRetry(id);
            assertThat(mapper.findById(id).pendingRetryArmed()).isEqualTo(1);
            mapper.clearRetryArmed(id);
            assertThat(mapper.findById(id).pendingRetryArmed()).isZero();
        }
    }

    @Test
    @DisplayName("隔离表：insertPending 冲突保留 first_seen，incPendingAttempts 累加，deletePending 移除")
    void pendingTableCrud() {
        try (SqlSession session = factory.openSession(true)) {
            ScheduledTaskMapper mapper = session.getMapper(ScheduledTaskMapper.class);
            mapper.insertPending(1L, 100L, "受限", 5000L);
            mapper.insertPending(1L, 100L, "再次受限", 6000L); // 冲突：保留 first_seen、attempts 不变
            List<ScheduledTaskPending> list = mapper.listPending(1L);
            assertThat(list).hasSize(1);
            assertThat(list.get(0).firstSeenTime()).isEqualTo(5000L);
            assertThat(list.get(0).attempts()).isZero();
            assertThat(list.get(0).reason()).isEqualTo("再次受限");

            mapper.incPendingAttempts(1L, 100L, 7000L);
            assertThat(mapper.listPending(1L).get(0).attempts()).isEqualTo(1);

            mapper.deletePending(1L, 100L);
            assertThat(mapper.listPending(1L)).isEmpty();
        }
    }

    @Test
    @DisplayName("updateRunResult 保留运行中被手动设置的 PAUSED：不覆盖 last_status / last_message / next_run_time")
    void runResultPreservesManualPaused() {
        try (SqlSession session = factory.openSession(true)) {
            ScheduledTaskMapper mapper = session.getMapper(ScheduledTaskMapper.class);
            ScheduledTaskInsert row = sample("任务H", 1000L, null);
            mapper.insert(row);
            long id = row.getId();
            mapper.updateRunStarted(id, 3000L);

            // 模拟用户在任务运行过程中手动点了「暂停」
            mapper.setStatus(id, ScheduledTask.STATUS_PAUSED);

            // 本轮跑完落库结果：CASE 应保留 PAUSED + 旧的 next_run_time，仅清 run_started_time、刷 last_run_time
            mapper.updateRunResult(id, 4000L, "OK", null, 10000L);

            ScheduledTask read = mapper.findById(id);
            assertThat(read.lastStatus()).isEqualTo(ScheduledTask.STATUS_PAUSED);
            assertThat(read.lastMessage()).isNull();
            assertThat(read.nextRunTime()).isEqualTo(1000L); // 保留：不被 OK 路径的新值覆盖
            assertThat(read.lastRunTime()).isEqualTo(4000L); // 仍刷新
            assertThat(read.runStartedTime()).isNull();       // 仍清空
        }
    }

    @Test
    @DisplayName("updateRunResult 非 PAUSED 旧状态正常被新结果覆盖")
    void runResultOverwritesNonPausedStatus() {
        try (SqlSession session = factory.openSession(true)) {
            ScheduledTaskMapper mapper = session.getMapper(ScheduledTaskMapper.class);
            ScheduledTaskInsert row = sample("任务I", 1000L, null);
            row.setLastStatus("OK");
            mapper.insert(row);
            long id = row.getId();

            mapper.updateRunResult(id, 4000L, "ERROR", "boom", 20000L);

            ScheduledTask read = mapper.findById(id);
            assertThat(read.lastStatus()).isEqualTo("ERROR");
            assertThat(read.lastMessage()).isEqualTo("boom");
            assertThat(read.nextRunTime()).isEqualTo(20000L);
        }
    }

    private long insertWithStatus(ScheduledTaskMapper mapper, String name, String status) {
        ScheduledTaskInsert row = sample(name, 1000L, null);
        row.setLastStatus(status);
        mapper.insert(row);
        return row.getId();
    }
}

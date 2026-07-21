package top.sywyar.pixivdownload.core.schedule.db;

import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import top.sywyar.pixivdownload.core.db.schema.DatabaseInitializer;
import top.sywyar.pixivdownload.core.schedule.ScheduleTaskDefinitionUpdate;
import top.sywyar.pixivdownload.core.schedule.ScheduledPendingWork;
import top.sywyar.pixivdownload.core.schedule.ScheduledTask;
import top.sywyar.pixivdownload.core.schedule.ScheduledTaskCreate;
import top.sywyar.pixivdownload.core.schedule.ScheduledTaskCredential;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleLastOutcome;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleSuspendReason;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.plugin.registry.DatabaseSchemaRegistry;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ScheduledTaskStore 中性事务意图")
class ScheduledTaskStoreTest {

    private SingleConnectionDataSource dataSource;
    private ScheduledTaskMapper mapper;
    private ScheduledTaskStoreImpl store;
    private JdbcTemplate jdbc;
    private DatabaseInitializer initializer;
    private DataSourceTransactionManager transactionManager;
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        dataSource = new SingleConnectionDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite::memory:");
        dataSource.setSuppressClose(true);

        Environment environment = new Environment(
                "test", new SpringManagedTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(ScheduledTaskMapper.class);
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
        mapper = new SqlSessionTemplate(factory).getMapper(ScheduledTaskMapper.class);
        jdbc = new JdbcTemplate(dataSource);
        transactionManager = new DataSourceTransactionManager(dataSource);
        transactionTemplate = new TransactionTemplate(transactionManager);

        DatabaseSchemaRegistry registry = DatabaseSchemaRegistry.forBuiltInPlugins();
        initializer = new DatabaseInitializer(jdbc,
                registry.contributions(), registry.mergedSchema(),
                TestI18nBeans.appMessages(), event -> {});
        initializer.initialize();
        store = transactionalStore(mapper);
        store.init();
    }

    @AfterEach
    void tearDown() {
        dataSource.destroy();
    }

    @Test
    @DisplayName("create 返回 generated key，并持久化 canonical 默认状态")
    void createReturnsGeneratedIdAndPersistsCanonicalDefaults() {
        long id = store.create(create("新建任务"));

        assertThat(id).isPositive();
        ScheduledTask created = mapper.findById(id);
        assertThat(created.id()).isEqualTo(id);
        assertThat(created.enabled()).isTrue();
        assertThat(created.proxySnapshot()).isNull();
        assertThat(created.storageVersion()).isEqualTo(ScheduledTask.CURRENT_STORAGE_VERSION);
        assertThat(created.runState()).isNull();
        assertThat(created.runClaimToken()).isNull();
        assertThat(created.lastOutcome()).isEqualTo(ScheduleLastOutcome.NEVER);
        assertThat(created.outcomeCode()).isNull();
        assertThat(created.outcomeMessage()).isNull();
        assertThat(created.lastRunTime()).isNull();
        assertThat(created.checkpointSchema()).isNull();
        assertThat(created.checkpointVersion()).isNull();
        assertThat(created.checkpointJson()).isNull();
        assertThat(created.suspendReason()).isNull();
        assertThat(created.suspendCode()).isNull();
        assertThat(created.suspendDetailJson()).isNull();
        assertThat(created.stateVersion()).isZero();
        assertThat(store.findCredentialMetadata(id)).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT cookie_mode FROM scheduled_tasks WHERE id = ?", String.class, id))
                .isEqualTo("restricted");
    }

    @Test
    @DisplayName("generated key 缺失时 create 失败并回滚已插入任务")
    void createRollsBackWhenGeneratedIdIsMissing() {
        ScheduledTaskStoreImpl missingIdStore = transactionalStore(missingGeneratedIdMapper());

        assertThatThrownBy(() -> missingIdStore.create(create("缺失 ID")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("did not return a generated id");
        assertThat(mapper.countAll()).isZero();
    }

    @Test
    @DisplayName("definition CAS 编辑清 checkpoint、pending 与可修复挂起")
    void definitionEditResetsCheckpointAndPending() {
        ScheduledTaskInsertRow row = sample("编辑前");
        row.setCheckpointSchema("fixture.checkpoint");
        row.setCheckpointVersion(1);
        row.setCheckpointJson("{\"cursor\":\"before\"}");
        row.setSuspendReason(ScheduleSuspendReason.MIGRATION_ERROR);
        row.setSuspendCode("definition.invalid");
        row.setSuspendDetailJson("{\"field\":\"query\"}");
        mapper.insert(row);
        mapper.upsertPendingWork(pending(row.getId(), "fixture.work", "001"));
        mapper.upsertPendingWork(pending(row.getId(), "other.work", "001"));

        ScheduleTaskDefinitionUpdate update = new ScheduleTaskDefinitionUpdate(
                "编辑后", "fixture-source-2", "fixture-plugin", "fixture.definition", 2,
                "{\"query\":{\"mode\":\"changed\"},\"v\":2}", "{}",
                ScheduledTask.TRIGGER_CRON, null, "0 0 * * * *", 2_000L);
        assertThat(store.updateDefinition(row.getId(), 0L, update)).hasValue(1L);

        ScheduledTask changed = mapper.findById(row.getId());
        assertThat(changed.name()).isEqualTo("编辑后");
        assertThat(changed.sourceType()).isEqualTo("fixture-source-2");
        assertThat(changed.checkpointSchema()).isNull();
        assertThat(changed.checkpointVersion()).isNull();
        assertThat(changed.checkpointJson()).isNull();
        assertThat(changed.suspendReason()).isNull();
        assertThat(changed.suspendCode()).isNull();
        assertThat(changed.suspendDetailJson()).isNull();
        assertThat(mapper.listPendingWork(row.getId())).isEmpty();
    }

    @Test
    @DisplayName("definition CAS 编辑保留人工与运行策略挂起")
    void definitionEditPreservesNonDefinitionSuspension() {
        ScheduledTaskInsertRow row = sample("人工挂起");
        row.setSuspendReason(ScheduleSuspendReason.MANUAL);
        row.setSuspendCode("admin.pause");
        row.setSuspendDetailJson("{\"by\":\"admin\"}");
        mapper.insert(row);
        ScheduleTaskDefinitionUpdate update = new ScheduleTaskDefinitionUpdate(
                "仍然挂起", "fixture-source", "fixture-plugin", "fixture.definition", 2,
                "{\"query\":{\"mode\":\"changed\"}}", "{}",
                ScheduledTask.TRIGGER_INTERVAL, 30, null, 2_000L);

        assertThat(store.updateDefinition(row.getId(), 0L, update)).hasValue(1L);
        ScheduledTask changed = mapper.findById(row.getId());
        assertThat(changed.suspendReason()).isEqualTo(ScheduleSuspendReason.MANUAL);
        assertThat(changed.suspendCode()).isEqualTo("admin.pause");
        assertThat(changed.suspendDetailJson()).isEqualTo("{\"by\":\"admin\"}");
    }

    @Test
    @DisplayName("definition 版本不匹配时不改定义也不误删 pending")
    void staleDefinitionEditKeepsAggregateUntouched() {
        ScheduledTaskInsertRow row = sample("原定义");
        mapper.insert(row);
        mapper.upsertPendingWork(pending(row.getId(), "fixture.work", "001"));
        ScheduleTaskDefinitionUpdate update = new ScheduleTaskDefinitionUpdate(
                "错误覆盖", "fixture-source", "fixture-plugin", "fixture.definition", 2,
                "{}", "{}", ScheduledTask.TRIGGER_INTERVAL, 30, null, 2_000L);

        assertThat(store.updateDefinition(row.getId(), 99L, update)).isEmpty();
        assertThat(mapper.findById(row.getId()).name()).isEqualTo("原定义");
        assertThat(mapper.listPendingWork(row.getId())).hasSize(1);
    }

    @Test
    @DisplayName("credential 绑定、策略状态 CAS 与移除都推进 task stateVersion，secret 不进元数据")
    void credentialLifecycleUsesTaskVersionAndDedicatedSecretScalar() {
        ScheduledTaskInsertRow row = sample("凭证生命周期");
        mapper.insert(row);

        OptionalLong bound = store.bindCredential(row.getId(), 0L,
                "fixture-plugin", "fixture-policy", "account-7", "{}",
                "secret-value", null, 2_000L);
        assertThat(bound).hasValue(1L);
        ScheduledTaskCredential metadata = store.findCredentialMetadata(row.getId());
        assertThat(metadata.policyStateJson()).isEqualTo("{}");
        assertThat(metadata.toString()).doesNotContain("secret-value");
        assertThat(store.findCredentialSecret(row.getId(), "fixture-plugin", "fixture-policy"))
                .isEqualTo("secret-value");

        assertThat(store.updateCredentialPolicyState(row.getId(), 0L,
                "fixture-plugin", "fixture-policy", "{}", "{\"ack\":9}", 3_000L)).isEmpty();
        assertThat(store.updateCredentialPolicyState(row.getId(), 1L,
                "fixture-plugin", "fixture-policy", "{}", "{\"ack\":9}", 3_000L))
                .hasValue(2L);
        assertThat(store.findCredentialMetadata(row.getId()).policyStateJson())
                .isEqualTo("{\"ack\":9}");
        assertThat(store.findCredentialSecret(row.getId(), "fixture-plugin", "fixture-policy"))
                .isEqualTo("secret-value");

        assertThat(store.removeCredential(row.getId(), 2L,
                "fixture-plugin", "other-policy")).isEmpty();
        assertThat(store.removeCredential(row.getId(), 2L,
                "fixture-plugin", "fixture-policy")).hasValue(3L);
        assertThat(store.findCredentialMetadata(row.getId())).isNull();
        assertThat(store.findCredentialSecret(row.getId(), "fixture-plugin", "fixture-policy")).isNull();
    }

    @Test
    @DisplayName("管理员清 pending 与 durable claim 争用同一 stateVersion CAS")
    void pendingClearAndRunClaimAreMutuallyExclusive() {
        ScheduledTaskInsertRow clearedFirst = sample("先清 pending");
        mapper.insert(clearedFirst);
        mapper.upsertPendingWork(pending(clearedFirst.getId(), "fixture.work", "001/路径?'\"#_%"));

        assertThat(store.clearPendingWork(
                clearedFirst.getId(), 0L, "fixture.work", "001/路径?'\"#_%"))
                .hasValue(1L);
        assertThat(mapper.listPendingWork(clearedFirst.getId())).isEmpty();
        assertThat(store.tryQueueNow(clearedFirst.getId(), 0L, "stale-claim")).isEmpty();
        assertThat(store.tryQueueNow(clearedFirst.getId(), 1L, "fresh-claim")).isPresent();

        ScheduledTaskInsertRow claimedFirst = sample("先认领运行");
        mapper.insert(claimedFirst);
        mapper.upsertPendingWork(pending(claimedFirst.getId(), "fixture.work", "same/id"));
        assertThat(store.tryQueueNow(claimedFirst.getId(), 0L, "active-claim")).isPresent();

        assertThat(store.clearPendingWork(
                claimedFirst.getId(), 0L, "fixture.work", "same/id")).isEmpty();
        assertThat(mapper.listPendingWork(claimedFirst.getId()))
                .extracting(ScheduledPendingWork::workId)
                .containsExactly("same/id");
    }

    @Test
    @DisplayName("清 pending 失败应回滚已推进的 stateVersion")
    void pendingClearRollsBackVersionWhenDeleteFails() {
        ScheduledTaskInsertRow row = sample("清 pending 回滚");
        mapper.insert(row);
        mapper.upsertPendingWork(pending(row.getId(), "fixture.work", "001/路径?'\"#_%"));
        ScheduledTaskStoreImpl faulting = transactionalStore(faultingMapper("deletePendingWork"));

        assertThatThrownBy(() -> faulting.clearPendingWork(
                row.getId(), 0L, "fixture.work", "001/路径?'\"#_%"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fixture mapper failure");

        assertThat(mapper.findById(row.getId()).stateVersion()).isZero();
        assertThat(mapper.listPendingWork(row.getId()))
                .extracting(ScheduledPendingWork::workId)
                .containsExactly("001/路径?'\"#_%");
    }

    @Test
    @DisplayName("definition 写入后清 pending 失败应回滚整个聚合")
    void definitionEditRollsBackWhenPendingDeleteFails() {
        ScheduledTaskInsertRow row = sample("回滚前定义");
        row.setCheckpointSchema("fixture.checkpoint");
        row.setCheckpointVersion(1);
        row.setCheckpointJson("{\"cursor\":\"kept\"}");
        mapper.insert(row);
        mapper.upsertPendingWork(pending(row.getId(), "fixture.work", "001"));
        ScheduleTaskDefinitionUpdate update = new ScheduleTaskDefinitionUpdate(
                "不应留下", "fixture-source-2", "fixture-plugin", "fixture.definition", 2,
                "{\"changed\":true}", "{}", ScheduledTask.TRIGGER_INTERVAL, 5, null, 2_000L);

        ScheduledTaskStoreImpl faulting = transactionalStore(faultingMapper("deleteAllPendingWork"));
        assertThatThrownBy(() -> faulting.updateDefinition(row.getId(), 0L, update))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fixture mapper failure");

        ScheduledTask unchanged = mapper.findById(row.getId());
        assertThat(unchanged.name()).isEqualTo("回滚前定义");
        assertThat(unchanged.stateVersion()).isZero();
        assertThat(unchanged.checkpointJson()).isEqualTo("{\"cursor\":\"kept\"}");
        assertThat(mapper.listPendingWork(row.getId())).hasSize(1);
    }

    @Test
    @DisplayName("deleteAggregate 按 stateVersion 清任务、credential、新 pending 与旧迁移源 pending")
    void deletesWholeAggregateOnlyOnMatchingVersion() {
        ScheduledTaskInsertRow row = sample("聚合删除");
        mapper.insert(row);
        assertThat(store.bindCredential(row.getId(), 0L,
                "fixture-plugin", "fixture-policy", "account-8", "{}",
                "secret", null, 2_000L)).hasValue(1L);
        mapper.upsertPendingWork(pending(row.getId(), "fixture.work", "001"));
        jdbc.update("INSERT INTO scheduled_task_pending(task_id, work_id, reason, attempts) VALUES(?,?,?,?)",
                row.getId(), 77L, "legacy", 0);

        assertThat(store.deleteAggregate(row.getId(), 0L)).isFalse();
        assertThat(mapper.findById(row.getId())).isNotNull();
        assertThat(store.deleteAggregate(row.getId(), 1L)).isTrue();
        assertThat(mapper.findById(row.getId())).isNull();
        assertThat(mapper.listPendingWork(row.getId())).isEmpty();
        assertThat(mapper.findCredentialMetadata(row.getId())).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM scheduled_task_pending WHERE task_id = ?", Integer.class, row.getId()))
                .isZero();
    }

    @Test
    @DisplayName("聚合删除末尾失败应恢复任务、凭证和两类 pending")
    void deleteAggregateRollsBackAllChildrenOnFailure() {
        ScheduledTaskInsertRow row = sample("删除回滚");
        mapper.insert(row);
        assertThat(store.bindCredential(row.getId(), 0L,
                "fixture-plugin", "fixture-policy", "account-rollback", "{}",
                "secret", null, 2_000L)).hasValue(1L);
        mapper.upsertPendingWork(pending(row.getId(), "fixture.work", "001"));
        jdbc.update("INSERT INTO scheduled_task_pending(task_id, work_id, reason, attempts) VALUES(?,?,?,?)",
                row.getId(), 91L, "legacy", 0);

        ScheduledTaskStoreImpl faulting = transactionalStore(faultingMapper("deleteCredentialByTask"));
        assertThatThrownBy(() -> faulting.deleteAggregate(row.getId(), 1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fixture mapper failure");

        assertThat(mapper.findById(row.getId())).isNotNull();
        assertThat(mapper.listPendingWork(row.getId())).hasSize(1);
        assertThat(mapper.findCredentialMetadata(row.getId())).isNotNull();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM scheduled_task_pending WHERE task_id = ?", Integer.class, row.getId()))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("凭证绑定、恢复与策略挂起组合失败应由真实外层事务整体回滚")
    void credentialBindResumeAndSuspendRollBackTogether() {
        ScheduledTaskInsertRow row = sample("凭证组合回滚");
        row.setSuspendReason(ScheduleSuspendReason.CREDENTIAL);
        row.setSuspendCode("COOKIE_DEAD");
        mapper.insert(row);
        ScheduledTaskStoreImpl faulting = transactionalStore(faultingMapper("suspend"));

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            OptionalLong bound = faulting.bindCredential(row.getId(), 0L,
                    "fixture-plugin", "fixture-policy", "account-rollback", "{}",
                    "new-secret", "fixture-reference", 2_000L);
            assertThat(bound).hasValue(1L);
            assertThat(faulting.resume(row.getId(), 1L,
                    ScheduleSuspendReason.CREDENTIAL, "COOKIE_DEAD", 3_000L)).hasValue(2L);
            faulting.suspend(row.getId(), 2L,
                    ScheduleSuspendReason.POLICY, "POLICY_BLOCK", "{}");
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fixture mapper failure");

        ScheduledTask unchanged = mapper.findById(row.getId());
        assertThat(unchanged.stateVersion()).isZero();
        assertThat(unchanged.suspendReason()).isEqualTo(ScheduleSuspendReason.CREDENTIAL);
        assertThat(unchanged.suspendCode()).isEqualTo("COOKIE_DEAD");
        assertThat(mapper.findCredentialMetadata(row.getId())).isNull();
    }

    @Test
    @DisplayName("未完成迁移的 storageVersion=0 任务仍可按版本聚合删除")
    void deletesRejectedLegacyAggregate() {
        ScheduledTaskInsertRow row = sample("迁移失败待处理");
        row.setStorageVersion(ScheduledTask.LEGACY_STORAGE_VERSION);
        mapper.insert(row);
        jdbc.update("INSERT INTO scheduled_task_pending(task_id, work_id, reason, attempts) VALUES(?,?,?,?)",
                row.getId(), 88L, "unmapped", 0);

        assertThat(store.deleteAggregate(row.getId(), 0L)).isTrue();
        assertThat(mapper.findById(row.getId())).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM scheduled_task_pending WHERE task_id = ?", Integer.class, row.getId()))
                .isZero();
    }

    private ScheduledTaskStoreImpl transactionalStore(ScheduledTaskMapper targetMapper) {
        ScheduledTaskStoreImpl target = new ScheduledTaskStoreImpl(targetMapper, initializer);
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvice(new TransactionInterceptor(
                transactionManager, new AnnotationTransactionAttributeSource()));
        return (ScheduledTaskStoreImpl) proxyFactory.getProxy();
    }

    private ScheduledTaskMapper faultingMapper(String failingMethod) {
        return (ScheduledTaskMapper) Proxy.newProxyInstance(
                ScheduledTaskMapper.class.getClassLoader(),
                new Class<?>[]{ScheduledTaskMapper.class},
                (proxy, method, args) -> {
                    if (method.getName().equals(failingMethod)) {
                        throw new IllegalStateException("fixture mapper failure: " + failingMethod);
                    }
                    try {
                        return method.invoke(mapper, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private ScheduledTaskMapper missingGeneratedIdMapper() {
        return (ScheduledTaskMapper) Proxy.newProxyInstance(
                ScheduledTaskMapper.class.getClassLoader(),
                new Class<?>[]{ScheduledTaskMapper.class},
                (proxy, method, args) -> {
                    try {
                        Object result = method.invoke(mapper, args);
                        if (method.getName().equals("insert")) {
                            ((ScheduledTaskInsertRow) args[0]).setId(null);
                        }
                        return result;
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private ScheduledTaskCreate create(String name) {
        return new ScheduledTaskCreate(
                name,
                "fixture-source",
                "fixture-plugin",
                "fixture.definition",
                1,
                "{\"query\":{\"mode\":\"fixture\"}}",
                "{}",
                ScheduledTask.TRIGGER_INTERVAL,
                60,
                null,
                1_000L,
                1_700_000_000_000L);
    }

    private ScheduledTaskInsertRow sample(String name) {
        ScheduledTaskInsertRow row = new ScheduledTaskInsertRow();
        row.setName(name);
        row.setEnabled(true);
        row.setSourceType("fixture-source");
        row.setSourceOwnerPluginId("fixture-plugin");
        row.setDefinitionSchema("fixture.definition");
        row.setDefinitionVersion(1);
        row.setDefinitionJson("{\"query\":{\"mode\":\"fixture\"}}");
        row.setPresentationJson("{}");
        row.setTriggerKind(ScheduledTask.TRIGGER_INTERVAL);
        row.setIntervalMinutes(60);
        row.setNextRunTime(1_000L);
        row.setCreatedTime(1_700_000_000_000L);
        return row;
    }

    private ScheduledPendingWork pending(long taskId, String workType, String workId) {
        return new ScheduledPendingWork(taskId, workType, workId,
                "fixture.payload", 1, "{\"fixture\":true}", "[]", "{}",
                "retry", "{}", 0, 2_000L, 2_000L);
    }
}

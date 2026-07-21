package top.sywyar.pixivdownload.core.schedule.db.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import top.sywyar.pixivdownload.core.db.schema.DatabaseInitializer;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityOwner;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistry;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityReservation;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistryTestAccess;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleOwnerBundle;
import top.sywyar.pixivdownload.core.schedule.migration.LegacySchedulePersistenceDescriptor;
import top.sywyar.pixivdownload.core.schedule.migration.LegacySchedulePersistenceDescriptorProvider;
import top.sywyar.pixivdownload.core.schedule.migration.LegacyScheduledTaskMigrationAdapter;
import top.sywyar.pixivdownload.core.schedule.migration.LegacyScheduledCredentialPolicyTarget;
import top.sywyar.pixivdownload.core.schedule.migration.LegacyScheduledTaskMigrationResult;
import top.sywyar.pixivdownload.core.schedule.migration.LegacyScheduledTaskMigrationResult.Credential;
import top.sywyar.pixivdownload.core.schedule.migration.LegacyScheduledTaskMigrationResult.Migrated;
import top.sywyar.pixivdownload.core.schedule.migration.LegacyScheduledTaskMigrationResult.PendingWork;
import top.sywyar.pixivdownload.core.schedule.migration.LegacyScheduledTaskMigrationResult.Rejected;
import top.sywyar.pixivdownload.core.schedule.migration.LegacyScheduledTaskMigrationRoute;
import top.sywyar.pixivdownload.core.schedule.migration.LegacyScheduledTaskMigrationService;
import top.sywyar.pixivdownload.core.schedule.migration.LegacyScheduledTaskSnapshot;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialContext;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialPolicy;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialProbeResult;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionPlan;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledCheckpoint;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledDiscoveryResult;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceContext;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceDescriptor;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceExecutor;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourcePresentation;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskDefinition;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskPresentation;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWork;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkContext;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkExecutor;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkKey;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkPresentation;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkRelation;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkResult;
import top.sywyar.pixivdownload.plugin.registry.DatabaseSchemaRegistry;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("旧计划任务中性持久化迁移")
class LegacyScheduledTaskMigrationCoordinatorTest {

    private static final String FIXTURE = "/db/schedule/legacy-scheduled-tasks-v0.sql";
    private static final String OWNER = "fixture-schedule-owner";
    private static final String LEGACY_SECRET = "PHPSESSID=1001_legacy; device=fixture";
    private static final DatabaseSchemaRegistry REGISTRY = DatabaseSchemaRegistry.forBuiltInPlugins();
    private static final Map<String, LegacyScheduledTaskMigrationRoute> ROUTES = Map.ofEntries(
            Map.entry("USER_NEW", route("user-new")),
            Map.entry("USER_REQUEST", route("user-request")),
            Map.entry("SEARCH", route("search")),
            Map.entry("SERIES", route("series")),
            Map.entry("MY_BOOKMARKS", route("my-bookmarks")),
            Map.entry("FOLLOW_LATEST", route("follow-latest")),
            Map.entry("COLLECTION", route("collection")));

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("冻结旧库覆盖七种来源并连续迁移两次保持快照一致")
    void shouldMigrateFrozenLegacyFixtureIdempotently() throws Exception {
        try (Database database = openFixture("idempotent.db")) {
            AuthorizedMigration migration = database.authorized(ROUTES, taskId -> {});

            var first = migration.migrate(fixtureAdapter());

            assertThat(first.examined()).isEqualTo(7);
            assertThat(first.migrated()).isEqualTo(6);
            assertThat(first.rejected()).isEqualTo(1);
            assertThat(first.failed()).isZero();
            assertThat(database.jdbc.queryForList(
                    "SELECT type FROM scheduled_tasks WHERE storage_version = 1 ORDER BY id",
                    String.class)).containsExactly(
                    "user-new", "user-request", "search", "series", "my-bookmarks", "follow-latest");
            assertThat(database.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM scheduled_tasks WHERE storage_version = 1", Integer.class))
                    .isEqualTo(6);
            assertThat(database.jdbc.queryForObject(
                    "SELECT last_outcome FROM scheduled_tasks WHERE id = 2", String.class))
                    .isEqualTo("INTERRUPTED");
            assertThat(database.jdbc.queryForObject(
                    "SELECT last_outcome FROM scheduled_tasks WHERE id = 1", String.class))
                    .isEqualTo("INTERRUPTED");
            assertThat(database.jdbc.queryForObject(
                    "SELECT next_run_time FROM scheduled_tasks WHERE id = 1", Long.class)).isZero();
            assertThat(database.jdbc.queryForObject(
                    "SELECT enabled FROM scheduled_tasks WHERE id = 2", Integer.class)).isZero();
            assertThat(database.jdbc.queryForObject(
                    "SELECT next_run_time FROM scheduled_tasks WHERE id = 2", Long.class))
                    .isEqualTo(1700000200000L);
            assertThat(database.jdbc.queryForObject(
                    "SELECT suspend_reason FROM scheduled_tasks WHERE id = 3", String.class))
                    .isEqualTo("CREDENTIAL");
            assertThat(database.jdbc.queryForObject(
                    "SELECT suspend_reason FROM scheduled_tasks WHERE id = 4", String.class))
                    .isEqualTo("POLICY");
            assertThat(database.jdbc.queryForObject(
                    "SELECT suspend_code FROM scheduled_tasks WHERE id = 4", String.class))
                    .isEqualTo("FIXTURE_POLICY_OVERUSE");
            assertThat(database.jdbc.queryForObject(
                    "SELECT suspend_reason FROM scheduled_tasks WHERE id = 5", String.class))
                    .isEqualTo("MANUAL");
            assertThat(database.jdbc.queryForObject(
                    "SELECT suspend_reason FROM scheduled_tasks WHERE id = 6", String.class))
                    .isEqualTo("SOURCE_UNAVAILABLE");
            assertThat(database.jdbc.queryForObject(
                    "SELECT checkpoint_json FROM scheduled_tasks WHERE id = 1", String.class))
                    .isEqualTo("{\"watermarkId\":\"9007199254740991\"}");

            String migratedSecret = database.jdbc.queryForObject(
                    "SELECT secret FROM scheduled_task_credentials WHERE task_id = 1", String.class);
            assertThat(secretEquals(LEGACY_SECRET, migratedSecret)).isTrue();
            assertThat(database.jdbc.queryForObject(
                    "SELECT cookie_snapshot FROM scheduled_tasks WHERE id = 1", String.class)).isNull();
            assertThat(database.jdbc.queryForObject(
                    "SELECT policy_state_json FROM scheduled_task_credentials WHERE task_id = 4", String.class))
                    .isEqualTo("{\"schema\":\"fixture.credential-policy-state\",\"version\":1,"
                            + "\"acknowledgedWarningTime\":\"1700000004000\"}");
            assertThat(database.jdbc.queryForList(
                    "SELECT task_id || ':' || work_type || ':' || work_id"
                            + " FROM scheduled_task_pending_work ORDER BY task_id",
                    String.class)).containsExactly(
                    "1:illust:9007199254740991", "5:novel:42");
            assertThat(database.jdbc.queryForObject(
                    "SELECT relations_json FROM scheduled_task_pending_work WHERE task_id = 1", String.class))
                    .contains("legacy-source");

            assertThat(database.jdbc.queryForObject(
                    "SELECT storage_version FROM scheduled_tasks WHERE id = 7", Integer.class)).isZero();
            assertThat(database.jdbc.queryForObject(
                    "SELECT suspend_reason FROM scheduled_tasks WHERE id = 7", String.class))
                    .isEqualTo("MIGRATION_ERROR");
            assertThat(database.jdbc.queryForObject(
                    "SELECT suspend_code FROM scheduled_tasks WHERE id = 7", String.class))
                    .isEqualTo("AMBIGUOUS_LEGACY_WORK_TYPE");
            assertThat(database.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM scheduled_task_pending WHERE task_id = 7", Integer.class))
                    .isEqualTo(1);
            String rejectedSecret = database.jdbc.queryForObject(
                    "SELECT cookie_snapshot FROM scheduled_tasks WHERE id = 7", String.class);
            assertThat(secretEquals("PHPSESSID=7007_collection", rejectedSecret)).isTrue();

            DatabaseSnapshot afterFirst = database.snapshot();
            var second = migration.migrate(fixtureAdapter());
            DatabaseSnapshot afterSecond = database.snapshot();

            assertThat(second.examined()).isEqualTo(1);
            assertThat(second.migrated()).isZero();
            assertThat(second.rejected()).isEqualTo(1);
            assertThat(second.failed()).isZero();
            assertThat(afterSecond).isEqualTo(afterFirst);
        }
    }

    @Test
    @DisplayName("真实事务在中性写入后故障应回滚且不泄漏旧凭证")
    void shouldRollbackRealTransactionAfterInjectedFailure() throws Exception {
        try (Database database = openFixture("rollback.db")) {
            AuthorizedMigration migration = database.authorized(
                    Map.of("USER_NEW", route("user-new")), taskId -> {
                if (taskId == 1) {
                    throw new IllegalStateException("fixture plugin failure");
                }
            });
            LegacyScheduledTaskMigrationAdapter adapter = fixtureAdapter();

            var first = migration.migrate(adapter);

            assertThat(first.failed()).isEqualTo(1);
            assertThat(database.jdbc.queryForObject(
                    "SELECT type FROM scheduled_tasks WHERE id = 1", String.class)).isEqualTo("USER_NEW");
            assertThat(database.jdbc.queryForObject(
                    "SELECT storage_version FROM scheduled_tasks WHERE id = 1", Integer.class)).isZero();
            assertThat(database.jdbc.queryForObject(
                    "SELECT suspend_reason FROM scheduled_tasks WHERE id = 1", String.class))
                    .isEqualTo("MIGRATION_ERROR");
            assertThat(database.jdbc.queryForObject(
                    "SELECT suspend_code FROM scheduled_tasks WHERE id = 1", String.class))
                    .isEqualTo("ADAPTER_FAILURE");
            assertThat(database.jdbc.queryForObject(
                    "SELECT suspend_detail_json FROM scheduled_tasks WHERE id = 1", String.class))
                    .isEqualTo("{}");
            assertThat(database.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM scheduled_task_credentials WHERE task_id = 1", Integer.class))
                    .isZero();
            assertThat(database.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM scheduled_task_pending_work WHERE task_id = 1", Integer.class))
                    .isZero();
            assertThat(database.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM scheduled_task_pending WHERE task_id = 1", Integer.class))
                    .isEqualTo(1);
            String secretAfterRollback = database.jdbc.queryForObject(
                    "SELECT cookie_snapshot FROM scheduled_tasks WHERE id = 1", String.class);
            assertThat(secretEquals(LEGACY_SECRET, secretAfterRollback)).isTrue();
            assertThat(database.snapshot().toString()).doesNotContain("fixture plugin failure");
            assertThat(List.of(first.getClass().getRecordComponents()))
                    .allMatch(component -> !Throwable.class.isAssignableFrom(component.getType())
                            && !LegacyScheduledTaskMigrationAdapter.class.isAssignableFrom(component.getType()));
            assertThat(List.of(LegacyScheduledTaskMigrationCoordinator.class.getDeclaredFields()))
                    .allMatch(field -> !Throwable.class.isAssignableFrom(field.getType())
                            && !LegacyScheduledTaskMigrationAdapter.class.isAssignableFrom(field.getType()));

            DatabaseSnapshot afterFirst = database.snapshot();
            var second = migration.migrate(adapter);
            assertThat(second.failed()).isEqualTo(1);
            assertThat(database.snapshot()).isEqualTo(afterFirst);
            assertThat(database.jdbc.queryForObject(
                    "SELECT state_version FROM scheduled_tasks WHERE id = 1", Integer.class)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("同一 owner 前一任务失败回滚后后一任务仍在独立事务成功迁移")
    void shouldIsolateEachTaskTransactionWithinOwnerMigration() throws Exception {
        try (Database database = openFixture("per-task-isolation.db")) {
            String secondSecret = "PHPSESSID=8008_matrix";
            database.jdbc.update(
                    "INSERT INTO scheduled_tasks(id, name, enabled, type, params_json, trigger_kind,"
                            + " interval_minutes, cron_expr, cookie_mode, cookie_snapshot, proxy_snapshot,"
                            + " next_run_time, last_run_time, last_status, last_message, watermark_id,"
                            + " run_started_time, account_id, ack_warning_time, pending_retry_armed,"
                            + " created_time, storage_version)"
                            + " VALUES(8, ?, 1, 'USER_NEW', ?, 'interval', 30, NULL, 'bound', ?, NULL,"
                            + " 1700000800000, NULL, NULL, NULL, 808, NULL, '8008', NULL, 1, ?, 0)",
                    "second legacy task",
                    "{\"kind\":\"illust\",\"source\":{\"userId\":\"8008\"}}",
                    secondSecret,
                    1699999008000L);
            database.jdbc.update(
                    "INSERT INTO scheduled_task_pending(task_id, work_id, reason, attempts,"
                            + " first_seen_time, last_attempt_time) VALUES(8, 8008, 'network', 1, 10, 20)");
            AuthorizedMigration migration = database.authorized(
                    Map.of("USER_NEW", route("user-new")), taskId -> {
                if (taskId == 1L) {
                    throw new IllegalStateException("first task fault");
                }
            });

            var report = migration.migrate(fixtureAdapter());

            assertThat(report.examined()).isEqualTo(2);
            assertThat(report.migrated()).isEqualTo(1);
            assertThat(report.failed()).isEqualTo(1);
            assertLegacyMigrationInputPreserved(database, 1L, "ADAPTER_FAILURE");
            assertThat(database.jdbc.queryForObject(
                    "SELECT storage_version FROM scheduled_tasks WHERE id = 8", Integer.class)).isEqualTo(1);
            assertThat(database.jdbc.queryForObject(
                    "SELECT cookie_snapshot FROM scheduled_tasks WHERE id = 8", String.class)).isNull();
            assertThat(database.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM scheduled_task_pending WHERE task_id = 8", Integer.class)).isZero();
            assertThat(database.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM scheduled_task_pending_work WHERE task_id = 8", Integer.class))
                    .isEqualTo(1);
            String migratedSecondSecret = database.jdbc.queryForObject(
                    "SELECT secret FROM scheduled_task_credentials WHERE task_id = 8", String.class);
            assertThat(secretEquals(secondSecret, migratedSecondSecret)).isTrue();
        }
    }

    @Test
    @DisplayName("宿主发布快照应拒绝适配器返回的跨 owner 来源类型")
    void shouldRejectCanonicalTypeOutsideOwnerPublication() throws Exception {
        try (Database database = openFixture("owner-boundary.db")) {
            LegacyScheduledTaskMigrationAdapter adapter = snapshot -> new Migrated(
                    definition(snapshot, "foreign-source"), null, credential(snapshot), List.of(), null);

            var report = database.authorized(
                    Map.of("USER_REQUEST", route("user-request")), taskId -> {}).migrate(adapter);

            assertThat(report.failed()).isEqualTo(1);
            assertThat(database.jdbc.queryForObject(
                    "SELECT type FROM scheduled_tasks WHERE id = 2", String.class)).isEqualTo("USER_REQUEST");
            assertThat(database.jdbc.queryForObject(
                    "SELECT suspend_code FROM scheduled_tasks WHERE id = 2", String.class))
                    .isEqualTo("ADAPTER_FAILURE");
        }
    }

    @Test
    @DisplayName("错误定义 schema、版本或作品类型都不得清理旧迁移输入")
    void shouldPreserveLegacyDataForPlansOutsidePersistenceSpec() throws Exception {
        assertInvalidPersistencePlanPreservesLegacy("wrong-schema.db", "wrong.definition", 1, "illust");
        assertInvalidPersistencePlanPreservesLegacy("wrong-version.db", "fixture.definition", 2, "illust");
        assertInvalidPersistencePlanPreservesLegacy("wrong-work-type.db", "fixture.definition", 1,
                "foreign.work");
    }

    @Test
    @DisplayName("错误凭证 owner 或未声明 policy 不得清理旧凭证与 pending")
    void shouldPreserveLegacyDataForCredentialsOutsidePersistenceSpec() throws Exception {
        assertInvalidCredentialPlanPreservesLegacy(
                "wrong-credential-owner.db", "foreign-owner", "fixture-cookie");
        assertInvalidCredentialPlanPreservesLegacy(
                "wrong-credential-policy.db", OWNER, "foreign-cookie");
    }

    @Test
    @DisplayName("宿主盖章的跨 owner 凭证策略可迁移且持久化真实策略 owner")
    void shouldMigrateCredentialOwnedByAnotherPublishedOwner() throws Exception {
        String policyOwner = "fixture-credential-owner";
        try (Database database = openFixture("cross-owner-credential.db")) {
            LegacyScheduledTaskMigrationAdapter adapter = snapshot -> {
                Credential original = credential(snapshot);
                Credential external = new Credential(
                        policyOwner, original.policyId(), original.accountKey(),
                        original.secretReference(), original.policyStateJson(), original.updatedTime());
                List<PendingWork> pending = snapshot.pending().stream()
                        .map(row -> pending(snapshot, row, "illust"))
                        .toList();
                return new Migrated(definition(snapshot, "user-new"), null, external, pending, null);
            };

            var report = database.authorized(
                    Map.of("USER_NEW", route("user-new", policyOwner)), taskId -> {}).migrate(adapter);

            assertThat(report.migrated()).isEqualTo(1);
            assertThat(report.failed()).isZero();
            assertThat(database.jdbc.queryForObject(
                    "SELECT policy_owner_plugin_id FROM scheduled_task_credentials WHERE task_id = 1",
                    String.class)).isEqualTo(policyOwner);
            assertThat(database.jdbc.queryForObject(
                    "SELECT storage_version FROM scheduled_tasks WHERE id = 1", Integer.class)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("等值伪造、错误 registry 与过期 reservation 都不得触碰旧 secret")
    void shouldRejectForgedForeignAndExpiredReservationsBeforeReadingLegacyData() throws Exception {
        try (Database database = openFixture("reservation-authorization.db")) {
            Map<String, LegacyScheduledTaskMigrationRoute> routes =
                    Map.of("USER_NEW", route("user-new"));
            AuthorizedMigration authorized = database.authorized(routes, taskId -> {});
            AuthorizedMigration otherRegistry = database.authorized(routes, taskId -> {});
            AtomicBoolean adapterCalled = new AtomicBoolean();
            LegacyScheduledTaskMigrationAdapter adapter = snapshot -> {
                adapterCalled.set(true);
                return fixtureAdapter().migrate(snapshot);
            };

            ScheduleCapabilityReservation forged =
                    ScheduleCapabilityRegistryTestAccess.equivalent(authorized.reservation());
            assertThatThrownBy(() -> authorized.coordinator()
                    .migrateReservedOwner(forged, adapter))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("unknown schedule capability reservation");
            assertThatThrownBy(() -> otherRegistry.coordinator()
                    .migrateReservedOwner(authorized.reservation(), adapter))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("unknown schedule capability reservation");
            assertThat(ScheduleCapabilityRegistryTestAccess.release(
                    authorized.registry(), authorized.reservation())).isTrue();
            assertThatThrownBy(() -> authorized.coordinator()
                    .migrateReservedOwner(authorized.reservation(), adapter))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("unknown schedule capability reservation");

            assertThat(adapterCalled).isFalse();
            assertThat(database.jdbc.queryForObject(
                    "SELECT storage_version FROM scheduled_tasks WHERE id = 1", Integer.class)).isZero();
            String secret = database.jdbc.queryForObject(
                    "SELECT cookie_snapshot FROM scheduled_tasks WHERE id = 1", String.class);
            assertThat(secretEquals(LEGACY_SECRET, secret)).isTrue();
            assertThat(database.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM scheduled_task_pending WHERE task_id = 1", Integer.class))
                    .isEqualTo(1);
            assertThat(database.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM scheduled_task_credentials WHERE task_id = 1", Integer.class))
                    .isZero();
            assertThat(database.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM scheduled_task_pending_work WHERE task_id = 1", Integer.class))
                    .isZero();
        }
    }

    private Database openFixture(String fileName) throws Exception {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve(fileName).toAbsolutePath());
        dataSource.setSuppressClose(true);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        executeScript(dataSource, readFixture());
        new DatabaseInitializer(jdbc, REGISTRY.contributions(), REGISTRY.mergedSchema(),
                TestI18nBeans.appMessages(), event -> {}).initialize();
        return new Database(dataSource, jdbc);
    }

    private void assertInvalidPersistencePlanPreservesLegacy(
            String fileName, String definitionSchema, int definitionVersion, String workType)
            throws Exception {
        try (Database database = openFixture(fileName)) {
            LegacyScheduledTaskMigrationAdapter adapter = snapshot -> {
                ScheduledTaskDefinition definition = new ScheduledTaskDefinition(
                        snapshot.id(), "user-new", definitionSchema, definitionVersion,
                        snapshot.definitionJson(), ScheduledTaskPresentation.empty());
                List<PendingWork> pending = snapshot.pending().stream()
                        .map(row -> pending(snapshot, row, workType))
                        .toList();
                return new Migrated(definition, null, credential(snapshot), pending, null);
            };

            var report = database.authorized(
                    Map.of("USER_NEW", route("user-new")), taskId -> {}).migrate(adapter);

            assertThat(report.failed()).isEqualTo(1);
            assertLegacyMigrationInputPreserved(database, 1L, "ADAPTER_FAILURE");
        }
    }

    private void assertInvalidCredentialPlanPreservesLegacy(
            String fileName, String policyOwnerPluginId, String policyId) throws Exception {
        try (Database database = openFixture(fileName)) {
            LegacyScheduledTaskMigrationAdapter adapter = snapshot -> {
                ScheduledTaskDefinition definition = definition(snapshot, "user-new");
                List<PendingWork> pending = snapshot.pending().stream()
                        .map(row -> pending(snapshot, row, "illust"))
                        .toList();
                Credential valid = credential(snapshot);
                Credential invalid = new Credential(
                        policyOwnerPluginId, policyId, valid.accountKey(), valid.secretReference(),
                        valid.policyStateJson(), valid.updatedTime());
                return new Migrated(definition, null, invalid, pending, null);
            };

            var report = database.authorized(
                    Map.of("USER_NEW", route("user-new")), taskId -> {}).migrate(adapter);

            assertThat(report.failed()).isEqualTo(1);
            assertLegacyMigrationInputPreserved(database, 1L, "ADAPTER_FAILURE");
        }
    }

    private void assertLegacyMigrationInputPreserved(Database database, long taskId, String code) {
        assertThat(database.jdbc.queryForObject(
                "SELECT storage_version FROM scheduled_tasks WHERE id = ?", Integer.class, taskId))
                .isZero();
        assertThat(database.jdbc.queryForObject(
                "SELECT suspend_reason FROM scheduled_tasks WHERE id = ?", String.class, taskId))
                .isEqualTo("MIGRATION_ERROR");
        assertThat(database.jdbc.queryForObject(
                "SELECT suspend_code FROM scheduled_tasks WHERE id = ?", String.class, taskId))
                .isEqualTo(code);
        String secret = database.jdbc.queryForObject(
                "SELECT cookie_snapshot FROM scheduled_tasks WHERE id = ?", String.class, taskId);
        assertThat(secretEquals(LEGACY_SECRET, secret)).isTrue();
        assertThat(database.jdbc.queryForObject(
                "SELECT COUNT(*) FROM scheduled_task_pending WHERE task_id = ?", Integer.class, taskId))
                .isEqualTo(1);
        assertThat(database.jdbc.queryForObject(
                "SELECT COUNT(*) FROM scheduled_task_pending_work WHERE task_id = ?", Integer.class, taskId))
                .isZero();
        assertThat(database.jdbc.queryForObject(
                "SELECT COUNT(*) FROM scheduled_task_credentials WHERE task_id = ?", Integer.class, taskId))
                .isZero();
    }

    private LegacyScheduledTaskMigrationAdapter fixtureAdapter() {
        return snapshot -> {
            assertThat(snapshot.toString()).doesNotContain("PHPSESSID", "device=fixture");
            if ("COLLECTION".equals(snapshot.sourceType()) && !snapshot.pending().isEmpty()) {
                return new Rejected("AMBIGUOUS_LEGACY_WORK_TYPE",
                        "{\"legacySourceType\":\"COLLECTION\",\"pendingCount\":"
                                + snapshot.pending().size() + "}");
            }
            String canonical = ROUTES.get(snapshot.sourceType()).canonicalSourceType();
            ScheduledCheckpoint checkpoint = snapshot.watermarkId() == null ? null
                    : new ScheduledCheckpoint("fixture.checkpoint", 1,
                    "{\"watermarkId\":\"" + snapshot.watermarkId() + "\"}");
            List<PendingWork> pending = snapshot.pending().stream()
                    .map(row -> pending(snapshot, row))
                    .toList();
            String canonicalSuspendCode = "OVERUSE_PAUSED".equals(snapshot.lastStatus())
                    ? "FIXTURE_POLICY_OVERUSE" : null;
            return new Migrated(definition(snapshot, canonical), checkpoint, credential(snapshot), pending,
                    canonicalSuspendCode);
        };
    }

    private static LegacyScheduledTaskMigrationRoute route(String canonicalSourceType) {
        return route(canonicalSourceType, OWNER);
    }

    private static LegacyScheduledTaskMigrationRoute route(
            String canonicalSourceType, String credentialPolicyOwner) {
        return LegacyScheduledTaskMigrationRoute.descriptorBound(
                canonicalSourceType, "fixture.definition", 1, Set.of("illust", "novel"),
                Set.of(new LegacyScheduledCredentialPolicyTarget(
                        "fixture-cookie", credentialPolicyOwner)));
    }

    private ScheduledTaskDefinition definition(LegacyScheduledTaskSnapshot snapshot, String canonical) {
        return new ScheduledTaskDefinition(snapshot.id(), canonical, "fixture.definition", 1,
                snapshot.definitionJson(),
                new ScheduledTaskPresentation(snapshot.name(), snapshot.sourceType(),
                        Map.of("legacyType", snapshot.sourceType())));
    }

    private Credential credential(LegacyScheduledTaskSnapshot snapshot) {
        if (!snapshot.hasLegacySecret()) {
            return null;
        }
        String policyState = snapshot.acknowledgedWarningTime() == null
                ? "{\"schema\":\"fixture.credential-policy-state\",\"version\":1}"
                : "{\"schema\":\"fixture.credential-policy-state\",\"version\":1,"
                + "\"acknowledgedWarningTime\":\"" + snapshot.acknowledgedWarningTime() + "\"}";
        return new Credential(OWNER, "fixture-cookie", snapshot.accountId(), null,
                policyState, snapshot.createdTime());
    }

    private PendingWork pending(LegacyScheduledTaskSnapshot snapshot,
                                LegacyScheduledTaskSnapshot.PendingRow row) {
        String workType = "MY_BOOKMARKS".equals(snapshot.sourceType()) ? "novel" : "illust";
        return pending(snapshot, row, workType);
    }

    private PendingWork pending(LegacyScheduledTaskSnapshot snapshot,
                                LegacyScheduledTaskSnapshot.PendingRow row,
                                String workType) {
        ScheduledWork work = new ScheduledWork(
                new ScheduledWorkKey(workType, row.workId()),
                "fixture.work", 1,
                "{\"workId\":\"" + row.workId() + "\"}",
                new ScheduledWorkPresentation("legacy " + row.workId(), null, null,
                        Map.of("legacy", "true")),
                List.of(new ScheduledWorkRelation("legacy-source", snapshot.sourceType(),
                        "fixture.relation", 1, "{}")));
        return new PendingWork(row.workId(), work, row.reason(),
                row.reason() == null ? null : "{\"legacyReason\":\"" + row.reason() + "\"}",
                row.attempts(), row.firstSeenTime(), row.lastAttemptTime());
    }

    private static String readFixture() throws IOException {
        try (InputStream input = LegacyScheduledTaskMigrationCoordinatorTest.class
                .getResourceAsStream(FIXTURE)) {
            if (input == null) {
                throw new IOException("missing fixture " + FIXTURE);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void executeScript(SingleConnectionDataSource dataSource, String script) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            for (String sql : splitSqlStatements(script)) {
                statement.executeUpdate(sql);
            }
        }
    }

    /** 仅按单引号外的分号切分，保留 fixture Cookie 值中的分号。 */
    private static List<String> splitSqlStatements(String script) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < script.length(); i++) {
            char ch = script.charAt(i);
            if (ch == '\'' && quoted && i + 1 < script.length() && script.charAt(i + 1) == '\'') {
                current.append(ch).append(ch);
                i++;
                continue;
            }
            if (ch == '\'') {
                quoted = !quoted;
            }
            if (ch == ';' && !quoted) {
                String sql = current.toString().trim();
                if (!sql.isEmpty()) {
                    statements.add(sql);
                }
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        String trailing = current.toString().trim();
        if (!trailing.isEmpty()) {
            statements.add(trailing);
        }
        return statements;
    }

    private static boolean secretEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return expected == null && actual == null;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private static String fingerprint(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private static ScheduleOwnerBundle migrationBundle(
            Map<String, LegacyScheduledTaskMigrationRoute> routes,
            Map<String, Set<String>> policyIdsByOwner) {
        Map<String, LegacyScheduledTaskMigrationRoute> byCanonical = new LinkedHashMap<>();
        Map<String, Set<String>> aliasesByCanonical = new LinkedHashMap<>();
        routes.forEach((alias, route) -> {
            LegacyScheduledTaskMigrationRoute previous =
                    byCanonical.putIfAbsent(route.canonicalSourceType(), route);
            if (previous != null && !previous.equals(route)) {
                throw new IllegalArgumentException("inconsistent test route: " + route.canonicalSourceType());
            }
            aliasesByCanonical.computeIfAbsent(
                    route.canonicalSourceType(), ignored -> new java.util.LinkedHashSet<>()).add(alias);
            route.allowedCredentialPolicies().forEach(target ->
                    policyIdsByOwner.computeIfAbsent(
                            target.policyOwnerPluginId(), ignored -> new java.util.LinkedHashSet<>())
                            .add(target.policyId()));
        });

        List<ScheduledSourceDescriptor> sourceDescriptors = new ArrayList<>();
        List<ScheduledSourceExecutor> sourceExecutors = new ArrayList<>();
        Set<String> workTypes = new LinkedHashSet<>();
        List<LegacySchedulePersistenceDescriptor> persistenceDescriptors = new ArrayList<>();
        byCanonical.forEach((canonical, route) -> {
            Set<String> aliases = Set.copyOf(aliasesByCanonical.get(canonical));
            Set<String> policyIds = route.allowedCredentialPolicies().stream()
                    .map(LegacyScheduledCredentialPolicyTarget::policyId)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            sourceDescriptors.add(sourceDescriptor(canonical, aliases, route, policyIds));
            sourceExecutors.add(sourceExecutor(canonical, route.allowedWorkTypes()));
            workTypes.addAll(route.allowedWorkTypes());
            persistenceDescriptors.add(new LegacySchedulePersistenceDescriptor(
                    canonical, route.definitionSchema(), route.definitionVersion(),
                    route.allowedWorkTypes(), policyIds));
        });
        List<ScheduledCredentialPolicy> localPolicies = policyIdsByOwner.getOrDefault(OWNER, Set.of()).stream()
                .map(LegacyScheduledTaskMigrationCoordinatorTest::credentialPolicy)
                .toList();
        List<LegacySchedulePersistenceDescriptorProvider> persistenceProviders =
                List.of(() -> List.copyOf(persistenceDescriptors));
        return ScheduleOwnerBundle.prepare(
                new ScheduleCapabilityOwner(OWNER, "fixture-package", 1L),
                sourceDescriptors,
                sourceExecutors,
                workTypes.stream().map(LegacyScheduledTaskMigrationCoordinatorTest::workExecutor).toList(),
                localPolicies,
                List.of(),
                persistenceProviders);
    }

    private static ScheduleOwnerBundle policyBundle(String owner, Set<String> policyIds) {
        return ScheduleOwnerBundle.prepare(
                new ScheduleCapabilityOwner(owner, owner + "-package", 1L),
                List.of(), List.of(), List.of(),
                policyIds.stream().map(LegacyScheduledTaskMigrationCoordinatorTest::credentialPolicy).toList(),
                List.of());
    }

    private static ScheduledSourceDescriptor sourceDescriptor(
            String sourceType,
            Set<String> aliases,
            LegacyScheduledTaskMigrationRoute route,
            Set<String> policyIds) {
        return new ScheduledSourceDescriptor(
                sourceType,
                aliases,
                route.definitionSchema(),
                route.definitionVersion(),
                new ScheduledSourcePresentation(
                        "schedule-test", "schedule.source.name", "schedule.source.description",
                        "schedule", "neutral"),
                Set.of("default"),
                route.allowedWorkTypes(),
                policyIds,
                Set.of(),
                null);
    }

    private static ScheduledSourceExecutor sourceExecutor(String sourceType, Set<String> workTypes) {
        return new ScheduledSourceExecutor() {
            @Override public String sourceType() { return sourceType; }
            @Override public ScheduledExecutionPlan plan(ScheduledTaskDefinition task) {
                return ScheduledExecutionPlan.credentialFree(workTypes);
            }
            @Override public ScheduledDiscoveryResult discover(ScheduledSourceContext context) {
                return ScheduledDiscoveryResult.withoutCheckpoint();
            }
        };
    }

    private static ScheduledWorkExecutor workExecutor(String workType) {
        return new ScheduledWorkExecutor() {
            @Override public String workType() { return workType; }
            @Override public ScheduledWorkResult execute(
                    ScheduledWork work, ScheduledWorkContext context) {
                return ScheduledWorkResult.completed();
            }
        };
    }

    private static ScheduledCredentialPolicy credentialPolicy(String policyId) {
        return new ScheduledCredentialPolicy() {
            @Override public String policyId() { return policyId; }
            @Override public ScheduledCredentialProbeResult probe(ScheduledCredentialContext context) {
                return ScheduledCredentialProbeResult.valid("fixture-account");
            }
        };
    }

    private record AuthorizedMigration(
            LegacyScheduledTaskMigrationCoordinator coordinator,
            ScheduleCapabilityRegistry registry,
            ScheduleCapabilityReservation reservation) {

        LegacyScheduledTaskMigrationService.OwnerMigrationReport migrate(
                LegacyScheduledTaskMigrationAdapter adapter) {
            return coordinator.migrateReservedOwner(reservation, adapter);
        }
    }

    private record Database(SingleConnectionDataSource dataSource, JdbcTemplate jdbc)
            implements AutoCloseable {

        AuthorizedMigration authorized(
                Map<String, LegacyScheduledTaskMigrationRoute> routes,
                LegacyScheduledTaskMigrationCoordinator.MigrationFaultInjector faultInjector) {
            ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
            Map<String, Set<String>> policyIdsByOwner = new LinkedHashMap<>();
            ScheduleOwnerBundle ownerBundle = migrationBundle(routes, policyIdsByOwner);
            policyIdsByOwner.forEach((policyOwner, policyIds) -> {
                if (!OWNER.equals(policyOwner)) {
                    ScheduleCapabilityRegistryTestAccess.publish(
                            registry, policyBundle(policyOwner, policyIds));
                }
            });
            ScheduleCapabilityReservation reservation =
                    ScheduleCapabilityRegistryTestAccess.reserve(registry, ownerBundle);
            DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
            LegacyScheduledTaskMigrationCoordinator coordinator =
                    new LegacyScheduledTaskMigrationCoordinator(jdbc, new ObjectMapper(),
                            new TransactionTemplate(transactionManager), faultInjector, registry);
            return new AuthorizedMigration(coordinator, registry, reservation);
        }

        DatabaseSnapshot snapshot() {
            return new DatabaseSnapshot(
                    sanitized(jdbc.queryForList("SELECT * FROM scheduled_tasks ORDER BY id")),
                    sanitized(jdbc.queryForList("SELECT * FROM scheduled_task_credentials ORDER BY task_id")),
                    sanitized(jdbc.queryForList(
                            "SELECT * FROM scheduled_task_pending ORDER BY task_id, work_id")),
                    sanitized(jdbc.queryForList(
                            "SELECT * FROM scheduled_task_pending_work ORDER BY task_id, work_type, work_id")));
        }

        private static List<Map<String, Object>> sanitized(List<Map<String, Object>> rows) {
            return rows.stream().map(row -> {
                Map<String, Object> safe = new LinkedHashMap<>(row);
                for (String sensitive : List.of("cookie_snapshot", "secret")) {
                    if (safe.containsKey(sensitive)) {
                        safe.put(sensitive, fingerprint(safe.get(sensitive)));
                    }
                }
                return Collections.unmodifiableMap(safe);
            }).toList();
        }

        @Override
        public void close() {
            dataSource.destroy();
        }
    }

    private record DatabaseSnapshot(
            List<Map<String, Object>> tasks,
            List<Map<String, Object>> credentials,
            List<Map<String, Object>> legacyPending,
            List<Map<String, Object>> pending
    ) {}
}

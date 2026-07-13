package top.sywyar.pixivdownload.schedule.persistence.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import top.sywyar.pixivdownload.core.schedule.migration.LegacyScheduledTaskMigrationResult;
import top.sywyar.pixivdownload.core.schedule.migration.LegacyScheduledTaskSnapshot;
import top.sywyar.pixivdownload.download.DownloadWorkbenchPlugin;
import top.sywyar.pixivdownload.schedule.persistence.PixivSchedulePersistenceCodec;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Pixiv 旧计划任务迁移适配器")
class PixivLegacyScheduledTaskMigrationAdapterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private PixivSchedulePersistenceCodec codec;
    private PixivLegacyScheduledTaskMigrationAdapter adapter;

    @BeforeEach
    void setUp() {
        codec = new PixivSchedulePersistenceCodec(objectMapper);
        adapter = new PixivLegacyScheduledTaskMigrationAdapter(objectMapper, codec);
    }

    @Test
    @DisplayName("七个旧枚举 alias 完整映射到 canonical source type")
    void exposesAllLegacyAliases() {
        assertThat(PixivLegacyScheduledTaskMigrationAdapter.legacySourceAliases()).containsExactlyInAnyOrderEntriesOf(
                Map.of(
                        "USER_NEW", "user-new",
                        "USER_REQUEST", "user-request",
                        "SEARCH", "search",
                        "SERIES", "series",
                        "MY_BOOKMARKS", "my-bookmarks",
                        "FOLLOW_LATEST", "follow-latest",
                        "COLLECTION", "collection"));
    }

    @ParameterizedTest(name = "{0} 映射为 {1}")
    @MethodSource("sourceMappings")
    @DisplayName("七类旧任务生成正式定义 schema 和展示快照")
    void migratesAllLegacySourceDefinitions(String legacyType, String canonicalType, String json) {
        LegacyScheduledTaskMigrationResult result = adapter.migrate(snapshot(
                legacyType, json, null, false, null, List.of()));

        assertThat(result).isInstanceOfSatisfying(LegacyScheduledTaskMigrationResult.Migrated.class, migrated -> {
            assertThat(migrated.definition().sourceType()).isEqualTo(canonicalType);
            assertThat(migrated.definition().definitionSchema())
                    .isEqualTo(PixivSchedulePersistenceCodec.DEFINITION_SCHEMA);
            assertThat(migrated.definition().definitionVersion()).isEqualTo(1);
            assertThat(migrated.definition().definitionJson()).isEqualTo(json);
            assertThat(migrated.definition().presentation().title()).isEqualTo("旧任务");
            assertThat(migrated.pending()).isEmpty();
        });
    }

    @Test
    @DisplayName("旧水位线和凭证元数据无损转换且不接触 secret")
    void migratesWatermarkAndCredentialMetadata() {
        LegacyScheduledTaskMigrationResult result = adapter.migrate(snapshot(
                "USER_NEW",
                "{\"kind\":\"illust\",\"source\":{\"userId\":\"88\"}}",
                Long.MAX_VALUE,
                true,
                Long.MAX_VALUE,
                List.of()));

        assertThat(result).isInstanceOfSatisfying(LegacyScheduledTaskMigrationResult.Migrated.class, migrated -> {
            assertThat(codec.decodeCheckpoint(migrated.checkpoint())).isEqualTo(Long.MAX_VALUE);
            assertThat(migrated.credential().policyOwnerPluginId()).isEqualTo(DownloadWorkbenchPlugin.ID);
            assertThat(migrated.credential().policyId())
                    .isEqualTo(PixivSchedulePersistenceCodec.CREDENTIAL_POLICY_ID);
            assertThat(migrated.credential().accountKey()).isEqualTo("10001");
            assertThat(migrated.credential().secretReference()).isEqualTo("scheduled-task:7:credential");
            assertThat(codec.decodeAcknowledgedWarningTime(migrated.credential().policyStateJson()))
                    .isEqualTo(Long.MAX_VALUE);
            assertThat(migrated.credential().updatedTime()).isEqualTo(1234L);
        });
    }

    @Test
    @DisplayName("过度访问旧状态映射为现有账号恢复动作识别的 canonical code")
    void mapsLegacyOveruseToCanonicalSuspendCode() {
        LegacyScheduledTaskSnapshot source = snapshot(
                "SERIES", "{\"kind\":\"illust\",\"source\":{\"seriesId\":\"9\"}}",
                null, true, 900L, List.of());
        LegacyScheduledTaskSnapshot overuse = withLastStatus(source, "OVERUSE_PAUSED");

        assertThat(adapter.migrate(overuse))
                .isInstanceOfSatisfying(LegacyScheduledTaskMigrationResult.Migrated.class,
                        migrated -> assertThat(migrated.canonicalSuspendCode())
                                .isEqualTo(PixivLegacyScheduledTaskMigrationAdapter.SUSPEND_CODE_OVERUSE));
    }

    @Test
    @DisplayName("独立持久化规范覆盖七类来源并固定定义、作品与凭证策略")
    void persistenceDescriptorProviderCoversAllLegacySources() {
        var descriptors = new PixivLegacySchedulePersistenceDescriptorProvider()
                .legacySchedulePersistenceDescriptors();

        assertThat(descriptors).hasSize(7)
                .allSatisfy(descriptor -> {
                    assertThat(descriptor.definitionSchema())
                            .isEqualTo(PixivSchedulePersistenceCodec.DEFINITION_SCHEMA);
                    assertThat(descriptor.definitionVersion())
                            .isEqualTo(PixivSchedulePersistenceCodec.DEFINITION_VERSION);
                    assertThat(descriptor.credentialPolicyIds())
                            .containsExactly(PixivSchedulePersistenceCodec.CREDENTIAL_POLICY_ID);
                });
        assertThat(descriptors).filteredOn(descriptor ->
                        descriptor.sourceType().equals("user-request")
                                || descriptor.sourceType().equals("follow-latest"))
                .allSatisfy(descriptor -> assertThat(descriptor.possibleWorkTypes())
                        .containsExactly(PixivSchedulePersistenceCodec.WORK_TYPE_ILLUST));
        assertThat(descriptors).filteredOn(descriptor -> descriptor.sourceType().equals("user-new"))
                .singleElement()
                .satisfies(descriptor -> assertThat(descriptor.possibleWorkTypes())
                        .containsExactlyInAnyOrder(
                                PixivSchedulePersistenceCodec.WORK_TYPE_ILLUST,
                                PixivSchedulePersistenceCodec.WORK_TYPE_NOVEL));
    }

    @ParameterizedTest(name = "{0} pending 转为 {2}")
    @MethodSource("pendingMappings")
    @DisplayName("插画和小说旧 pending 转为一一对应的中性信封")
    void migratesIllustAndNovelPending(String sourceType, String json, String expectedWorkType) throws Exception {
        LegacyScheduledTaskSnapshot.PendingRow pending =
                new LegacyScheduledTaskSnapshot.PendingRow(7, "9223372036854775807", "network failed", 3, 10L, 20L);

        LegacyScheduledTaskMigrationResult result = adapter.migrate(snapshot(
                sourceType, json, null, false, null, List.of(pending)));

        assertThat(result).isInstanceOfSatisfying(LegacyScheduledTaskMigrationResult.Migrated.class, migrated -> {
            assertThat(migrated.pending()).singleElement().satisfies(item -> {
                assertThat(item.legacyWorkId()).isEqualTo("9223372036854775807");
                assertThat(item.work().key().workType()).isEqualTo(expectedWorkType);
                assertThat(codec.decodeWorkId(item.work())).isEqualTo("9223372036854775807");
                assertThat(item.work().relations()).isEmpty();
                assertThat(item.work().presentation()).isEqualTo(
                        top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkPresentation.empty());
                assertThat(item.reasonCode()).isEqualTo(PixivLegacyScheduledTaskMigrationAdapter.LEGACY_PENDING_REASON);
                try {
                    assertThat(objectMapper.readTree(item.reasonDetailJson()).path("legacyReason").asText())
                            .isEqualTo("network failed");
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    throw new AssertionError(e);
                }
                assertThat(item.attempts()).isEqualTo(3);
                assertThat(item.firstSeenTime()).isEqualTo(10L);
                assertThat(item.lastAttemptTime()).isEqualTo(20L);
            });
        });
    }

    @Test
    @DisplayName("COLLECTION 含旧 pending 时拒绝猜测作品类型且安全详情不复制不透明载荷")
    void rejectsAmbiguousCollectionPending() throws Exception {
        LegacyScheduledTaskSnapshot.PendingRow pending =
                new LegacyScheduledTaskSnapshot.PendingRow(7, "42", "pending-secret", 1, 10L, 20L);

        LegacyScheduledTaskMigrationResult result = adapter.migrate(snapshot(
                "COLLECTION",
                "{\"kind\":\"mixed\",\"source\":{\"collectionId\":\"9\"},"
                        + "\"opaque\":\"definition-secret\"}",
                null,
                true,
                99L,
                List.of(pending)));

        assertThat(result).isInstanceOfSatisfying(LegacyScheduledTaskMigrationResult.Rejected.class, rejected -> {
            assertThat(rejected.code())
                    .isEqualTo(PixivLegacyScheduledTaskMigrationAdapter.REJECTION_AMBIGUOUS_WORK_TYPE);
            try {
                com.fasterxml.jackson.databind.JsonNode detail =
                        objectMapper.readTree(rejected.safeDetailJson());
                assertThat(detail.path("taskId").asText()).isEqualTo("7");
                assertThat(detail.path("legacySourceType").asText()).isEqualTo("COLLECTION");
                assertThat(detail.path("reasonCode").asText())
                        .isEqualTo("collection-pending-work-type-unknown");
                assertThat(detail.path("legacyDataPreserved").asBoolean()).isTrue();
                assertThat(detail.path("pendingCount").asInt()).isEqualTo(1);
                assertThat(detail.path("pending").get(0).path("workId").asText()).isEqualTo("42");
                assertThat(detail.path("pending").get(0).path("attempts").asInt()).isEqualTo(1);
                assertThat(detail.path("pending").get(0).path("firstSeenTime").asText()).isEqualTo("10");
                assertThat(detail.path("pending").get(0).path("lastAttemptTime").asText()).isEqualTo("20");
                assertThat(rejected.safeDetailJson())
                        .doesNotContain(
                                "definition-secret", "pending-secret", "definitionJson", "collectionId", "opaque");
                assertThat(detail.has("reason")).isFalse();
                assertThat(detail.path("pending").get(0).has("reason")).isFalse();
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new AssertionError(e);
            }
        });
    }

    @Test
    @DisplayName("没有旧 secret 时不生成凭证搬运计划")
    void omitsCredentialWithoutLegacySecret() {
        LegacyScheduledTaskSnapshot source = snapshot(
                "SERIES", "{\"kind\":\"illust\",\"source\":{\"seriesId\":\"9\"}}",
                null, false, null, List.of());
        LegacyScheduledTaskSnapshot withoutCredentialMetadata = new LegacyScheduledTaskSnapshot(
                source.id(), source.name(), source.enabled(), source.sourceType(), source.definitionJson(),
                source.triggerKind(), source.intervalMinutes(), source.cronExpr(), source.credentialMode(), false,
                source.proxySnapshot(), source.nextRunTime(), source.lastRunTime(), source.lastStatus(),
                source.lastMessage(), source.watermarkId(), source.runStartedTime(), null, null,
                source.pendingRetryArmed(), source.createdTime(), source.pending());
        LegacyScheduledTaskMigrationResult result = adapter.migrate(withoutCredentialMetadata);

        assertThat(result).isInstanceOfSatisfying(LegacyScheduledTaskMigrationResult.Migrated.class,
                migrated -> assertThat(migrated.credential()).isNull());
    }

    @Test
    @DisplayName("无 secret 但仍有策略状态时保留账号和已确认警告时间")
    void preservesPolicyStateWithoutSecret() {
        LegacyScheduledTaskMigrationResult result = adapter.migrate(snapshot(
                "USER_NEW", "{\"kind\":\"illust\",\"source\":{\"userId\":\"9\"}}",
                null, false, 88L, List.of()));

        assertThat(result).isInstanceOfSatisfying(LegacyScheduledTaskMigrationResult.Migrated.class, migrated -> {
            assertThat(migrated.credential().accountKey()).isEqualTo("10001");
            assertThat(migrated.credential().secretReference()).isNull();
            assertThat(codec.decodeAcknowledgedWarningTime(migrated.credential().policyStateJson())).isEqualTo(88L);
        });
    }

    private static Stream<Arguments> sourceMappings() {
        return Stream.of(
                Arguments.of("USER_NEW", "user-new", "{\"kind\":\"illust\",\"source\":{\"userId\":\"1\"}}"),
                Arguments.of("USER_REQUEST", "user-request", "{\"kind\":\"illust\",\"source\":{\"userId\":\"1\"}}"),
                Arguments.of("SEARCH", "search", "{\"kind\":\"novel\",\"source\":{\"word\":\"cat\"}}"),
                Arguments.of("SERIES", "series", "{\"kind\":\"illust\",\"source\":{\"seriesId\":\"2\"}}"),
                Arguments.of("MY_BOOKMARKS", "my-bookmarks", "{\"kind\":\"novel\",\"source\":{\"rest\":\"show\"}}"),
                Arguments.of("FOLLOW_LATEST", "follow-latest", "{\"kind\":\"illust\",\"source\":{}}"),
                Arguments.of("COLLECTION", "collection", "{\"kind\":\"mixed\",\"source\":{\"collectionId\":\"3\"}}"));
    }

    private static Stream<Arguments> pendingMappings() {
        return Stream.of(
                Arguments.of("USER_NEW", "{\"kind\":\"illust\",\"source\":{\"userId\":\"1\"}}", "illust"),
                Arguments.of("USER_NEW", "{\"kind\":\"novel\",\"source\":{\"userId\":\"1\"}}", "novel"),
                Arguments.of("FOLLOW_LATEST", "{\"kind\":\"novel\",\"source\":{}}", "illust"));
    }

    private static LegacyScheduledTaskSnapshot snapshot(
            String sourceType,
            String definitionJson,
            Long watermarkId,
            boolean hasLegacySecret,
            Long acknowledgedWarningTime,
            List<LegacyScheduledTaskSnapshot.PendingRow> pending) {
        return new LegacyScheduledTaskSnapshot(
                7,
                "旧任务",
                true,
                sourceType,
                definitionJson,
                "interval",
                60,
                null,
                hasLegacySecret ? "bound" : "restricted",
                hasLegacySecret,
                "127.0.0.1:7890",
                2000L,
                1000L,
                "PAUSED",
                null,
                watermarkId,
                null,
                "10001",
                acknowledgedWarningTime,
                1,
                1234L,
                pending);
    }

    private static LegacyScheduledTaskSnapshot withLastStatus(
            LegacyScheduledTaskSnapshot source, String lastStatus) {
        return new LegacyScheduledTaskSnapshot(
                source.id(), source.name(), source.enabled(), source.sourceType(), source.definitionJson(),
                source.triggerKind(), source.intervalMinutes(), source.cronExpr(), source.credentialMode(),
                source.hasLegacySecret(), source.proxySnapshot(), source.nextRunTime(), source.lastRunTime(),
                lastStatus, source.lastMessage(), source.watermarkId(), source.runStartedTime(),
                source.accountId(), source.acknowledgedWarningTime(), source.pendingRetryArmed(),
                source.createdTime(), source.pending());
    }
}

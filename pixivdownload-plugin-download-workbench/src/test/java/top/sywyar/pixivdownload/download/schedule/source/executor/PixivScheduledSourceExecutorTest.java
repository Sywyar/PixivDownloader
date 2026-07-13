package top.sywyar.pixivdownload.download.schedule.source.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.config.OutboundProxyOverride;
import top.sywyar.pixivdownload.download.PixivFetchService;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialHandle;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialRequirement;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledCancellation;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionException;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionPlan;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledFailure;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardPoint;
import top.sywyar.pixivdownload.plugin.api.schedule.network.ScheduledNetworkRoute;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledCheckpoint;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledDiscoveryResult;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledPendingReplayPolicy;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceContext;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceExecutor;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskDefinition;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskPresentation;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledWorkSink;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWork;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkKey;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkResult;
import top.sywyar.pixivdownload.schedule.persistence.PixivSchedulePersistenceCodec;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("Pixiv 计划来源执行适配器")
class PixivScheduledSourceExecutorTest {

    private ObjectMapper objectMapper;
    private PixivFetchService fetchService;
    private PixivSchedulePersistenceCodec persistenceCodec;
    private PixivScheduledLocalWorkLookup localWorkLookup;
    private PixivScheduledSourceSupport support;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        fetchService = mock(PixivFetchService.class);
        persistenceCodec = new PixivSchedulePersistenceCodec(objectMapper);
        localWorkLookup = mock(PixivScheduledLocalWorkLookup.class);
        support = new PixivScheduledSourceSupport(
                objectMapper, fetchService, persistenceCodec, localWorkLookup, 37, 0L);
    }

    @Test
    @DisplayName("七类适配器暴露稳定来源身份且计划阶段不访问发现依赖")
    void exposesSevenSourceTypesAndPlansWithoutDiscoverySideEffects() throws Exception {
        PixivFetchService isolatedFetch = mock(PixivFetchService.class);
        PixivSchedulePersistenceCodec isolatedCodec = mock(PixivSchedulePersistenceCodec.class);
        PixivScheduledLocalWorkLookup isolatedLookup = mock(PixivScheduledLocalWorkLookup.class);
        PixivScheduledSourceSupport isolatedSupport = new PixivScheduledSourceSupport(
                objectMapper, isolatedFetch, isolatedCodec, isolatedLookup, 37, 0L);
        List<ScheduledSourceExecutor> executors = executors(isolatedSupport);

        assertThat(executors).extracting(ScheduledSourceExecutor::sourceType)
                .containsExactly(
                        "user-new", "user-request", "search", "series",
                        "my-bookmarks", "follow-latest", "collection");

        for (ScheduledSourceExecutor executor : executors) {
            String kind = "collection".equals(executor.sourceType()) ? "mixed" : "illust";
            executor.plan(definition(executor.sourceType(), """
                    {"kind":"%s","source":{},"download":{"concurrent":2}}
                    """.formatted(kind)));
        }

        verifyNoInteractions(isolatedFetch, isolatedCodec, isolatedLookup);
    }

    @Test
    @DisplayName("计划从定义计算作品类型凭证要求检查点并发礼貌延迟与过度访问节奏")
    void buildsExecutionPlanFromDefinition() throws Exception {
        ScheduledExecutionPlan publicNovel = new PixivUserNewScheduledSourceExecutor(support).plan(
                definition("user-new", """
                        {"kind":"novel","source":{"userId":"8"},
                         "filters":{"content":"safe"},
                         "download":{"concurrent":4,"intervalMs":1200}}
                        """));

        assertThat(publicNovel.requiredWorkTypes()).containsExactly("novel");
        assertThat(publicNovel.credentialPolicyId()).isEqualTo("pixiv-cookie");
        assertThat(publicNovel.credentialRequirement()).isEqualTo(ScheduledCredentialRequirement.OPTIONAL);
        assertThat(publicNovel.anonymousFallbackAllowed()).isTrue();
        assertThat(publicNovel.checkpointSchema()).isEqualTo(PixivSchedulePersistenceCodec.CHECKPOINT_SCHEMA);
        assertThat(publicNovel.checkpointVersion()).isEqualTo(PixivSchedulePersistenceCodec.CHECKPOINT_VERSION);
        assertThat(publicNovel.maxInFlight()).isEqualTo(4);
        assertThat(publicNovel.politeDelayMillis()).isEqualTo(1200L);
        assertThat(publicNovel.guards()).singleElement().satisfies(binding -> {
            assertThat(binding.guardId()).isEqualTo("pixiv-overuse");
            assertThat(binding.points()).containsExactlyInAnyOrder(
                    ScheduledGuardPoint.RUN_START,
                    ScheduledGuardPoint.WORK_BATCH,
                    ScheduledGuardPoint.RUN_END,
                    ScheduledGuardPoint.RUN_FAILURE);
            assertThat(binding.workBatchSize()).isEqualTo(37);
        });

        ScheduledExecutionPlan privateBookmarks = new PixivMyBookmarksScheduledSourceExecutor(support).plan(
                definition("my-bookmarks", """
                        {"kind":"illust","source":{"rest":"hide"},"download":{"concurrent":3}}
                        """));
        assertThat(privateBookmarks.credentialRequirement()).isEqualTo(ScheduledCredentialRequirement.REQUIRED);
        assertThat(privateBookmarks.anonymousFallbackAllowed()).isFalse();
        assertThat(privateBookmarks.checkpointSchema()).isNull();

        ScheduledExecutionPlan collection = new PixivCollectionScheduledSourceExecutor(support).plan(
                definition("collection", """
                        {"kind":"mixed","source":{"collectionId":"7"},"download":{"concurrent":5}}
                        """));
        assertThat(collection.requiredWorkTypes()).containsExactlyInAnyOrder("illust", "novel");
        assertThat(collection.credentialRequirement()).isEqualTo(ScheduledCredentialRequirement.REQUIRED);

        ScheduledExecutionPlan watermarkSearch = new PixivSearchScheduledSourceExecutor(support).plan(
                definition("search", """
                        {"kind":"illust","source":{"order":"date_d","maxPages":-1}}
                        """));
        ScheduledExecutionPlan boundarySearch = new PixivSearchScheduledSourceExecutor(support).plan(
                definition("search", """
                        {"kind":"illust","source":{"order":"popular_d","maxPages":-1}}
                        """));
        assertThat(watermarkSearch.checkpointSchema()).isNotNull();
        assertThat(boundarySearch.checkpointSchema()).isNull();
    }

    @Test
    @DisplayName("热更新过度访问检查间隔后重新计划立即读取新值")
    void replansWithHotReloadedOveruseBatchSize() throws Exception {
        AtomicInteger batchSize = new AtomicInteger(37);
        PixivScheduledSourceSupport dynamicSupport = new PixivScheduledSourceSupport(
                objectMapper, fetchService, persistenceCodec, localWorkLookup,
                batchSize::get, 0L);
        PixivUserNewScheduledSourceExecutor executor =
                new PixivUserNewScheduledSourceExecutor(dynamicSupport);
        ScheduledTaskDefinition task = definition("user-new", """
                {"kind":"illust","source":{"userId":"8"},"download":{"concurrent":1}}
                """);

        ScheduledExecutionPlan beforeReload = executor.plan(task);
        batchSize.set(23);
        ScheduledExecutionPlan afterReload = executor.plan(task);

        assertThat(beforeReload.guards().get(0).workBatchSize()).isEqualTo(37);
        assertThat(afterReload.guards().get(0).workBatchSize()).isEqualTo(23);
        verifyNoInteractions(fetchService, localWorkLookup);
    }

    @Test
    @DisplayName("计划拒绝错误信封与仅插画来源的小说定义")
    void rejectsInvalidEnvelopeAndUnsupportedNovelKind() {
        ScheduledSourceExecutor request = new PixivUserRequestScheduledSourceExecutor(support);

        assertThatThrownBy(() -> request.plan(definition("search", """
                {"kind":"illust","source":{"userId":"8"}}
                """)))
                .isInstanceOf(ScheduledExecutionException.class)
                .satisfies(failure -> assertThat(((ScheduledExecutionException) failure).category())
                        .isEqualTo(ScheduledFailure.Category.INVALID_DEFINITION));
        assertThatThrownBy(() -> request.plan(definition("user-request", """
                {"kind":"novel","source":{"userId":"8"}}
                """)))
                .isInstanceOf(ScheduledExecutionException.class)
                .satisfies(failure -> assertThat(((ScheduledExecutionException) failure).code())
                        .isEqualTo("schedule.pixiv.definition-kind-unsupported"));
        assertThatThrownBy(() -> new PixivUserNewScheduledSourceExecutor(support).plan(
                definition("user-new", """
                        {"kind":" novel ","source":{"userId":"8"}}
                        """)))
                .isInstanceOf(ScheduledExecutionException.class)
                .satisfies(failure -> assertThat(((ScheduledExecutionException) failure).code())
                        .isEqualTo("schedule.pixiv.definition-kind-invalid"));
    }

    @Test
    @DisplayName("约稿水位线首轮上限计入本地完成项并只提交 ID 信封")
    void preservesUserRequestWatermarkAndFirstRunLimit() throws Exception {
        ScheduledTaskDefinition task = definition("user-request", """
                {"kind":"illust","source":{"userId":"8"},"fetchLimit":2,
                 "download":{"concurrent":2}}
                """);
        when(fetchService.discoverUserRequestArtworkIds("8", "PHPSESSID=8_secret"))
                .thenReturn(List.of("30", "20", "10"));
        completeLocally("illust:30");
        TestContext context = context(task, null);

        ScheduledDiscoveryResult result =
                new PixivUserRequestScheduledSourceExecutor(support).discover(context);

        assertThat(context.sink.submittedKeys()).containsExactly("illust:20");
        assertThat(context.sink.localKeys()).containsExactly("illust:30");
        assertThat(context.sink.allWorks()).allSatisfy(work -> {
            assertThat(work.payloadSchema()).isEqualTo(PixivSchedulePersistenceCodec.WORK_PAYLOAD_SCHEMA);
            assertThat(persistenceCodec.decodeWorkId(work)).isEqualTo(work.key().id());
            assertThat(work.payloadJson()).doesNotContain("PHPSESSID");
        });
        assertThat(persistenceCodec.decodeCheckpoint(result.candidateCheckpoint())).isEqualTo(30L);
        assertThat(context.credential.lastCopy).containsOnly('\0');
        assertThat(OutboundProxyOverride.isActive()).isFalse();
        verify(fetchService, never()).discoverUserRequestArtworkIds(eq("8"), eq(null));
    }

    @Test
    @DisplayName("搜索分别保持水位线已下载边界与固定页全量模式")
    void preservesAllSearchDiscoveryModes() throws Exception {
        PixivSearchScheduledSourceExecutor executor = new PixivSearchScheduledSourceExecutor(support);

        ScheduledTaskDefinition watermarkTask = definition("search", """
                {"kind":"illust","source":{"word":"猫","order":"date_d","mode":"all",
                 "sMode":"s_tag","maxPages":-1},"fetchLimit":1}
                """);
        when(fetchService.discoverSearchArtworkIdsPage("猫", "date_d", "all", "s_tag", 1,
                "PHPSESSID=8_secret")).thenReturn(List.of("120", "110", "100", "90"));
        TestContext watermarkContext = context(
                watermarkTask, persistenceCodec.encodeCheckpoint(100L));
        ScheduledDiscoveryResult watermarkResult = executor.discover(watermarkContext);
        assertThat(watermarkContext.sink.submittedKeys()).containsExactly("illust:120", "illust:110");
        assertThat(persistenceCodec.decodeCheckpoint(watermarkResult.candidateCheckpoint())).isEqualTo(120L);

        ScheduledTaskDefinition boundaryTask = definition("search", """
                {"kind":"illust","source":{"word":"猫","order":"popular_d","mode":"all",
                 "sMode":"s_tag","maxPages":-1},"fetchLimit":9}
                """);
        when(fetchService.discoverSearchArtworkIdsPage("猫", "popular_d", "all", "s_tag", 1,
                "PHPSESSID=8_secret")).thenReturn(List.of("9", "8", "7"));
        completeLocally("illust:8");
        TestContext boundaryContext = context(boundaryTask, null);
        ScheduledDiscoveryResult boundaryResult = executor.discover(boundaryContext);
        assertThat(boundaryContext.sink.submittedKeys()).containsExactly("illust:9");
        assertThat(boundaryContext.sink.localKeys()).containsExactly("illust:8");
        assertThat(boundaryResult.candidateCheckpoint()).isNull();

        ScheduledTaskDefinition fullTask = definition("search", """
                {"kind":"novel","source":{"word":"猫","order":"date_d","mode":"all",
                 "sMode":"s_tag","maxPages":2},"fetchLimit":1}
                """);
        when(fetchService.discoverSearchNovelIds(
                "猫", "date_d", "all", "s_tag", 2, "PHPSESSID=8_secret"))
                .thenReturn(List.of("5", "4"));
        completeLocally("novel:5");
        TestContext fullContext = context(fullTask, null);
        executor.discover(fullContext);
        assertThat(fullContext.sink.localKeys()).containsExactly("novel:5");
        assertThat(fullContext.sink.submittedKeys()).containsExactly("novel:4");
    }

    @Test
    @DisplayName("画师新作与系列按定义作品类型选择对应发现接口")
    void selectsKindSpecificUserAndSeriesFetchers() throws Exception {
        ScheduledTaskDefinition userTask = definition("user-new", """
                {"kind":"novel","source":{"userId":"8"},"download":{"concurrent":1}}
                """);
        when(fetchService.discoverUserNovelIds("8", "PHPSESSID=8_secret"))
                .thenReturn(List.of("31"));
        TestContext userContext = context(userTask, null);
        new PixivUserNewScheduledSourceExecutor(support).discover(userContext);
        assertThat(userContext.sink.submittedKeys()).containsExactly("novel:31");

        ScheduledTaskDefinition seriesTask = definition("series", """
                {"kind":"illust","source":{"seriesId":"9"},"fetchLimit":1}
                """);
        when(fetchService.discoverSeriesArtworkIds("9", "PHPSESSID=8_secret"))
                .thenReturn(List.of("21", "20"));
        TestContext seriesContext = context(seriesTask, null);
        new PixivSeriesScheduledSourceExecutor(support).discover(seriesContext);
        assertThat(seriesContext.sink.submittedKeys()).containsExactly("illust:21", "illust:20");
    }

    @Test
    @DisplayName("账号收藏的每轮上限不被本地完成项占用")
    void keepsBookmarkLimitFreeForAlreadyCompletedWork() throws Exception {
        ScheduledTaskDefinition task = definition("my-bookmarks", """
                {"kind":"illust","source":{"rest":"hide"},"fetchLimit":1}
                """);
        when(fetchService.discoverMyIllustBookmarkIds("hide", "PHPSESSID=8_secret"))
                .thenReturn(List.of("3", "2", "1"));
        completeLocally("illust:3");
        TestContext context = context(task, null);

        new PixivMyBookmarksScheduledSourceExecutor(support).discover(context);

        assertThat(context.sink.localKeys()).containsExactly("illust:3");
        assertThat(context.sink.submittedKeys()).containsExactly("illust:2");
        assertThat(context.sink.allKeys()).doesNotContain("illust:1");
    }

    @Test
    @DisplayName("关注新作按末页停止并推进最新作品水位线")
    void stopsFollowLatestAtLastPage() throws Exception {
        ScheduledTaskDefinition task = definition("follow-latest", """
                {"kind":"illust","source":{},"download":{"concurrent":2}}
                """);
        when(fetchService.fetchFollowLatestPage(1, "PHPSESSID=8_secret"))
                .thenReturn(new PixivFetchService.FollowLatestPage(List.of("40", "39"), true));
        TestContext context = context(task, null);

        ScheduledDiscoveryResult result =
                new PixivFollowLatestScheduledSourceExecutor(support).discover(context);

        assertThat(context.sink.submittedKeys()).containsExactly("illust:40", "illust:39");
        assertThat(persistenceCodec.decodeCheckpoint(result.candidateCheckpoint())).isEqualTo(40L);
        verify(fetchService, never()).fetchFollowLatestPage(eq(2), any());
    }

    @Test
    @DisplayName("珍藏集只重放本轮仍存在的待处理项并让两类作品共享新作预算")
    void rediscoverCollectionPendingWithSharedBudget() throws Exception {
        ScheduledTaskDefinition task = definition("collection", """
                {"kind":"mixed","source":{"collectionId":"77"},"fetchLimit":1,
                 "download":{"concurrent":3}}
                """);
        when(fetchService.discoverCollectionWorkIds("77", "PHPSESSID=8_secret"))
                .thenReturn(new PixivFetchService.CollectionWorkIds(
                        List.of("1", "2", "3", "4"), List.of("1", "2")));
        completeLocally("illust:1");
        TestContext context = context(task, null);
        context.pending.add(new ScheduledWorkKey("illust", "4"));
        context.pending.add(new ScheduledWorkKey("novel", "1"));
        PixivCollectionScheduledSourceExecutor executor =
                new PixivCollectionScheduledSourceExecutor(support);

        executor.discover(context);

        assertThat(executor.pendingReplayPolicy())
                .isEqualTo(ScheduledPendingReplayPolicy.REDISCOVERED_ONLY);
        assertThat(context.sink.localKeys()).containsExactly("illust:1");
        assertThat(context.sink.submittedKeys())
                .containsExactly("illust:2", "illust:4", "novel:1");
        assertThat(context.sink.submitSequence())
                .containsExactly("illust:2", "illust:4", "drain", "novel:1");
        assertThat(context.sink.allKeys()).doesNotContain("illust:3", "novel:2");
    }

    @Test
    @DisplayName("发现失败返回安全分类并清零临时凭证副本与路由覆盖")
    void sanitizesDiscoveryFailureAndClearsScopes() throws Exception {
        ScheduledTaskDefinition task = definition("user-request", """
                {"kind":"illust","source":{"userId":"8"}}
                """);
        when(fetchService.discoverUserRequestArtworkIds("8", "PHPSESSID=8_secret"))
                .thenThrow(new PixivFetchService.PixivFetchException("PHPSESSID=8_secret"));
        TestContext context = context(task, null);

        assertThatThrownBy(() -> new PixivUserRequestScheduledSourceExecutor(support).discover(context))
                .isInstanceOf(ScheduledExecutionException.class)
                .satisfies(failure -> {
                    ScheduledExecutionException scheduled = (ScheduledExecutionException) failure;
                    assertThat(scheduled.category()).isEqualTo(ScheduledFailure.Category.CREDENTIAL_INVALID);
                    assertThat(scheduled.code()).isEqualTo("schedule.pixiv.discovery-credential-invalid");
                    assertThat(scheduled.getMessage()).doesNotContain("PHPSESSID", "secret");
                    assertThat(scheduled.getCause()).isNull();
                });
        assertThat(context.credential.lastCopy).containsOnly('\0');
        assertThat(OutboundProxyOverride.isActive()).isFalse();

        doThrow(new IOException("network"))
                .when(fetchService).discoverUserRequestArtworkIds("8", "PHPSESSID=8_secret");
        assertThatThrownBy(() -> new PixivUserRequestScheduledSourceExecutor(support)
                .discover(context(task, null)))
                .isInstanceOf(ScheduledExecutionException.class)
                .satisfies(failure -> assertThat(((ScheduledExecutionException) failure).category())
                        .isEqualTo(ScheduledFailure.Category.RETRYABLE_NETWORK));
    }

    @Test
    @DisplayName("作品接收端抛出的未知运行时控制信号原样穿透并清理作用域")
    void propagatesUnknownRuntimeControlSignal() throws Exception {
        ScheduledTaskDefinition task = definition("user-request", """
                {"kind":"illust","source":{"userId":"8"}}
                """);
        when(fetchService.discoverUserRequestArtworkIds("8", "PHPSESSID=8_secret"))
                .thenReturn(List.of("1"));
        TestContext context = context(task, null);
        RuntimeException hostSignal = new RuntimeException("host-control-signal");
        context.sink.failSubmissionsWith(hostSignal);

        assertThatThrownBy(() -> new PixivUserRequestScheduledSourceExecutor(support)
                .discover(context))
                .isSameAs(hostSignal);
        assertThat(context.credential.lastCopy).containsOnly('\0');
        assertThat(OutboundProxyOverride.isActive()).isFalse();
    }

    @Test
    @DisplayName("坏检查点在凭证与网络前失败且网络期间取消不会被空结果吞掉")
    void rejectsCheckpointEarlyAndObservesCancellationAfterFetch() throws Exception {
        ScheduledTaskDefinition task = definition("user-request", """
                {"kind":"illust","source":{"userId":"8"}}
                """);
        TestContext invalidCheckpoint = context(
                task, new ScheduledCheckpoint("other.checkpoint", 1, "{}"));

        assertThatThrownBy(() -> new PixivUserRequestScheduledSourceExecutor(support)
                .discover(invalidCheckpoint))
                .isInstanceOf(ScheduledExecutionException.class)
                .satisfies(failure -> assertThat(((ScheduledExecutionException) failure).code())
                        .isEqualTo("schedule.pixiv.checkpoint-invalid"));
        verifyNoInteractions(fetchService);
        assertThat(invalidCheckpoint.credential.lastCopy).isNull();

        boolean[] cancelled = {false};
        doAnswer(invocation -> {
            cancelled[0] = true;
            return List.of();
        }).when(fetchService).discoverUserRequestArtworkIds("8", "PHPSESSID=8_secret");
        TestContext cancelledDuringFetch =
                new TestContext(task, null, () -> cancelled[0]);

        assertThatThrownBy(() -> new PixivUserRequestScheduledSourceExecutor(support)
                .discover(cancelledDuringFetch))
                .isInstanceOf(ScheduledExecutionException.class)
                .satisfies(failure -> assertThat(((ScheduledExecutionException) failure).category())
                        .isEqualTo(ScheduledFailure.Category.CANCELLED));
        assertThat(cancelledDuringFetch.credential.lastCopy).containsOnly('\0');
        assertThat(OutboundProxyOverride.isActive()).isFalse();
    }

    private List<ScheduledSourceExecutor> executors(PixivScheduledSourceSupport sourceSupport) {
        return List.of(
                new PixivUserNewScheduledSourceExecutor(sourceSupport),
                new PixivUserRequestScheduledSourceExecutor(sourceSupport),
                new PixivSearchScheduledSourceExecutor(sourceSupport),
                new PixivSeriesScheduledSourceExecutor(sourceSupport),
                new PixivMyBookmarksScheduledSourceExecutor(sourceSupport),
                new PixivFollowLatestScheduledSourceExecutor(sourceSupport),
                new PixivCollectionScheduledSourceExecutor(sourceSupport));
    }

    private ScheduledTaskDefinition definition(String sourceType, String json) {
        return new ScheduledTaskDefinition(
                1L,
                sourceType,
                PixivSchedulePersistenceCodec.DEFINITION_SCHEMA,
                PixivSchedulePersistenceCodec.DEFINITION_VERSION,
                json,
                ScheduledTaskPresentation.empty());
    }

    private TestContext context(ScheduledTaskDefinition task, ScheduledCheckpoint checkpoint) {
        return new TestContext(task, checkpoint);
    }

    private void completeLocally(String... keys) {
        Set<String> completed = Set.of(keys);
        doAnswer(invocation -> {
            ScheduledWorkKey key = invocation.getArgument(0);
            return completed.contains(key.workType() + ":" + key.id());
        }).when(localWorkLookup).isAlreadyCompleted(any(), any());
    }

    private static final class TestContext implements ScheduledSourceContext {
        private final ScheduledTaskDefinition task;
        private final ScheduledCheckpoint checkpoint;
        private final RecordingSink sink = new RecordingSink();
        private final RecordingCredential credential = new RecordingCredential("PHPSESSID=8_secret");
        private final Set<ScheduledWorkKey> pending = new LinkedHashSet<>();
        private final ScheduledCancellation cancellation;

        private TestContext(ScheduledTaskDefinition task, ScheduledCheckpoint checkpoint) {
            this(task, checkpoint, () -> false);
        }

        private TestContext(
                ScheduledTaskDefinition task,
                ScheduledCheckpoint checkpoint,
                ScheduledCancellation cancellation) {
            this.task = task;
            this.checkpoint = checkpoint;
            this.cancellation = cancellation;
        }

        @Override
        public ScheduledCheckpoint checkpoint() {
            return checkpoint;
        }

        @Override
        public ScheduledWorkSink workSink() {
            return sink;
        }

        @Override
        public boolean isPending(ScheduledWorkKey key) {
            return pending.contains(key);
        }

        @Override
        public ScheduledTaskDefinition task() {
            return task;
        }

        @Override
        public ScheduledNetworkRoute route() {
            return ScheduledNetworkRoute.direct();
        }

        @Override
        public ScheduledCredentialHandle credential() {
            return credential;
        }

        @Override
        public ScheduledCancellation cancellation() {
            return cancellation;
        }
    }

    private static final class RecordingCredential implements ScheduledCredentialHandle {
        private final String secret;
        private char[] lastCopy;

        private RecordingCredential(String secret) {
            this.secret = secret;
        }

        @Override
        public boolean isPresent() {
            return !secret.isEmpty();
        }

        @Override
        public String reference() {
            return "credential-reference";
        }

        @Override
        public String accountKey() {
            return "8";
        }

        @Override
        public char[] copySecret() {
            lastCopy = secret.toCharArray();
            return lastCopy;
        }

        @Override
        public void close() {
        }
    }

    private static final class RecordingSink implements ScheduledWorkSink {
        private final List<ScheduledWork> submitted = new ArrayList<>();
        private final List<ScheduledWork> local = new ArrayList<>();
        private final List<String> submitSequence = new ArrayList<>();
        private RuntimeException submitFailure;

        @Override
        public void submit(ScheduledWork work) {
            if (submitFailure != null) {
                throw submitFailure;
            }
            submitted.add(work);
            submitSequence.add(work.key().workType() + ":" + work.key().id());
        }

        @Override
        public void drain() {
            submitSequence.add("drain");
        }

        void failSubmissionsWith(RuntimeException failure) {
            submitFailure = failure;
        }

        @Override
        public void completeLocally(ScheduledWork work, ScheduledWorkResult result) {
            assertThat(result.outcome()).isEqualTo(ScheduledWorkResult.Outcome.ALREADY_COMPLETED);
            local.add(work);
        }

        List<String> submittedKeys() {
            return keys(submitted);
        }

        List<String> localKeys() {
            return keys(local);
        }

        List<String> allKeys() {
            return keys(allWorks());
        }

        List<String> submitSequence() {
            return List.copyOf(submitSequence);
        }

        List<ScheduledWork> allWorks() {
            List<ScheduledWork> all = new ArrayList<>(local);
            all.addAll(submitted);
            return all;
        }

        private static List<String> keys(List<ScheduledWork> works) {
            return works.stream()
                    .map(work -> work.key().workType() + ":" + work.key().id())
                    .toList();
        }
    }
}

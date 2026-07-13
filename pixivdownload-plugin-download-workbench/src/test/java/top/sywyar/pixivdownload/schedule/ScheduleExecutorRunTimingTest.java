package top.sywyar.pixivdownload.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;
import top.sywyar.pixivdownload.core.metadata.novel.NovelMetadataRepository;
import top.sywyar.pixivdownload.core.schedule.ScheduledPendingWork;
import top.sywyar.pixivdownload.core.schedule.ScheduledTask;
import top.sywyar.pixivdownload.core.schedule.ScheduledTaskStore;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityPublication;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistry;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleGenerationDrain;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleLastOutcome;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleRunCompletion;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleRunToken;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleSuspendReason;
import top.sywyar.pixivdownload.download.ArtworkDownloader;
import top.sywyar.pixivdownload.download.DownloadWorkbenchPlugin;
import top.sywyar.pixivdownload.download.PixivFetchService;
import top.sywyar.pixivdownload.download.request.DownloadRequest;
import top.sywyar.pixivdownload.download.schedule.source.DiscoveryMode;
import top.sywyar.pixivdownload.download.schedule.source.ScheduledSource;
import top.sywyar.pixivdownload.download.schedule.source.ScheduledSourceContext;
import top.sywyar.pixivdownload.download.schedule.work.ScheduledIllustWorkRunner;
import top.sywyar.pixivdownload.notification.NotificationScenario;
import top.sywyar.pixivdownload.schedule.persistence.PixivSchedulePersistenceCodec;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ScheduleExecutor 耐久状态与收尾屏障")
class ScheduleExecutorRunTimingTest {

    private static final TaskExecutor SYNC_EXECUTOR = Runnable::run;

    @Mock
    private ScheduledTaskStore store;
    @Mock
    private PixivFetchService pixivFetchService;
    @Mock
    private top.sywyar.pixivdownload.core.db.PixivDatabase pixivDatabase;
    @Mock
    private ArtworkDownloader artworkDownloader;
    @Mock
    private NovelMetadataRepository novelMetadataRepository;
    @Mock
    private OveruseWarningService overuseWarningService;
    @Mock
    private top.sywyar.pixivdownload.core.notification.NotificationService notificationService;
    @Mock
    private top.sywyar.pixivdownload.i18n.AppMessages appMessages;
    @Mock
    private top.sywyar.pixivdownload.setup.SetupService setupService;

    private ObjectMapper objectMapper;
    private PixivSchedulePersistenceCodec codec;
    private ScheduleRunState localRunState;
    private ScheduleExecutor executor;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        codec = new PixivSchedulePersistenceCodec(objectMapper);
        localRunState = new ScheduleRunState();
        executor = newExecutor(SYNC_EXECUTOR, SYNC_EXECUTOR);

        lenient().when(store.tryQueueNow(anyLong(), anyLong(), anyString()))
                .thenAnswer(invocation -> Optional.of(new ScheduleRunToken(
                        invocation.getArgument(2), 1L,
                        top.sywyar.pixivdownload.core.schedule.state.ScheduleRunState.QUEUED)));
        lenient().when(store.startRun(anyLong(), any(ScheduleRunToken.class)))
                .thenAnswer(invocation -> {
                    ScheduleRunToken queued = invocation.getArgument(1);
                    return Optional.of(new ScheduleRunToken(
                            queued.claimToken(), 2L,
                            top.sywyar.pixivdownload.core.schedule.state.ScheduleRunState.RUNNING));
                });
        lenient().when(store.completeRun(
                        anyLong(), any(ScheduleRunToken.class), any(ScheduleRunCompletion.class)))
                .thenReturn(OptionalLong.of(3L));
        lenient().when(store.finishCancelled(
                        anyLong(), any(ScheduleRunToken.class), any(ScheduleLastOutcome.class),
                        anyLong(), any(), any(), any()))
                .thenReturn(OptionalLong.of(3L));
        lenient().when(store.suspend(
                        anyLong(), anyLong(), any(ScheduleSuspendReason.class), any(), any()))
                .thenReturn(OptionalLong.of(3L));
        lenient().when(store.listPendingWork(anyLong())).thenReturn(List.of());
    }

    @Test
    @DisplayName("真实并发下载全部排空后才把结果与候选检查点原子提交")
    void commitsOutcomeAndCheckpointAfterDownloadBarrier() throws Exception {
        ScheduledTask task = task(1L, "user-new",
                "{\"kind\":\"illust\",\"source\":{\"userId\":\"100\"},"
                        + "\"download\":{\"concurrent\":2}}",
                null, null, null);
        when(pixivFetchService.discoverUserArtworkIds("100", null)).thenReturn(List.of("123", "124"));
        stubArtwork(123L, "标题");
        stubArtwork(124L, "标题");
        CountDownLatch downloadsStarted = new CountDownLatch(2);
        CountDownLatch allowDownloadsToFinish = new CountDownLatch(1);
        AtomicInteger finished = new AtomicInteger();
        AtomicLong downloadedAt = new AtomicLong();
        when(artworkDownloader.downloadImagesBlocking(
                anyLong(), eq("标题"), anyList(), anyString(),
                any(DownloadRequest.Other.class), isNull(), isNull()))
                .thenAnswer(invocation -> {
                    downloadsStarted.countDown();
                    assertThat(allowDownloadsToFinish.await(5, TimeUnit.SECONDS)).isTrue();
                    downloadedAt.set(System.currentTimeMillis());
                    finished.incrementAndGet();
                    return true;
                });

        ExecutorService downloadPool = Executors.newFixedThreadPool(2);
        ScheduleExecutor asyncExecutor = newExecutor(downloadPool::execute, downloadPool::execute);
        Thread run = new Thread(() -> asyncExecutor.runTaskAndRecord(task), "schedule-barrier-test");
        run.start();
        try {
            assertThat(downloadsStarted.await(5, TimeUnit.SECONDS)).isTrue();
            verify(store, never()).completeRun(
                    eq(1L), any(ScheduleRunToken.class), any(ScheduleRunCompletion.class));

            allowDownloadsToFinish.countDown();
            run.join(5_000L);
            assertThat(run.isAlive()).isFalse();
        } finally {
            allowDownloadsToFinish.countDown();
            run.join(5_000L);
            downloadPool.shutdownNow();
        }

        ScheduleRunCompletion completion = captureCompletion(1L);
        assertThat(finished.get()).isEqualTo(2);
        assertThat(completion.outcome()).isEqualTo(ScheduleLastOutcome.OK);
        assertThat(completion.finishedTime()).isGreaterThanOrEqualTo(downloadedAt.get());
        assertThat(completion.nextRunTime()).isEqualTo(completion.finishedTime() + 60_000L);
        assertThat(completion.checkpointSchema())
                .isEqualTo(PixivSchedulePersistenceCodec.CHECKPOINT_SCHEMA);
        assertThat(codec.decodeCheckpoint(new top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledCheckpoint(
                 completion.checkpointSchema(), completion.checkpointVersion(), completion.checkpointJson())))
                .isEqualTo(124L);
    }

    @Test
    @DisplayName("无水位线来源正常结束时不伪造检查点")
    void doesNotCreateCheckpointForSeries() throws Exception {
        ScheduledTask task = task(
                2L, "series",
                "{\"kind\":\"illust\",\"source\":{\"seriesId\":\"9\"}}",
                null, null, null);
        when(pixivFetchService.discoverSeriesArtworkIds("9", null)).thenReturn(List.of());

        executor.runTaskAndRecord(task);

        ScheduleRunCompletion completion = captureCompletion(2L);
        assertThat(completion.outcome()).isEqualTo(ScheduleLastOutcome.OK);
        assertThat(completion.checkpointSchema()).isNull();
        assertThat(completion.checkpointVersion()).isNull();
        assertThat(completion.checkpointJson()).isNull();
    }

    @Test
    @DisplayName("SEARCH popular_d 与 maxPages=-1 逐页处理直到命中已下载边界")
    void popularIncrementalSearchUsesPagedDownloadedBoundary() throws Exception {
        ScheduledTask task = task(
                10L, "search",
                "{\"kind\":\"illust\",\"source\":{\"word\":\"tag\",\"order\":\"popular_d\","
                        + "\"mode\":\"all\",\"sMode\":\"s_tag\",\"maxPages\":-1}}",
                null, null, null);
        when(pixivFetchService.discoverSearchArtworkIdsPage(
                "tag", "popular_d", "all", "s_tag", 1, null))
                .thenReturn(List.of("300", "200"));
        when(pixivDatabase.hasArtwork(300L)).thenReturn(false);
        when(pixivDatabase.hasArtwork(200L)).thenReturn(true);
        stubArtwork(300L, "热门新作");
        when(artworkDownloader.downloadImagesBlocking(
                eq(300L), eq("热门新作"), anyList(), eq("https://www.pixiv.net/artworks/300"),
                any(DownloadRequest.Other.class), isNull(), isNull()))
                .thenReturn(true);

        executor.runTaskAndRecord(task);

        verify(pixivFetchService).discoverSearchArtworkIdsPage(
                "tag", "popular_d", "all", "s_tag", 1, null);
        verify(pixivFetchService, never()).discoverSearchArtworkIds(
                eq("tag"), eq("popular_d"), eq("all"), eq("s_tag"), eq(-1), isNull());
        verify(artworkDownloader).downloadImagesBlocking(
                eq(300L), eq("热门新作"), anyList(), eq("https://www.pixiv.net/artworks/300"),
                any(DownloadRequest.Other.class), isNull(), isNull());
        ScheduleRunCompletion completion = captureCompletion(10L);
        assertThat(completion.outcome()).isEqualTo(ScheduleLastOutcome.OK);
        assertThat(completion.checkpointSchema()).isNull();
        assertThat(completion.checkpointJson()).isNull();
    }

    @Test
    @DisplayName("单作品可恢复失败先耐久进入 pending 再允许推进检查点")
    void persistsPendingBeforeCheckpoint() throws Exception {
        ScheduledTask task = task(3L, "user-new", userDefinition("100"), null, null, null);
        when(pixivFetchService.discoverUserArtworkIds("100", null)).thenReturn(List.of("200"));
        when(pixivDatabase.hasArtwork(200L)).thenReturn(false);
        when(pixivFetchService.fetchArtworkMetaCapture("200", null))
                .thenThrow(new PixivFetchService.PixivFetchException(
                        "Authorization: Bearer pending-secret token: second-secret"));

        executor.runTaskAndRecord(task);

        ArgumentCaptor<ScheduledPendingWork> pending = ArgumentCaptor.forClass(ScheduledPendingWork.class);
        ArgumentCaptor<ScheduleRunCompletion> completion =
                ArgumentCaptor.forClass(ScheduleRunCompletion.class);
        InOrder durableWrites = inOrder(store);
        durableWrites.verify(store).upsertPendingWork(pending.capture());
        durableWrites.verify(store).completeRun(
                eq(3L), any(ScheduleRunToken.class), completion.capture());
        assertThat(pending.getValue().workType()).isEqualTo("illust");
        assertThat(pending.getValue().workId()).isEqualTo("200");
        assertThat(codec.decodeWorkId(codec.fromPendingWork(pending.getValue()))).isEqualTo("200");
        assertThat(pending.getValue().reasonDetailJson()).contains("[redacted]")
                .doesNotContain("pending-secret", "second-secret");
        assertThat(completion.getValue().checkpointJson()).contains("\"200\"");
    }

    @Test
    @DisplayName("下载回调无法耐久 pending 时整轮失败且绝不提交候选检查点")
    void pendingPersistenceFailurePreventsCheckpointCommit() throws Exception {
        ScheduledTask task = task(4L, "user-new", userDefinition("100"), null, null, null);
        when(pixivFetchService.discoverUserArtworkIds("100", null)).thenReturn(List.of("201"));
        stubArtwork(201L, "失败作品");
        when(artworkDownloader.downloadImagesBlocking(
                eq(201L), eq("失败作品"), anyList(), anyString(),
                any(DownloadRequest.Other.class), isNull(), isNull()))
                .thenReturn(false);
        when(store.upsertPendingWork(any(ScheduledPendingWork.class)))
                .thenThrow(new IllegalStateException("database unavailable"));

        executor.runTaskAndRecord(task);

        ScheduleRunCompletion completion = captureCompletion(4L);
        assertThat(completion.outcome()).isEqualTo(ScheduleLastOutcome.ERROR);
        assertThat(completion.checkpointSchema()).isNull();
        assertThat(completion.checkpointJson()).isNull();
    }

    @Test
    @DisplayName("顶层失败结果在持久化前脱敏完整 Cookie 对")
    void failureOutcomeRedactsCookiePairs() throws Exception {
        ScheduledTask task = task(11L, "user-new", userDefinition("100"), null, null, null);
        when(pixivFetchService.discoverUserArtworkIds("100", null))
                .thenThrow(new IllegalStateException(
                        "bad auth PHPSESSID=cookie-secret; device=pair-secret"));

        executor.runTaskAndRecord(task);

        ScheduleRunCompletion completion = captureCompletion(11L);
        assertThat(completion.outcome()).isEqualTo(ScheduleLastOutcome.ERROR);
        assertThat(completion.outcomeMessage()).contains("[redacted]")
                .doesNotContain("cookie-secret", "pair-secret");
    }

    @Test
    @DisplayName("顶层失败结果统一脱敏 Authorization、Bearer、token 与签名 URL")
    void failureOutcomeRedactsGenericCredentialForms() throws Exception {
        ScheduledTask task = task(12L, "user-new", userDefinition("100"), null, null, null);
        when(pixivFetchService.discoverUserArtworkIds("100", null))
                .thenThrow(new IllegalStateException(
                        "request failed Authorization: Basic basic-secret, "
                                + "Proxy-Authorization: Bearer proxy-secret, token: token-secret "
                                + "url=https://example.test/a?X-Amz-Signature=signature-secret"));

        executor.runTaskAndRecord(task);

        ScheduleRunCompletion completion = captureCompletion(12L);
        assertThat(completion.outcome()).isEqualTo(ScheduleLastOutcome.ERROR);
        assertThat(completion.outcomeMessage()).contains("[redacted]")
                .doesNotContain("basic-secret", "proxy-secret", "token-secret", "signature-secret");
    }

    @Test
    @DisplayName("鉴权失效通知不携带会误导的下次运行时间")
    void authExpiredNotificationOmitsNextRunTime() throws Exception {
        ScheduledTask task = task(18L, "user-new", userDefinition("100"), null, null, null);
        when(pixivFetchService.discoverUserArtworkIds("100", null))
                .thenThrow(new PixivFetchService.PixivFetchException("auth expired"));

        executor.runTaskAndRecord(task);

        verify(store).suspend(
                eq(18L), eq(2L), eq(ScheduleSuspendReason.CREDENTIAL), eq("COOKIE_DEAD"), isNull());
        verify(store).finishCancelled(
                eq(18L), any(ScheduleRunToken.class), eq(ScheduleLastOutcome.ERROR),
                anyLong(), eq("COOKIE_DEAD"), isNull(), any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> placeholders = ArgumentCaptor.forClass(Map.class);
        verify(notificationService).notify(
                eq(NotificationScenario.AUTH_EXPIRED), any(), placeholders.capture());
        assertThat(placeholders.getValue()).doesNotContainKey("next_run_time");
        verify(store, never()).completeRun(
                eq(18L), any(ScheduleRunToken.class), any(ScheduleRunCompletion.class));
    }

    @Test
    @DisplayName("损坏 pending 信封保留原行并把任务挂起为迁移错误")
    void invalidPendingEnvelopeSuspendsWithoutCheckpoint() {
        ScheduledTask task = task(5L, "user-new", userDefinition("100"), null, null, null);
        when(store.listPendingWork(5L)).thenReturn(List.of(new ScheduledPendingWork(
                5L, "illust", "202", "unknown.schema", 9, "{}", "[]", "{}",
                "OLD", "{}", 1, 1L, 2L)));

        executor.runTaskAndRecord(task);

        verify(store).suspend(
                eq(5L), eq(2L), eq(ScheduleSuspendReason.MIGRATION_ERROR),
                eq("DEFINITION_INVALID"), anyString());
        verify(store).finishCancelled(
                eq(5L), any(ScheduleRunToken.class), eq(ScheduleLastOutcome.ERROR),
                anyLong(), eq("DEFINITION_INVALID"), anyString(), any());
        verify(store, never()).completeRun(eq(5L), any(), any());
        verify(store, never()).deletePendingWork(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("pending 重试与同轮来源重复发现共享作品认领且只下载一次")
    void pendingRetryAndDiscoveryDispatchOnlyOnce() throws Exception {
        ScheduledTask task = task(6L, "user-new", userDefinition("100"), null, null, null);
        ScheduledPendingWork pending = codec.toPendingWork(
                6L, codec.createWorkEnvelope("illust", "203"),
                "OLD", "{}", 0, 1L, 2L);
        when(store.listPendingWork(6L)).thenReturn(List.of(pending));
        when(pixivFetchService.discoverUserArtworkIds("100", null)).thenReturn(List.of("203"));
        stubArtwork(203L, "重试作品");
        when(artworkDownloader.downloadImagesBlocking(
                eq(203L), eq("重试作品"), anyList(), anyString(),
                any(DownloadRequest.Other.class), isNull(), isNull()))
                .thenReturn(true);

        executor.runTaskAndRecord(task);

        verify(artworkDownloader, times(1)).downloadImagesBlocking(
                eq(203L), eq("重试作品"), anyList(), anyString(),
                any(DownloadRequest.Other.class), isNull(), isNull());
        verify(store).deletePendingWork(6L, "illust", "203");
        assertThat(captureCompletion(6L).outcome()).isEqualTo(ScheduleLastOutcome.OK);
    }

    @Test
    @DisplayName("pending 重试刚跨过上限时触发需人工通知")
    void pendingRetryNotifiesWhenAttemptsCrossLimit() throws Exception {
        ScheduledTask task = task(13L, "user-new", userDefinition("100"), null, null, null);
        ScheduledPendingWork pending = codec.toPendingWork(
                13L, codec.createWorkEnvelope("illust", "777"),
                "OLD", "{}", 4, 1_000L, 2_000L);
        ScheduledPendingWork exhausted = codec.toPendingWork(
                13L, codec.createWorkEnvelope("illust", "777"),
                "OLD", "{}", 5, 1_000L, 3_000L);
        when(store.listPendingWork(13L)).thenReturn(List.of(pending));
        when(store.incrementPendingAttempts(eq(13L), eq("illust"), eq("777"), anyLong()))
                .thenReturn(5);
        when(store.findPendingWork(13L, "illust", "777")).thenReturn(exhausted);
        when(pixivDatabase.hasArtwork(777L)).thenReturn(false);
        when(pixivFetchService.fetchArtworkMetaCapture("777", null))
                .thenThrow(new IllegalStateException("still failing"));
        when(pixivFetchService.discoverUserArtworkIds("100", null)).thenReturn(List.of());

        executor.runTaskAndRecord(task);

        verify(store).incrementPendingAttempts(eq(13L), eq("illust"), eq("777"), anyLong());
        ScheduleRunCompletion completion = captureCompletion(13L);
        assertThat(completion.outcome()).isEqualTo(ScheduleLastOutcome.OK);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> placeholders = ArgumentCaptor.forClass(Map.class);
        verify(notificationService).notify(
                eq(NotificationScenario.PENDING_EXHAUSTED), any(), placeholders.capture());
        assertThat(placeholders.getValue())
                .containsEntry("work_id", "777")
                .containsEntry("attempts", "5")
                .containsEntry("last_error_excerpt", "still failing");
    }

    @Test
    @DisplayName("实际目录检测开启时使用 ArtworkDownloader 去重而不读取裸数据库命中")
    void verifyFilesUsesArtworkDownloaderDedup() throws Exception {
        ScheduledTask task = task(
                14L, "user-new",
                "{\"kind\":\"illust\",\"source\":{\"userId\":\"100\"},"
                        + "\"download\":{\"verifyFiles\":true}}",
                null, null, null);
        when(pixivFetchService.discoverUserArtworkIds("100", null)).thenReturn(List.of("200"));
        when(artworkDownloader.isArtworkDownloaded(200L, true)).thenReturn(true);

        executor.runTaskAndRecord(task);

        verify(artworkDownloader).isArtworkDownloaded(200L, true);
        verify(pixivDatabase, never()).hasArtwork(anyLong());
        verify(artworkDownloader, never()).downloadImagesBlocking(
                anyLong(), any(), anyList(), any(), any(DownloadRequest.Other.class), any(), any());
        ScheduleRunCompletion completion = captureCompletion(14L);
        assertThat(completion.outcome()).isEqualTo(ScheduleLastOutcome.OK);
        assertThat(completion.checkpointJson()).contains("\"watermarkId\":\"200\"");
    }

    @Test
    @DisplayName("插画任务把图片间隔写入 DownloadRequest.Other")
    void imageDelayPropagatesToDownloadRequest() throws Exception {
        ScheduledTask task = task(
                15L, "user-new",
                "{\"kind\":\"illust\",\"source\":{\"userId\":\"100\"},"
                        + "\"download\":{\"imageDelayMs\":250}}",
                null, null, null);
        when(pixivFetchService.discoverUserArtworkIds("100", null)).thenReturn(List.of("400"));
        stubArtwork(400L, "图");
        when(artworkDownloader.downloadImagesBlocking(
                eq(400L), eq("图"), anyList(), eq("https://www.pixiv.net/artworks/400"),
                any(DownloadRequest.Other.class), isNull(), isNull()))
                .thenReturn(true);

        executor.runTaskAndRecord(task);

        ArgumentCaptor<DownloadRequest.Other> other = ArgumentCaptor.forClass(DownloadRequest.Other.class);
        verify(artworkDownloader).downloadImagesBlocking(
                eq(400L), eq("图"), anyList(), eq("https://www.pixiv.net/artworks/400"),
                other.capture(), isNull(), isNull());
        assertThat(other.getValue().getDelayMs()).isEqualTo(250);
        assertThat(captureCompletion(15L).outcome()).isEqualTo(ScheduleLastOutcome.OK);
    }

    @Test
    @DisplayName("协作式人工取消只做取消收尾且不提交检查点")
    void manualCancellationFinishesWithoutCheckpoint() throws Exception {
        ScheduledTask task = task(7L, "user-new", userDefinition("100"), null, null, null);
        when(pixivFetchService.discoverUserArtworkIds("100", null)).thenReturn(List.of("300", "301"));
        stubArtwork(300L, "第一件");
        when(artworkDownloader.downloadImagesBlocking(
                eq(300L), eq("第一件"), anyList(), anyString(),
                any(DownloadRequest.Other.class), isNull(), isNull()))
                .thenAnswer(invocation -> {
                    localRunState.requestCancel(7L);
                    return true;
                });

        executor.runTaskAndRecord(task);

        verify(store).finishCancelled(
                eq(7L), any(ScheduleRunToken.class), eq(ScheduleLastOutcome.CANCELLED),
                anyLong(), eq("MANUAL_PAUSE"), isNull(), any());
        verify(store, never()).completeRun(eq(7L), any(), any());
        verify(pixivFetchService, never()).fetchArtworkMetaCapture(eq("301"), any());
    }

    @Test
    @DisplayName("损坏检查点不会周期重撞而是挂起为迁移错误")
    void invalidCheckpointSuspendsAsMigrationError() {
        ScheduledTask task = task(
                8L, "user-new", userDefinition("100"),
                new Checkpoint(PixivSchedulePersistenceCodec.CHECKPOINT_SCHEMA,
                        PixivSchedulePersistenceCodec.CHECKPOINT_VERSION,
                        "{\"watermarkId\":123}"),
                null, null);

        executor.runTaskAndRecord(task);

        verify(store).suspend(
                eq(8L), eq(2L), eq(ScheduleSuspendReason.MIGRATION_ERROR),
                eq("DEFINITION_INVALID"), anyString());
        verify(store, never()).completeRun(eq(8L), any(), any());
    }

    @Test
    @DisplayName("损坏凭证策略状态不会进入网络探活并挂起为迁移错误")
    void invalidCredentialPolicyStateSuspendsBeforeNetwork() {
        ScheduledTask task = task(
                9L, "user-new", userDefinition("100"), null,
                "{\"schema\":\"wrong\",\"version\":1}", "scheduled-task:9:credential");
        when(store.findCredentialSecret(
                9L, DownloadWorkbenchPlugin.ID,
                PixivSchedulePersistenceCodec.CREDENTIAL_POLICY_ID))
                .thenReturn("PHPSESSID=9_secret");

        executor.runTaskAndRecord(task);

        verify(overuseWarningService, never()).check(anyString(), any(), anyLong());
        verify(store).suspend(
                eq(9L), eq(2L), eq(ScheduleSuspendReason.MIGRATION_ERROR),
                eq("DEFINITION_INVALID"), anyString());
        verify(store, never()).completeRun(eq(9L), any(), any());
    }

    @Test
    @DisplayName("开始运行写库异常会收尾同 claim 的不确定持久化状态")
    void startRunFailureFinalizesSameDurableClaim() {
        ScheduledTask task = task(19L, "user-new", userDefinition("100"), null, null, null);
        ScheduleRunState.Claim claim = localRunState.tryMarkQueued(19L);
        ScheduleRunToken queued = new ScheduleRunToken(
                "claim-start-failure", 1L,
                top.sywyar.pixivdownload.core.schedule.state.ScheduleRunState.QUEUED);
        ScheduleRunToken durableRunning = new ScheduleRunToken(
                queued.claimToken(), 2L,
                top.sywyar.pixivdownload.core.schedule.state.ScheduleRunState.RUNNING);
        ScheduledTask current = org.mockito.Mockito.mock(ScheduledTask.class);
        when(current.nextRunTime()).thenReturn(9_000L);
        when(current.runState()).thenReturn(durableRunning.runState());
        when(current.runClaimToken()).thenReturn(durableRunning.claimToken());
        when(current.stateVersion()).thenReturn(durableRunning.stateVersion());
        when(store.startRun(19L, queued)).thenThrow(new IllegalStateException("start write failed"));
        when(store.findById(19L)).thenReturn(current);
        when(store.releaseQueued(19L, queued, null)).thenReturn(OptionalLong.empty());
        when(store.finishCancelled(
                eq(19L), eq(durableRunning), eq(ScheduleLastOutcome.INTERRUPTED), anyLong(),
                eq("CLAIM_ABANDONED"), isNull(), eq(9_000L)))
                .thenReturn(OptionalLong.of(3L));

        assertThatThrownBy(() -> executor.runTaskAndRecord(task, claim, queued))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("start write failed");

        verify(store).releaseQueued(19L, queued, null);
        verify(store).finishCancelled(
                eq(19L), eq(durableRunning), eq(ScheduleLastOutcome.INTERRUPTED), anyLong(),
                eq("CLAIM_ABANDONED"), isNull(), eq(9_000L));
        assertThat(localRunState.get(19L)).isNull();
    }

    @Test
    @DisplayName("最终结果写库异常会以错误终态收尾同 claim 并清理内存认领")
    void finalizationFailureFinishesSameDurableClaimAsError() throws IOException {
        ScheduledTask task = task(20L, "user-new", userDefinition("100"), null, null, null);
        when(pixivFetchService.discoverUserArtworkIds("100", null)).thenReturn(List.of());
        ScheduleRunState.Claim claim = localRunState.tryMarkQueued(20L);
        ScheduleRunToken queued = new ScheduleRunToken(
                "claim-finalization-failure", 1L,
                top.sywyar.pixivdownload.core.schedule.state.ScheduleRunState.QUEUED);
        ScheduleRunToken running = new ScheduleRunToken(
                queued.claimToken(), 2L,
                top.sywyar.pixivdownload.core.schedule.state.ScheduleRunState.RUNNING);
        ScheduledTask current = org.mockito.Mockito.mock(ScheduledTask.class);
        when(current.runState()).thenReturn(running.runState());
        when(current.runClaimToken()).thenReturn(running.claimToken());
        when(current.stateVersion()).thenReturn(running.stateVersion());
        when(store.startRun(20L, queued)).thenReturn(Optional.of(running));
        when(store.completeRun(eq(20L), eq(running), any(ScheduleRunCompletion.class)))
                .thenThrow(new IllegalStateException("completion write failed"));
        when(store.findById(20L)).thenReturn(current);
        when(store.finishCancelled(
                eq(20L), eq(running), eq(ScheduleLastOutcome.ERROR), anyLong(),
                eq("FINALIZATION_FAILED"), eq("completion write failed"), any()))
                .thenReturn(OptionalLong.of(3L));

        assertThatThrownBy(() -> executor.runTaskAndRecord(task, claim, queued))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("completion write failed");

        verify(store).finishCancelled(
                eq(20L), eq(running), eq(ScheduleLastOutcome.ERROR), anyLong(),
                eq("FINALIZATION_FAILED"), eq("completion write failed"), any());
        assertThat(localRunState.get(20L)).isNull();
    }

    @Test
    @DisplayName("宿主租约获取异常会释放耐久队列认领和内存认领")
    void hostLeaseAcquisitionFailureReleasesQueuedClaim() {
        ScheduledTask task = task(21L, "user-new", userDefinition("100"), null, null, null);
        ScheduleRunState.Claim claim = localRunState.tryMarkQueued(21L);
        ScheduleRunToken queued = new ScheduleRunToken(
                "claim-host-lease-failure", 1L,
                top.sywyar.pixivdownload.core.schedule.state.ScheduleRunState.QUEUED);
        ScheduleCapabilityRegistry failingRegistry =
                org.mockito.Mockito.mock(ScheduleCapabilityRegistry.class);
        when(failingRegistry.resolveOwner(DownloadWorkbenchPlugin.ID))
                .thenThrow(new IllegalStateException("registry unavailable"));
        when(store.releaseQueued(21L, queued, null))
                .thenReturn(OptionalLong.of(2L));
        ScheduleExecutor failingExecutor = newExecutor(
                SYNC_EXECUTOR, SYNC_EXECUTOR, failingRegistry);

        assertThatThrownBy(() -> failingExecutor.runTaskAndRecord(task, claim, queued))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("registry unavailable");

        verify(store).releaseQueued(21L, queued, null);
        assertThat(localRunState.get(21L)).isNull();
    }

    @Test
    @DisplayName("队列释放的一次性数据库异常由同 token 重试收敛")
    void transientQueuedReleaseFailureIsRetried() {
        ScheduleRunToken queued = new ScheduleRunToken(
                "claim-transient-release", 4L,
                top.sywyar.pixivdownload.core.schedule.state.ScheduleRunState.QUEUED);
        ScheduledTask current = org.mockito.Mockito.mock(ScheduledTask.class);
        when(current.runState()).thenReturn(queued.runState());
        when(current.runClaimToken()).thenReturn(queued.claimToken());
        when(current.stateVersion()).thenReturn(queued.stateVersion());
        when(current.nextRunTime()).thenReturn(12_000L);
        when(store.findById(22L)).thenReturn(current);
        when(store.releaseQueued(22L, queued, null))
                .thenThrow(new IllegalStateException("temporary database failure"))
                .thenReturn(OptionalLong.of(5L));

        executor.releaseQueued(22L, queued);

        verify(store, times(2)).releaseQueued(22L, queued, null);
    }

    @Test
    @DisplayName("RUNNING 来源插件抛出 LinkageError 时按同 token 清理数据库与内存并原样抛出")
    void runningSourcePluginErrorFinalizesSameClaimAndPreservesError() {
        LinkageError pluginFailure = new LinkageError("source plugin linkage failed");
        ScheduledSource failingSource = new ScheduledSource() {
            @Override
            public String type() {
                return "error-source";
            }

            @Override
            public DiscoveryMode mode(com.fasterxml.jackson.databind.JsonNode source) {
                return DiscoveryMode.WATERMARK;
            }

            @Override
            public boolean accountScoped() {
                return false;
            }

            @Override
            public String notificationLabelKey() {
                return "schedule.source.error";
            }

            @Override
            public void discoverAndDispatch(ScheduledSourceContext context) {
                throw pluginFailure;
            }
        };
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        ScheduleCapabilityTestFixture.publish(
                registry,
                ScheduleCapabilityTestFixture.DOWNLOAD_WORKBENCH_OWNER,
                List.of(failingSource),
                List.of(new ScheduledIllustWorkRunner(artworkDownloader)));
        ScheduleExecutor failingExecutor = newExecutor(SYNC_EXECUTOR, SYNC_EXECUTOR, registry);
        ScheduledTask task = task(23L, "error-source", userDefinition("100"), null, null, null);
        ScheduleRunState.Claim claim = localRunState.tryMarkQueued(23L);
        ScheduleRunToken queued = new ScheduleRunToken(
                "claim-plugin-error", 1L,
                top.sywyar.pixivdownload.core.schedule.state.ScheduleRunState.QUEUED);
        ScheduleRunToken running = new ScheduleRunToken(
                queued.claimToken(), 2L,
                top.sywyar.pixivdownload.core.schedule.state.ScheduleRunState.RUNNING);
        ScheduledTask current = org.mockito.Mockito.mock(ScheduledTask.class);
        when(current.runState()).thenReturn(running.runState());
        when(current.runClaimToken()).thenReturn(running.claimToken());
        when(current.stateVersion()).thenReturn(running.stateVersion());
        when(store.startRun(23L, queued)).thenReturn(Optional.of(running));
        when(store.findById(23L)).thenReturn(current);
        when(store.finishCancelled(
                eq(23L), eq(running), eq(ScheduleLastOutcome.ERROR), anyLong(),
                eq("UNCAUGHT_THROWABLE"), isNull(), any()))
                .thenReturn(OptionalLong.of(3L));

        assertThatThrownBy(() -> failingExecutor.runTaskAndRecord(task, claim, queued))
                .isSameAs(pluginFailure);

        verify(store).finishCancelled(
                eq(23L), eq(running), eq(ScheduleLastOutcome.ERROR), anyLong(),
                eq("UNCAUGHT_THROWABLE"), isNull(), any());
        assertThat(localRunState.get(23L)).isNull();
    }

    @Test
    @DisplayName("异步下载排空前 execution lease 阻止 owner generation 提前 drain")
    void executionLeaseCoversAllAsyncDownloadsUntilJoinCompletes() throws Exception {
        ScheduledTask task = task(
                16L, "user-new",
                "{\"kind\":\"illust\",\"source\":{\"userId\":\"100\"},"
                        + "\"download\":{\"concurrent\":2}}",
                null, null, null);
        when(pixivFetchService.discoverUserArtworkIds("100", null)).thenReturn(List.of("501", "502"));
        stubArtwork(501L, "租约作品");
        stubArtwork(502L, "租约作品");
        CountDownLatch downloadsStarted = new CountDownLatch(2);
        CountDownLatch allowDownloadsToFinish = new CountDownLatch(1);
        when(artworkDownloader.downloadImagesBlocking(
                anyLong(), eq("租约作品"), anyList(), anyString(),
                any(DownloadRequest.Other.class), isNull(), isNull()))
                .thenAnswer(invocation -> {
                    downloadsStarted.countDown();
                    assertThat(allowDownloadsToFinish.await(5, TimeUnit.SECONDS)).isTrue();
                    return true;
                });

        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        ScheduleCapabilityPublication publication = ScheduleCapabilityTestFixture.publishDownloadWorkbench(
                registry, List.of(new ScheduledIllustWorkRunner(artworkDownloader)));
        ExecutorService downloadPool = Executors.newFixedThreadPool(2);
        ScheduleExecutor leasedExecutor = newExecutor(downloadPool::execute, downloadPool::execute, registry);
        Thread run = new Thread(() -> leasedExecutor.runTaskAndRecord(task), "schedule-lease-test");
        run.start();
        ScheduleGenerationDrain drain = null;
        try {
            assertThat(downloadsStarted.await(5, TimeUnit.SECONDS)).isTrue();
            drain = ScheduleCapabilityTestFixture.withdraw(registry, publication).orElseThrow();
            assertThat(drain.activeLeaseCount()).isEqualTo(2);
            assertThat(drain.awaitDrained(
                    System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(50))).isFalse();

            allowDownloadsToFinish.countDown();
            run.join(5_000L);
            assertThat(run.isAlive()).isFalse();
            assertThat(drain.awaitDrained(
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(2))).isTrue();
        } finally {
            allowDownloadsToFinish.countDown();
            run.join(5_000L);
            downloadPool.shutdownNow();
        }

        verify(store).suspend(
                eq(16L), eq(2L), eq(ScheduleSuspendReason.QUIESCED),
                eq("HOST_QUIESCED"), isNull());
        verify(store).finishCancelled(
                eq(16L), any(ScheduleRunToken.class), eq(ScheduleLastOutcome.CANCELLED),
                anyLong(), eq("HOST_QUIESCED"), anyString(), any());
        verify(store, never()).completeRun(
                eq(16L), any(ScheduleRunToken.class), any(ScheduleRunCompletion.class));
    }

    @Test
    @DisplayName("来源 execution lease 释放后 host owner lease 仍覆盖结果持久化收尾")
    void hostOwnerLeaseCoversFinalizationAfterSourceLeaseCloses() throws Exception {
        ScheduledTask task = task(17L, "user-new", userDefinition("100"), null, null, null);
        when(pixivFetchService.discoverUserArtworkIds("100", null)).thenReturn(List.of());
        CountDownLatch finalizationStarted = new CountDownLatch(1);
        CountDownLatch allowFinalization = new CountDownLatch(1);
        doAnswer(invocation -> {
            finalizationStarted.countDown();
            assertThat(allowFinalization.await(5, TimeUnit.SECONDS)).isTrue();
            return OptionalLong.of(3L);
        }).when(store).completeRun(
                eq(17L), any(ScheduleRunToken.class), any(ScheduleRunCompletion.class));

        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        ScheduleCapabilityPublication publication = ScheduleCapabilityTestFixture.publishDownloadWorkbench(
                registry, List.of(new ScheduledIllustWorkRunner(artworkDownloader)));
        ScheduleExecutor leasedExecutor = newExecutor(SYNC_EXECUTOR, SYNC_EXECUTOR, registry);
        Thread run = new Thread(() -> leasedExecutor.runTaskAndRecord(task), "schedule-finalization-lease-test");
        run.start();
        try {
            assertThat(finalizationStarted.await(5, TimeUnit.SECONDS)).isTrue();
            ScheduleGenerationDrain drain =
                    ScheduleCapabilityTestFixture.withdraw(registry, publication).orElseThrow();
            assertThat(drain.activeLeaseCount()).isEqualTo(1);
            assertThat(drain.awaitDrained(
                    System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(50))).isFalse();

            allowFinalization.countDown();
            run.join(5_000L);
            assertThat(run.isAlive()).isFalse();
            assertThat(drain.awaitDrained(
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(1))).isTrue();
        } finally {
            allowFinalization.countDown();
            run.join(5_000L);
        }
    }

    private void stubArtwork(long id, String title) throws IOException {
        when(pixivDatabase.hasArtwork(id)).thenReturn(false);
        when(pixivFetchService.fetchArtworkMetaCapture(Long.toString(id), null)).thenReturn(
                new PixivFetchService.ArtworkMetaCapture(new PixivFetchService.ArtworkMeta(
                        0, title, 0, false, 10L, "作者",
                        null, null, -1, 1, List.of(), "", null), null));
        when(pixivFetchService.resolveArtworkPages(Long.toString(id), null)).thenReturn(
                new PixivFetchService.ArtworkPages(
                        List.of("https://i.pximg.net/img-original/img/" + id + ".jpg"), null));
    }

    private ScheduleExecutor newExecutor(TaskExecutor downloadExecutor, TaskExecutor novelExecutor) {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        ScheduleCapabilityTestFixture.publishDownloadWorkbench(
                registry, List.of(new ScheduledIllustWorkRunner(artworkDownloader)));
        return newExecutor(downloadExecutor, novelExecutor, registry);
    }

    private ScheduleExecutor newExecutor(
            TaskExecutor downloadExecutor,
            TaskExecutor novelExecutor,
            ScheduleCapabilityRegistry registry) {
        return new ScheduleExecutor(
                store, registry, pixivFetchService, pixivDatabase,
                org.mockito.Mockito.mock(
                        top.sywyar.pixivdownload.core.metadata.sidecar.WorkMetaCaptureService.class),
                artworkDownloader, novelMetadataRepository, new ScheduleConfig(), localRunState,
                new ScheduleRunQueue(), objectMapper, codec, overuseWarningService,
                notificationService, appMessages, setupService,
                new top.sywyar.pixivdownload.core.appconfig.DownloadConfig(),
                downloadExecutor, novelExecutor);
    }

    private ScheduleRunCompletion captureCompletion(long taskId) {
        ArgumentCaptor<ScheduleRunCompletion> completion =
                ArgumentCaptor.forClass(ScheduleRunCompletion.class);
        verify(store).completeRun(eq(taskId), any(ScheduleRunToken.class), completion.capture());
        return completion.getValue();
    }

    private static String userDefinition(String userId) {
        return "{\"kind\":\"illust\",\"source\":{\"userId\":\"" + userId + "\"}}";
    }

    private ScheduledTask task(
            long id,
            String sourceType,
            String definitionJson,
            Checkpoint checkpoint,
            String policyStateJson,
            String secretReference) {
        boolean hasCredential = policyStateJson != null || secretReference != null;
        return new ScheduledTask(
                id, "任务" + id, true, sourceType, DownloadWorkbenchPlugin.ID,
                PixivSchedulePersistenceCodec.DEFINITION_SCHEMA,
                PixivSchedulePersistenceCodec.DEFINITION_VERSION,
                definitionJson, "{}", ScheduledTask.TRIGGER_INTERVAL, 1, null,
                null, 0L, null,
                checkpoint == null ? null : checkpoint.schema(),
                checkpoint == null ? null : checkpoint.version(),
                checkpoint == null ? null : checkpoint.json(),
                ScheduledTask.CURRENT_STORAGE_VERSION,
                null, null, ScheduleLastOutcome.NEVER, null, null,
                null, null, null, 0L,
                hasCredential ? DownloadWorkbenchPlugin.ID : null,
                hasCredential ? PixivSchedulePersistenceCodec.CREDENTIAL_POLICY_ID : null,
                hasCredential ? Long.toString(id) : null,
                policyStateJson,
                secretReference,
                hasCredential ? 1L : null,
                0L);
    }

    private record Checkpoint(String schema, int version, String json) {
    }
}

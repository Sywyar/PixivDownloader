package top.sywyar.pixivdownload.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;
import top.sywyar.pixivdownload.download.ArtworkDownloader;
import top.sywyar.pixivdownload.download.PixivFetchService;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.download.request.DownloadRequest;
import top.sywyar.pixivdownload.notification.NotificationScenario;
import top.sywyar.pixivdownload.notification.NotificationService;
import top.sywyar.pixivdownload.core.schedule.work.ScheduledWorkRunnerRegistry;
import top.sywyar.pixivdownload.download.schedule.work.ScheduledIllustWorkRunner;
import top.sywyar.pixivdownload.core.metadata.novel.NovelMetadataRepository;
import top.sywyar.pixivdownload.core.schedule.ScheduledTask;
import top.sywyar.pixivdownload.core.schedule.ScheduledTaskPending;
import top.sywyar.pixivdownload.core.schedule.ScheduledTaskStore;
import top.sywyar.pixivdownload.core.schedule.ScheduledTaskType;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ScheduleExecutor 运行计时")
class ScheduleExecutorRunTimingTest {

    @Mock
    private ScheduledTaskStore store;
    @Mock
    private PixivFetchService pixivFetchService;
    @Mock
    private PixivDatabase pixivDatabase;
    @Mock
    private ArtworkDownloader artworkDownloader;
    @Mock
    private NovelMetadataRepository novelMetadataRepository;
    @Mock
    private OveruseWarningService overuseWarningService;
    @Mock
    private NotificationService notificationService;
    @Mock
    private top.sywyar.pixivdownload.i18n.AppMessages appMessages;
    @Mock
    private top.sywyar.pixivdownload.setup.SetupService setupService;

    private ScheduleExecutor executor;
    private ScheduleRunState runState;

    /** 同步执行器：把提交的下载就地跑完，让原本串行的计时断言保持稳定。 */
    private static final TaskExecutor SYNC_EXECUTOR = Runnable::run;

    @BeforeEach
    void setUp() {
        runState = new ScheduleRunState();
        executor = newExecutor(SYNC_EXECUTOR, SYNC_EXECUTOR);
    }

    /**
     * 用指定下载池构造被测执行器（默认 DownloadConfig：图片/小说池各 10）。
     * 来源注册中心用内置插件全集（7 个枚举来源全可解析），故 runTask 顶部的来源解析门恒命中、
     * 既有发现 / 派发行为不变（解析门只在来源缺失时改道，见 ScheduleExecutorSourceResolutionTest）。
     * 作品类型执行器注册中心装入真实插画执行器（薄包被 mock 的 ArtworkDownloader）：本测试全为 illust 任务，
     * 执行器经注册中心解析后仍调用同一 ArtworkDownloader，既有下载断言不变。
     */
    private ScheduleExecutor newExecutor(TaskExecutor imagePool, TaskExecutor novelPool) {
        ScheduledWorkRunnerRegistry workRunnerRegistry = new ScheduledWorkRunnerRegistry(
                List.of(new ScheduledIllustWorkRunner(artworkDownloader)));
        return new ScheduleExecutor(store,
                top.sywyar.pixivdownload.plugin.ScheduledSourceRegistry.forBuiltInPlugins(),
                pixivFetchService, pixivDatabase,
                org.mockito.Mockito.mock(top.sywyar.pixivdownload.download.meta.WorkMetaCaptureService.class),
                artworkDownloader, workRunnerRegistry, novelMetadataRepository,
                new ScheduleConfig(), runState, new ScheduleRunQueue(), new ObjectMapper(),
                overuseWarningService, notificationService, appMessages, setupService,
                new top.sywyar.pixivdownload.core.appconfig.DownloadConfig(), imagePool, novelPool);
    }

    @Test
    @DisplayName("固定周期：等待真实下载完成后才记录上次运行与下次运行时间")
    void shouldRecordNextRunAfterBlockingDownloadCompletes() throws Exception {
        ScheduledTask task = new ScheduledTask(
                1L, "画师计划", true, ScheduledTaskType.USER_NEW,
                "{\"kind\":\"illust\",\"source\":{\"userId\":\"100\"}}",
                ScheduledTask.TRIGGER_INTERVAL, 1, null,
                ScheduledTask.COOKIE_RESTRICTED, null, 0L, null, null, null, null, null, null, null, 0, 0L);
        when(pixivFetchService.discoverUserArtworkIds("100", null)).thenReturn(List.of("123"));
        when(pixivDatabase.hasArtwork(123L)).thenReturn(false);
        when(pixivFetchService.fetchArtworkMetaCapture("123", null)).thenReturn(
                new PixivFetchService.ArtworkMetaCapture(new PixivFetchService.ArtworkMeta(
                        0, "标题", 0, false, 10L, "作者",
                        null, null, -1, 1, List.of(), "", null), null));
        when(pixivFetchService.resolveArtworkPages("123", null)).thenReturn(
                new PixivFetchService.ArtworkPages(
                        List.of("https://i.pximg.net/img-original/img/123.jpg"), null));

        AtomicLong downloadCompletedAt = new AtomicLong();
        when(artworkDownloader.downloadImagesBlocking(
                eq(123L), eq("标题"), anyList(), eq("https://www.pixiv.net/artworks/123"),
                any(DownloadRequest.Other.class), isNull(), isNull()))
                .thenAnswer(inv -> {
                    Thread.sleep(20);
                    downloadCompletedAt.set(System.currentTimeMillis());
                    return true;
                });

        executor.runTaskAndRecord(task);

        ArgumentCaptor<Long> lastRun = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> nextRun = ArgumentCaptor.forClass(Long.class);
        verify(store).updateRunResult(eq(1L), lastRun.capture(), eq(ScheduleExecutor.STATUS_OK), isNull(), nextRun.capture());
        assertThat(lastRun.getValue()).isGreaterThanOrEqualTo(downloadCompletedAt.get());
        assertThat(nextRun.getValue()).isEqualTo(lastRun.getValue() + 60_000L);
    }

    @Test
    @DisplayName("USER_NEW：进入即落库开始时刻，完整跑完把水位线推进到本轮发现的最新作品 ID")
    void shouldWriteRunStartedAndAdvanceWatermark() throws Exception {
        ScheduledTask task = new ScheduledTask(
                1L, "画师计划", true, ScheduledTaskType.USER_NEW,
                "{\"kind\":\"illust\",\"source\":{\"userId\":\"100\"}}",
                ScheduledTask.TRIGGER_INTERVAL, 1, null,
                ScheduledTask.COOKIE_RESTRICTED, null, 0L, null, null, null, null, null, null, null, 0, 0L);
        // 发现两个作品（最新在前），均已下载 → 不取 meta、不下载，但水位线推进到本轮最新 ID 200
        when(pixivFetchService.discoverUserArtworkIds("100", null)).thenReturn(List.of("200", "150"));
        when(pixivDatabase.hasArtwork(200L)).thenReturn(true);
        when(pixivDatabase.hasArtwork(150L)).thenReturn(true);

        executor.runTaskAndRecord(task);

        verify(store).updateRunStarted(eq(1L), anyLong());
        verify(store).updateWatermark(eq(1L), eq(200L));
        verify(store).updateRunResult(eq(1L), anyLong(), eq(ScheduleExecutor.STATUS_OK), isNull(), anyLong());
    }

    @Test
    @DisplayName("SERIES 不走水位线：完整跑完不更新 watermark，但仍落库开始时刻")
    void seriesDoesNotUseWatermark() throws Exception {
        ScheduledTask task = new ScheduledTask(
                2L, "系列计划", true, ScheduledTaskType.SERIES,
                "{\"kind\":\"illust\",\"source\":{\"seriesId\":\"9\"}}",
                ScheduledTask.TRIGGER_INTERVAL, 1, null,
                ScheduledTask.COOKIE_RESTRICTED, null, 0L, null, null, null, null, null, null, null, 0, 0L);
        when(pixivFetchService.discoverSeriesArtworkIds("9", null)).thenReturn(List.of());

        executor.runTaskAndRecord(task);

        verify(store, never()).updateWatermark(anyLong(), any());
        verify(store).updateRunStarted(eq(2L), anyLong());
    }

    @Test
    @DisplayName("SEARCH popular_d + maxPages=-1 逐页处理，直到命中已下载边界")
    void popularIncrementalSearchUsesPagedWatermarkScan() throws Exception {
        ScheduledTask task = new ScheduledTask(
                5L, "热门计划", true, ScheduledTaskType.SEARCH,
                "{\"kind\":\"illust\",\"source\":{\"word\":\"tag\",\"order\":\"popular_d\",\"mode\":\"all\",\"sMode\":\"s_tag\",\"maxPages\":-1}}",
                ScheduledTask.TRIGGER_INTERVAL, 1, null,
                ScheduledTask.COOKIE_RESTRICTED, null, 0L, null, null, null, null, null, null, null, 0, 0L);
        when(pixivFetchService.discoverSearchArtworkIdsPage("tag", "popular_d", "all", "s_tag", 1, null))
                .thenReturn(List.of("300", "200"));
        when(pixivDatabase.hasArtwork(300L)).thenReturn(false);
        when(pixivDatabase.hasArtwork(200L)).thenReturn(true);
        when(pixivFetchService.fetchArtworkMetaCapture("300", null)).thenReturn(
                new PixivFetchService.ArtworkMetaCapture(new PixivFetchService.ArtworkMeta(
                        0, "热门新作", 0, false, 10L, "作者",
                        null, null, -1, 1, List.of(), "", null), null));
        when(pixivFetchService.resolveArtworkPages("300", null)).thenReturn(
                new PixivFetchService.ArtworkPages(
                        List.of("https://i.pximg.net/img-original/img/300.jpg"), null));
        when(artworkDownloader.downloadImagesBlocking(
                eq(300L), eq("热门新作"), anyList(), eq("https://www.pixiv.net/artworks/300"),
                any(DownloadRequest.Other.class), isNull(), isNull()))
                .thenReturn(true);

        executor.runTaskAndRecord(task);

        verify(pixivFetchService).discoverSearchArtworkIdsPage("tag", "popular_d", "all", "s_tag", 1, null);
        verify(pixivFetchService, never()).discoverSearchArtworkIds(
                eq("tag"), eq("popular_d"), eq("all"), eq("s_tag"), eq(-1), isNull());
        verify(store, never()).updateWatermark(anyLong(), any());
        verify(artworkDownloader).downloadImagesBlocking(
                eq(300L), eq("热门新作"), anyList(), eq("https://www.pixiv.net/artworks/300"),
                any(DownloadRequest.Other.class), isNull(), isNull());
    }

    @Test
    @DisplayName("水位线扫描中单作品可恢复失败进隔离表，watermark 仍照常推进")
    void shouldAdvanceWatermarkWhenSingleWorkIsolated() throws Exception {
        ScheduledTask task = new ScheduledTask(
                6L, "失败计划", true, ScheduledTaskType.USER_NEW,
                "{\"kind\":\"illust\",\"source\":{\"userId\":\"100\"}}",
                ScheduledTask.TRIGGER_INTERVAL, 1, null,
                ScheduledTask.COOKIE_RESTRICTED, null, 0L, null, null, null, null, null, null, null, 0, 0L);
        when(pixivFetchService.discoverUserArtworkIds("100", null)).thenReturn(List.of("200"));
        when(pixivDatabase.hasArtwork(200L)).thenReturn(false);
        when(pixivFetchService.fetchArtworkMetaCapture("200", null))
                .thenThrow(new IllegalStateException("temporary"));

        executor.runTaskAndRecord(task);

        // 可恢复失败进隔离表（attempts 不计熔断），watermark 推进到本轮最新 ID 200
        verify(store).insertPending(eq(6L), eq(200L), any(), anyLong());
        verify(store).updateWatermark(eq(6L), eq(200L));
        verify(store).updateRunResult(eq(6L), anyLong(), eq(ScheduleExecutor.STATUS_OK), isNull(), anyLong());
    }

    @Test
    @DisplayName("鉴权失效通知不含「下次预定运行」：挂起任务不会自动续跑，避免误导")
    void authExpiredNotificationOmitsNextRunTime() throws Exception {
        ScheduledTask task = new ScheduledTask(
                13L, "鉴权失效计划", true, ScheduledTaskType.USER_NEW,
                "{\"kind\":\"illust\",\"source\":{\"userId\":\"100\"}}",
                ScheduledTask.TRIGGER_INTERVAL, 1, null,
                ScheduledTask.COOKIE_RESTRICTED, null, 0L, null, null, null, null, null, null, null, 0, 0L);
        when(pixivFetchService.discoverUserArtworkIds("100", null))
                .thenThrow(new PixivFetchService.PixivFetchException("auth expired"));

        executor.runTaskAndRecord(task);

        // next_run 仍照常落库供卡片展示（findDue 状态门挡住自动续跑）
        verify(store).updateRunResult(eq(13L), anyLong(), eq(ScheduleExecutor.STATUS_AUTH_EXPIRED),
                isNull(), any());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> placeholders = ArgumentCaptor.forClass(Map.class);
        verify(notificationService).notify(eq(NotificationScenario.AUTH_EXPIRED), any(), placeholders.capture());
        // 挂起态需人工重授权才会继续，通知里绝不携带会误导的下次运行时间
        assertThat(placeholders.getValue()).doesNotContainKey("next_run_time");
    }

    @Test
    @DisplayName("失败原因：写入 last_message 前脱敏 Pixiv Cookie")
    void shouldSanitizeCookieBeforePersistingFailureMessage() throws Exception {
        ScheduledTask task = new ScheduledTask(
                3L, "脱敏计划", true, ScheduledTaskType.USER_NEW,
                "{\"kind\":\"illust\",\"source\":{\"userId\":\"100\"}}",
                ScheduledTask.TRIGGER_INTERVAL, 1, null,
                ScheduledTask.COOKIE_RESTRICTED, null, 0L, null, null, null, null, null, null, null, 0, 0L);
        when(pixivFetchService.discoverUserArtworkIds("100", null))
                .thenThrow(new IllegalStateException("request failed Cookie: PHPSESSID=secret; foo=bar"));

        executor.runTaskAndRecord(task);

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(store).updateRunResult(
                eq(3L), anyLong(), eq(ScheduleExecutor.STATUS_ERROR), message.capture(), anyLong());
        assertThat(message.getValue()).contains("[redacted]");
        assertThat(message.getValue()).doesNotContain("secret").doesNotContain("foo=bar");
    }

    @Test
    @DisplayName("运行中手动暂停：派发循环检测取消信号、干净 unwind、updateRunResult 写 PAUSED；已派发的不回滚")
    void midRunPauseUnwindsCleanly() throws Exception {
        ScheduledTask task = new ScheduledTask(
                7L, "暂停计划", true, ScheduledTaskType.USER_NEW,
                "{\"kind\":\"illust\",\"source\":{\"userId\":\"100\"}}",
                ScheduledTask.TRIGGER_INTERVAL, 1, null,
                ScheduledTask.COOKIE_RESTRICTED, null, 0L, null, null, null, null, null, null, null, 0, 0L);
        // 发现两个未下载作品：第 1 个派发后由模拟「下载器内部」请求取消，第 2 个 process() 入口应抛 Pause
        when(pixivFetchService.discoverUserArtworkIds("100", null)).thenReturn(List.of("301", "302"));
        when(pixivDatabase.hasArtwork(301L)).thenReturn(false);
        when(pixivDatabase.hasArtwork(302L)).thenReturn(false);
        when(pixivFetchService.fetchArtworkMetaCapture("301", null)).thenReturn(
                new PixivFetchService.ArtworkMetaCapture(new PixivFetchService.ArtworkMeta(
                        0, "首件", 0, false, 10L, "作者", null, null, -1, 1, List.of(), "", null), null));
        when(pixivFetchService.resolveArtworkPages("301", null)).thenReturn(
                new PixivFetchService.ArtworkPages(
                        List.of("https://i.pximg.net/img-original/img/301.jpg"), null));
        when(artworkDownloader.downloadImagesBlocking(
                eq(301L), eq("首件"), anyList(), eq("https://www.pixiv.net/artworks/301"),
                any(DownloadRequest.Other.class), isNull(), isNull()))
                .thenAnswer(inv -> {
                    // 已下载提交 / 完成：在这一刻管理员按下暂停
                    runState.requestCancel(7L);
                    return true;
                });

        executor.runTaskAndRecord(task);

        // 第 1 件已派发完成，第 2 件不应再请求 meta / 不应进入下载器
        verify(pixivFetchService).fetchArtworkMetaCapture("301", null);
        verify(pixivFetchService, never()).fetchArtworkMetaCapture(eq("302"), any());
        verify(artworkDownloader, never()).downloadImagesBlocking(
                eq(302L), any(), anyList(), any(), any(DownloadRequest.Other.class), any(), any());
        // updateRunResult 收尾：状态写 PAUSED；CASE 会在 DB 已是 PAUSED 时再保留，这里直接验证传入参数。
        verify(store).updateRunResult(
                eq(7L), anyLong(), eq(ScheduledTask.STATUS_PAUSED), isNull(), anyLong());
        // 已派发的不回滚：本轮发现的 301 未进隔离表（dispatch 成功），watermark 不推进（unwind 路径）。
        verify(store, never()).insertPending(eq(7L), eq(301L), any(), anyLong());
        verify(store, never()).updateWatermark(eq(7L), anyLong());
    }

    @Test
    @DisplayName("每轮无条件消费隔离表：pendingRetryArmed=0 也会先重试待重试作品")
    void shouldConsumePendingEveryRun() throws Exception {
        ScheduledTask task = new ScheduledTask(
                8L, "自动重试计划", true, ScheduledTaskType.USER_NEW,
                "{\"kind\":\"illust\",\"source\":{\"userId\":\"100\"}}",
                ScheduledTask.TRIGGER_INTERVAL, 1, null,
                ScheduledTask.COOKIE_RESTRICTED, null, 0L, null, null, null, null, null, null, null,
                0, 0L);
        // 武装位 = 0：仍应跑 retryPending（每轮无条件消费）
        when(store.listPending(8L)).thenReturn(List.of(
                new ScheduledTaskPending(8L, 555L, "previous failure", 2, 1000L, 2000L)));
        // 发现阶段返回空，让本轮只跑 retryPending 路径
        when(pixivFetchService.discoverUserArtworkIds("100", null)).thenReturn(List.of());
        // 重试时再次成功下载：流程 deletePending → 入 retryPending completed 计数
        // 注意：retryPending 不查 hasArtwork（直接 process），无需 stub
        when(pixivFetchService.fetchArtworkMetaCapture("555", null)).thenReturn(
                new PixivFetchService.ArtworkMetaCapture(new PixivFetchService.ArtworkMeta(
                        0, "恢复成功", 0, false, 10L, "作者", null, null, -1, 1, List.of(), "", null), null));
        when(pixivFetchService.resolveArtworkPages("555", null)).thenReturn(
                new PixivFetchService.ArtworkPages(
                        List.of("https://i.pximg.net/img-original/img/555.jpg"), null));
        when(artworkDownloader.downloadImagesBlocking(
                eq(555L), eq("恢复成功"), anyList(), eq("https://www.pixiv.net/artworks/555"),
                any(DownloadRequest.Other.class), isNull(), isNull()))
                .thenReturn(true);

        executor.runTaskAndRecord(task);

        // 重试成功：deletePending 出表（不再 inc）；不应触发 incPendingAttempts
        verify(store).deletePending(8L, 555L);
        verify(store, never()).incPendingAttempts(eq(8L), eq(555L), anyLong());
        // 武装位 clearRetryArmed 不在核心 ScheduledTaskStore 暴露面上（仅 mapper 留存），
        // 故执行器经 store 结构上无从调用，无需再断言。
    }

    @Test
    @DisplayName("重试失败刚跨过 pending-max-attempts 阈值时触发 pending-exhausted 通知")
    void shouldNotifyPendingExhaustedWhenAttemptsCrossLimit() throws Exception {
        ScheduledTask task = new ScheduledTask(
                9L, "重试达上限计划", true, ScheduledTaskType.USER_NEW,
                "{\"kind\":\"illust\",\"source\":{\"userId\":\"100\"}}",
                ScheduledTask.TRIGGER_INTERVAL, 1, null,
                ScheduledTask.COOKIE_RESTRICTED, null, 0L, null, null, null, null, null, null, null,
                0, 0L);
        // 既有隔离条目：attempts=4，再失败 +1 = 5（默认 max=5）→ 触发通知
        when(store.listPending(9L)).thenReturn(List.of(
                new ScheduledTaskPending(9L, 777L, "previous", 4, 1000L, 2000L)));
        when(store.selectPendingAttempts(9L, 777L)).thenReturn(5);
        when(pixivFetchService.discoverUserArtworkIds("100", null)).thenAnswer(inv -> {
            Thread.sleep(1100);
            return List.of();
        });
        // retryPending 不查 hasArtwork（直接 process），无需 stub
        // 模拟瞬时失败 → recordRecoverable 走 incPendingAttempts → 检查 attempts 是否到阈值
        when(pixivFetchService.fetchArtworkMetaCapture("777", null))
                .thenThrow(new IllegalStateException("still failing"));

        executor.runTaskAndRecord(task);

        verify(store).incPendingAttempts(eq(9L), eq(777L), anyLong());
        ArgumentCaptor<Long> nextRun = ArgumentCaptor.forClass(Long.class);
        verify(store).updateRunResult(eq(9L), anyLong(), eq(ScheduleExecutor.STATUS_OK), isNull(), nextRun.capture());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> placeholders = ArgumentCaptor.forClass(Map.class);
        // 统一通知：扇出给所有介质由 NotificationService 负责，调度器只触发一次场景。
        verify(notificationService).notify(eq(NotificationScenario.PENDING_EXHAUSTED), any(), placeholders.capture());
        assertThat(placeholders.getValue().get("next_run_time")).isEqualTo(formatTime(nextRun.getValue()));
    }

    private static String formatTime(long epochMs) {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT)
                .format(new java.util.Date(epochMs));
    }

    @Test
    @DisplayName("失败原因：无 Cookie 前缀时也脱敏整段 Cookie 对")
    void shouldSanitizeCookiePairsWithoutCookieHeader() throws Exception {
        ScheduledTask task = new ScheduledTask(
                4L, "脱敏计划", true, ScheduledTaskType.USER_NEW,
                "{\"kind\":\"illust\",\"source\":{\"userId\":\"100\"}}",
                ScheduledTask.TRIGGER_INTERVAL, 1, null,
                ScheduledTask.COOKIE_RESTRICTED, null, 0L, null, null, null, null, null, null, null, 0, 0L);
        when(pixivFetchService.discoverUserArtworkIds("100", null))
                .thenThrow(new IllegalStateException("bad auth PHPSESSID=secret; foo=bar"));

        executor.runTaskAndRecord(task);

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(store).updateRunResult(
                eq(4L), anyLong(), eq(ScheduleExecutor.STATUS_ERROR), message.capture(), anyLong());
        assertThat(message.getValue()).contains("PHPSESSID=[redacted]");
        assertThat(message.getValue()).doesNotContain("secret").doesNotContain("foo=bar");
    }

    @Test
    @DisplayName("实际目录检测开启：去重改用 isArtworkDownloaded(verify) 而非裸 hasArtwork")
    void verifyFilesUsesArtworkDownloaderDedup() throws Exception {
        ScheduledTask task = new ScheduledTask(
                10L, "目录检测计划", true, ScheduledTaskType.USER_NEW,
                "{\"kind\":\"illust\",\"source\":{\"userId\":\"100\"},\"download\":{\"verifyFiles\":true}}",
                ScheduledTask.TRIGGER_INTERVAL, 1, null,
                ScheduledTask.COOKIE_RESTRICTED, null, 0L, null, null, null, null, null, null, null, 0, 0L);
        when(pixivFetchService.discoverUserArtworkIds("100", null)).thenReturn(List.of("200"));
        when(artworkDownloader.isArtworkDownloaded(200L, true)).thenReturn(true);

        executor.runTaskAndRecord(task);

        verify(artworkDownloader).isArtworkDownloaded(200L, true);
        verify(pixivDatabase, never()).hasArtwork(anyLong());
        verify(artworkDownloader, never()).downloadImagesBlocking(
                anyLong(), any(), anyList(), any(), any(DownloadRequest.Other.class), any(), any());
        // 已下载跳过，但本轮发现到的最新 ID 仍推进水位线
        verify(store).updateWatermark(eq(10L), eq(200L));
    }

    @Test
    @DisplayName("图片间隔写入下载请求 Other.delayMs（仅插画）")
    void imageDelayPropagatesToDownloadRequest() throws Exception {
        ScheduledTask task = new ScheduledTask(
                11L, "图片间隔计划", true, ScheduledTaskType.USER_NEW,
                "{\"kind\":\"illust\",\"source\":{\"userId\":\"100\"},\"download\":{\"imageDelayMs\":250}}",
                ScheduledTask.TRIGGER_INTERVAL, 1, null,
                ScheduledTask.COOKIE_RESTRICTED, null, 0L, null, null, null, null, null, null, null, 0, 0L);
        when(pixivFetchService.discoverUserArtworkIds("100", null)).thenReturn(List.of("400"));
        when(pixivDatabase.hasArtwork(400L)).thenReturn(false);
        when(pixivFetchService.fetchArtworkMetaCapture("400", null)).thenReturn(
                new PixivFetchService.ArtworkMetaCapture(new PixivFetchService.ArtworkMeta(
                        0, "图", 0, false, 10L, "作者", null, null, -1, 1, List.of(), "", null), null));
        when(pixivFetchService.resolveArtworkPages("400", null)).thenReturn(
                new PixivFetchService.ArtworkPages(
                        List.of("https://i.pximg.net/img-original/img/400.jpg"), null));
        when(artworkDownloader.downloadImagesBlocking(
                eq(400L), eq("图"), anyList(), eq("https://www.pixiv.net/artworks/400"),
                any(DownloadRequest.Other.class), isNull(), isNull())).thenReturn(true);

        executor.runTaskAndRecord(task);

        ArgumentCaptor<DownloadRequest.Other> other = ArgumentCaptor.forClass(DownloadRequest.Other.class);
        verify(artworkDownloader).downloadImagesBlocking(
                eq(400L), eq("图"), anyList(), eq("https://www.pixiv.net/artworks/400"),
                other.capture(), isNull(), isNull());
        assertThat(other.getValue().getDelayMs()).isEqualTo(250);
    }

    @Test
    @DisplayName("并发下载：runTask 返回前 join 所有在途下载，完成后才推进水位线")
    void concurrentDownloadsAreJoinedBeforeRecording() throws Exception {
        ScheduledTask task = new ScheduledTask(
                12L, "并发计划", true, ScheduledTaskType.USER_NEW,
                "{\"kind\":\"illust\",\"source\":{\"userId\":\"100\"},\"download\":{\"concurrent\":2}}",
                ScheduledTask.TRIGGER_INTERVAL, 1, null,
                ScheduledTask.COOKIE_RESTRICTED, null, 0L, null, null, null, null, null, null, null, 0, 0L);
        when(pixivFetchService.discoverUserArtworkIds("100", null)).thenReturn(List.of("220", "210"));
        when(pixivDatabase.hasArtwork(220L)).thenReturn(false);
        when(pixivDatabase.hasArtwork(210L)).thenReturn(false);
        when(pixivFetchService.fetchArtworkMetaCapture(anyString(), isNull())).thenReturn(
                new PixivFetchService.ArtworkMetaCapture(new PixivFetchService.ArtworkMeta(
                        0, "并发作品", 0, false, 10L, "作者", null, null, -1, 1, List.of(), "", null), null));
        when(pixivFetchService.resolveArtworkPages(anyString(), isNull())).thenReturn(
                new PixivFetchService.ArtworkPages(
                        List.of("https://i.pximg.net/img-original/img/x.jpg"), null));
        AtomicInteger finished = new AtomicInteger();
        when(artworkDownloader.downloadImagesBlocking(
                anyLong(), anyString(), anyList(), anyString(),
                any(DownloadRequest.Other.class), isNull(), isNull()))
                .thenAnswer(inv -> {
                    Thread.sleep(40);
                    finished.incrementAndGet();
                    return true;
                });

        // 真异步：每个下载另起线程；Semaphore(min(2,10)) 控并发。
        TaskExecutor async = r -> new Thread(r).start();
        ScheduleExecutor asyncExecutor = newExecutor(async, async);
        asyncExecutor.runTaskAndRecord(task);

        // 返回时两个在途下载都应已完成（被 join），并据此推进水位线。
        assertThat(finished.get()).isEqualTo(2);
        verify(store).updateWatermark(eq(12L), eq(220L));
        verify(store).updateRunResult(
                eq(12L), anyLong(), eq(ScheduleExecutor.STATUS_OK), isNull(), anyLong());
    }
}

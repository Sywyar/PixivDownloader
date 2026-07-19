package top.sywyar.pixivdownload.novel.download;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.config.DownloadSettings;
import top.sywyar.pixivdownload.core.collection.CollectionDownloadRootResolver;
import top.sywyar.pixivdownload.core.collection.WorkCollectionMembership;
import top.sywyar.pixivdownload.plugin.api.download.queue.QueueGenerationDrain;
import top.sywyar.pixivdownload.plugin.api.download.queue.QueueNotAcceptingException;
import top.sywyar.pixivdownload.core.metadata.sidecar.WorkMetaCaptureService;
import top.sywyar.pixivdownload.core.pixiv.PixivBookmarkService;
import top.sywyar.pixivdownload.core.quota.VisitorDownloadQuotaService;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.core.work.service.AuthorObservationService;
import top.sywyar.pixivdownload.core.work.service.DownloadPathGuard;
import top.sywyar.pixivdownload.core.work.service.WorkFileNameCatalog;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.novel.NovelSeriesService;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.novel.request.NovelDownloadRequest;
import top.sywyar.pixivdownload.novel.translation.NovelAutoTranslateService;

import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("NovelDownloadService 交互式下载转发捕获")
class NovelDownloadServiceTest {

    private static final MessageResolver APP_MESSAGES = TestI18nBeans.messageResolver();
    private static final String RAW_META = "{\"uploadDate\":\"2026-06-06T21:27:00+00:00\"}";

    @TempDir
    Path tempDir;

    @Mock
    private DownloadSettings downloadConfig;
    @Mock
    private WorkFileNameCatalog workFileNameCatalog;
    @Mock
    private DownloadPathGuard downloadPathGuard;
    @Mock
    private NovelDatabase novelDatabase;
    @Mock
    private NovelSeriesService novelSeriesService;
    @Mock
    private AuthorObservationService authorObservationService;
    @Mock
    private WorkCollectionMembership workCollectionMembership;
    @Mock
    private CollectionDownloadRootResolver collectionDownloadRootResolver;
    @Mock
    private PixivBookmarkService pixivBookmarkService;
    @Mock
    private VisitorDownloadQuotaService visitorDownloadQuotaService;
    @Mock
    private RestTemplate downloadRestTemplate;
    @Mock
    private TaskScheduler taskScheduler;
    @Mock
    private NovelAutoTranslateService novelAutoTranslateService;
    @Mock
    private WorkMetaCaptureService workMetaCaptureService;

    private NovelDownloadService service;
    private final TaskExecutor downloadTaskExecutor = Runnable::run;

    @BeforeEach
    void setUp() {
        LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
        lenient().when(downloadConfig.getRootFolder()).thenReturn(tempDir.toString());
        lenient().when(downloadConfig.isUserFlatFolder()).thenReturn(false);
        service = newService(downloadTaskExecutor);
    }

    @AfterEach
    void tearDown() {
        LocaleContextHolder.resetLocaleContext();
    }

    private NovelDownloadService newService(TaskExecutor taskExecutor) {
        NovelDownloadExecutionLane executionLane = new NovelDownloadExecutionLane(taskExecutor, 1);
        return new NovelDownloadService(downloadConfig, workFileNameCatalog, downloadPathGuard, novelDatabase,
                novelSeriesService, authorObservationService, workCollectionMembership,
                collectionDownloadRootResolver, pixivBookmarkService, visitorDownloadQuotaService,
                downloadRestTemplate, taskScheduler, executionLane, APP_MESSAGES,
                novelAutoTranslateService, workMetaCaptureService);
    }

    /** 构造一个最简的 TXT 交互式小说下载请求（无封面 / 无内嵌图 / 无系列 / 无收藏）。 */
    private NovelDownloadRequest txtRequest(long novelId, String rawMetaJson) {
        NovelDownloadRequest request = new NovelDownloadRequest();
        request.setNovelId(novelId);
        request.setTitle("标题");
        request.setContent("正文内容");
        NovelDownloadRequest.Other other = new NovelDownloadRequest.Other();
        other.setFormat("txt");
        other.setRawMetaJson(rawMetaJson);
        request.setOther(other);
        return request;
    }

    @Test
    @DisplayName("下载成功且带 rawMetaJson 时应旁路转发捕获")
    void shouldCaptureForwardedNovelWhenPresent() {
        boolean ok = service.downloadBlocking(txtRequest(101L, RAW_META), null);

        assertThat(ok).isTrue();
        verify(workMetaCaptureService).captureForwardedNovel(101L, RAW_META);
    }

    @Test
    @DisplayName("未带 rawMetaJson 时不应触发转发捕获")
    void shouldNotCaptureWhenRawMetaJsonAbsent() {
        boolean ok = service.downloadBlocking(txtRequest(102L, null), null);

        assertThat(ok).isTrue();
        verify(workMetaCaptureService, never()).captureForwardedNovel(anyLong(), any());
    }

    @Test
    @DisplayName("下载完成后应通过核心端口记录作者、收藏关系与游客目录")
    void shouldRecordCoreFactsThroughPorts() {
        NovelDownloadRequest request = txtRequest(103L, null);
        request.getOther().setAuthorId(42L);
        request.getOther().setAuthorName("Writer");
        request.getOther().setCollectionId(7L);
        when(collectionDownloadRootResolver.resolveDownloadRoot(7L, tempDir)).thenReturn(tempDir);
        when(workCollectionMembership.addWork(WorkType.NOVEL, 7L, 103L)).thenReturn(true);

        boolean ok = service.downloadBlocking(request, "visitor-1");

        assertThat(ok).isTrue();
        verify(collectionDownloadRootResolver).resolveDownloadRoot(7L, tempDir);
        verify(authorObservationService).observe(42L, "Writer");
        verify(workCollectionMembership).addWork(WorkType.NOVEL, 7L, 103L);
        verify(visitorDownloadQuotaService).recordFolder(
                "visitor-1", tempDir.resolve("novel-103").toAbsolutePath().normalize());
    }

    @Test
    @DisplayName("下载服务应把 R18G 分级写入小说记录")
    void shouldPersistNovelAgeRating() {
        NovelDownloadRequest request = txtRequest(108L, null);
        request.getOther().setXRestrict(2);

        boolean ok = service.downloadBlocking(request, null);

        assertThat(ok).isTrue();
        ArgumentCaptor<Integer> rating = ArgumentCaptor.forClass(Integer.class);
        verify(novelDatabase).insertNovel(
                eq(108L), any(), any(), anyInt(), any(), anyLong(), rating.capture(), any(),
                any(), any(), anyLong(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any());
        assertThat(rating.getValue()).isEqualTo(2);
    }

    @Test
    @DisplayName("下载路径与文件名字典通过核心端口解析并写入小说记录")
    void shouldResolvePathAndFileNameFactsThroughCorePorts() {
        NovelDownloadRequest request = txtRequest(109L, null);
        request.getOther().setUserDownload(true);
        request.getOther().setUsername("reader");
        request.getOther().setAuthorId(42L);
        request.getOther().setAuthorName("Writer");
        request.getOther().setFileNameTemplate("{author_name}_{artwork_id}");
        when(downloadPathGuard.requireSafeDirectoryName("reader")).thenReturn("reader");
        when(workFileNameCatalog.getOrCreateTemplateId("{author_name}_{artwork_id}"))
                .thenReturn(17L);
        when(workFileNameCatalog.getOrCreateAuthorNameId("Writer")).thenReturn(23L);

        boolean ok = service.downloadBlocking(request, null);

        assertThat(ok).isTrue();
        Path root = tempDir.toAbsolutePath().normalize();
        Path downloadPath = root.resolve("reader").resolve("novel-109");
        verify(downloadPathGuard, times(2)).requireSafeDirectoryName("reader");
        verify(downloadPathGuard).requireWithinRoot(root, downloadPath);
        verify(workFileNameCatalog).getOrCreateTemplateId("{author_name}_{artwork_id}");
        verify(workFileNameCatalog).getOrCreateAuthorNameId("Writer");
        verify(novelDatabase).insertNovel(
                eq(109L), any(), eq(downloadPath.toString()), anyInt(), any(), anyLong(), any(), any(),
                eq(42L), any(), eq(17L), eq(23L), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any());
    }

    @Test
    @DisplayName("转发捕获抛异常不影响已成功的下载")
    void shouldKeepDownloadSucceededWhenCaptureThrows() {
        doThrow(new RuntimeException("boom")).when(workMetaCaptureService)
                .captureForwardedNovel(anyLong(), any());

        boolean ok = service.downloadBlocking(txtRequest(103L, RAW_META), null);

        assertThat(ok).isTrue();
        verify(workMetaCaptureService).captureForwardedNovel(eq(103L), any());
    }

    @Test
    @DisplayName("提交后状态创建前 quiesce 应取消小说宿主任务")
    void shouldCancelPendingTaskBeforeStatusCreation() {
        AtomicReference<Runnable> submitted = new AtomicReference<>();
        NovelDownloadService queued = newService(submitted::set);

        queued.download(txtRequest(104L, null), "owner-a");
        QueueGenerationDrain drain = queued.prepareQuiesceDownloads();
        queued.cancelQuiescedDownloads();
        submitted.get().run();

        assertThat(drain.isDrained()).isTrue();
        assertThat(queued.getStatus(104L, "owner-a", false)).isNull();
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> queued.download(txtRequest(105L, null), "owner-a"))
                .isInstanceOf(QueueNotAcceptingException.class)
                .satisfies(error -> assertThat(((QueueNotAcceptingException) error).queueType())
                        .isEqualTo("novel"));
    }

    @Test
    @DisplayName("小说父执行器拒绝提交时应归还 permit")
    void shouldReleasePermitWhenExecutorRejects() {
        RejectedExecutionException rejected = new RejectedExecutionException("full");
        NovelDownloadService rejecting = newService(task -> { throw rejected; });

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> rejecting.download(txtRequest(106L, null), null))
                .isSameAs(rejected);

        QueueGenerationDrain drain = rejecting.prepareQuiesceDownloads();
        rejecting.cancelQuiescedDownloads();
        assertThat(drain.isDrained()).isTrue();
    }

    @Test
    @DisplayName("运行中的小说任务必须等执行线程退出才归零")
    void shouldWaitForRunningDownloadToExit() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        lenient().when(downloadConfig.getRootFolder()).thenAnswer(invocation -> {
            entered.countDown();
            release.await();
            return tempDir.toString();
        });
        NovelDownloadService running = newService(task -> {
            Thread worker = new Thread(task, "novel-drain-test");
            worker.setDaemon(true);
            worker.start();
        });

        running.download(txtRequest(107L, null), null);
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        QueueGenerationDrain drain = running.prepareQuiesceDownloads();
        running.cancelQuiescedDownloads();
        assertThat(drain.awaitDrained(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(20))).isFalse();

        release.countDown();
        assertThat(drain.awaitDrained(System.nanoTime() + TimeUnit.SECONDS.toNanos(2))).isTrue();
    }
}

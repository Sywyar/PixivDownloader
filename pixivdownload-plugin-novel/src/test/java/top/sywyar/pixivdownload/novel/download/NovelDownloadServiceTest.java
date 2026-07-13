package top.sywyar.pixivdownload.novel.download;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.author.AuthorService;
import top.sywyar.pixivdownload.collection.CollectionService;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.core.download.queue.QueueGenerationDrain;
import top.sywyar.pixivdownload.core.download.queue.QueueNotAcceptingException;
import top.sywyar.pixivdownload.core.metadata.sidecar.WorkMetaCaptureService;
import top.sywyar.pixivdownload.core.pixiv.PixivBookmarkService;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.novel.NovelSeriesService;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.novel.request.NovelDownloadRequest;
import top.sywyar.pixivdownload.novel.translation.NovelAutoTranslateService;
import top.sywyar.pixivdownload.quota.UserQuotaService;

import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("NovelDownloadService 交互式下载转发捕获")
class NovelDownloadServiceTest {

    private static final AppMessages APP_MESSAGES = TestI18nBeans.appMessages();
    private static final String RAW_META = "{\"uploadDate\":\"2026-06-06T21:27:00+00:00\"}";

    @TempDir
    Path tempDir;

    @Mock
    private DownloadConfig downloadConfig;
    @Mock
    private PixivDatabase pixivDatabase;
    @Mock
    private NovelDatabase novelDatabase;
    @Mock
    private NovelSeriesService novelSeriesService;
    @Mock
    private AuthorService authorService;
    @Mock
    private CollectionService collectionService;
    @Mock
    private PixivBookmarkService pixivBookmarkService;
    @Mock
    private UserQuotaService userQuotaService;
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

    private NovelDownloadService newService(TaskExecutor taskExecutor) {
        return new NovelDownloadService(downloadConfig, pixivDatabase, novelDatabase,
                novelSeriesService, authorService, collectionService, pixivBookmarkService,
                userQuotaService, downloadRestTemplate, taskScheduler, taskExecutor, APP_MESSAGES,
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
                .satisfies(error -> assertThat(((QueueNotAcceptingException) error).getStatus().value())
                        .isEqualTo(503));
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

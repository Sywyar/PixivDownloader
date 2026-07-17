package top.sywyar.pixivdownload.download;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResponseExtractor;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.author.AuthorService;
import top.sywyar.pixivdownload.collection.CollectionService;
import top.sywyar.pixivdownload.config.DownloadSettings;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.core.download.DownloadStatisticsService;
import top.sywyar.pixivdownload.core.download.DownloadedArtworkService;
import top.sywyar.pixivdownload.plugin.api.download.queue.QueueGenerationDrain;
import top.sywyar.pixivdownload.plugin.api.download.queue.QueueNotAcceptingException;
import top.sywyar.pixivdownload.plugin.api.download.queue.QueueTaskTracker;
import top.sywyar.pixivdownload.core.hash.ArtworkHashService;
import top.sywyar.pixivdownload.core.metadata.sidecar.WorkMetaCaptureService;
import top.sywyar.pixivdownload.core.pixiv.PixivBookmarkService;
import top.sywyar.pixivdownload.download.request.DownloadRequest;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.i18n.LocalizedException;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.quota.UserQuotaService;
import top.sywyar.pixivdownload.series.MangaSeriesService;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ArtworkDownloadExecutor 单元测试")
class ArtworkDownloadExecutorTest {
    private static final AppMessages APP_MESSAGES = TestI18nBeans.appMessages();

    @TempDir
    Path tempDir;

    @Mock
    private DownloadSettings downloadSettings;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private PixivDatabase pixivDatabase;
    @Mock
    private UserQuotaService userQuotaService;
    @Mock
    private RestTemplate downloadRestTemplate;
    @Mock
    private TaskScheduler taskScheduler;
    @Mock
    private PixivBookmarkService pixivBookmarkService;
    @Mock
    private UgoiraService ugoiraService;
    @Mock
    private AuthorService authorService;
    @Mock
    private CollectionService collectionService;
    @Mock
    private MangaSeriesService mangaSeriesService;
    @Mock
    private ArtworkHashService artworkHashService;
    @Mock
    private WorkMetaCaptureService workMetaCaptureService;
    @Mock
    private DownloadStatisticsService downloadStatisticsService;
    @Mock
    private DownloadedArtworkService downloadedArtworkService;

    private ArtworkDownloadExecutor artworkDownloadExecutor;
    private final TaskExecutor downloadTaskExecutor = Runnable::run;

    @BeforeEach
    void setUp() {
        LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
        lenient().when(taskScheduler.schedule(any(Runnable.class), any(java.time.Instant.class)))
                .thenReturn(mock(ScheduledFuture.class));
        artworkDownloadExecutor = newExecutor(downloadTaskExecutor);
    }

    private ArtworkDownloadExecutor newExecutor(TaskExecutor taskExecutor) {
        return new ArtworkDownloadExecutor(downloadSettings, eventPublisher, pixivDatabase,
                userQuotaService, downloadRestTemplate, taskScheduler, taskExecutor,
                pixivBookmarkService, ugoiraService,
                authorService, collectionService, mangaSeriesService, artworkHashService,
                workMetaCaptureService, downloadStatisticsService, downloadedArtworkService, APP_MESSAGES);
    }

    @Nested
    @DisplayName("队列生命周期")
    class QueueLifecycleTests {

        @Test
        @SuppressWarnings("unchecked")
        @DisplayName("提交后状态创建前 quiesce 应取消宿主任务且不发布残留状态")
        void shouldCancelPendingTaskBeforeStatusCreation() {
            AtomicReference<Runnable> submitted = new AtomicReference<>();
            ArtworkDownloadExecutor executor = newExecutor(submitted::set);

            executor.downloadImages(1001L, "title", List.of("https://i.pximg.net/a.jpg"),
                    "https://www.pixiv.net/artworks/1001", new DownloadRequest.Other(), null, "owner-a");
            QueueGenerationDrain drain = executor.prepareQuiesceDownloads();
            executor.cancelQuiescedDownloads();
            submitted.get().run();

            ConcurrentHashMap<String, DownloadStatus> statuses =
                    (ConcurrentHashMap<String, DownloadStatus>) ReflectionTestUtils
                            .getField(executor, "downloadStatusMap");
            assertThat(drain.isDrained()).isTrue();
            assertThat(statuses).isEmpty();
            assertThatThrownBy(() -> executor.downloadImages(1002L, "title", List.of("https://i.pximg.net/b.jpg"),
                    "https://www.pixiv.net/artworks/1002", new DownloadRequest.Other(), null, "owner-a"))
                    .isInstanceOf(QueueNotAcceptingException.class)
                    .satisfies(error -> assertThat(((QueueNotAcceptingException) error).queueType())
                            .isEqualTo("illust"));
        }

        @Test
        @DisplayName("父执行器拒绝提交时应归还 permit")
        void shouldReleasePermitWhenExecutorRejects() {
            RejectedExecutionException rejected = new RejectedExecutionException("full");
            ArtworkDownloadExecutor executor = newExecutor(task -> { throw rejected; });

            assertThatThrownBy(() -> executor.downloadImages(1003L, "title", List.of("https://i.pximg.net/c.jpg"),
                    "https://www.pixiv.net/artworks/1003", new DownloadRequest.Other(), null, null))
                    .isSameAs(rejected);

            QueueGenerationDrain drain = executor.prepareQuiesceDownloads();
            executor.cancelQuiescedDownloads();
            assertThat(drain.isDrained()).isTrue();
        }

        @Test
        @DisplayName("运行中的插画任务协作取消后必须等执行线程退出")
        void shouldWaitForRunningDownloadToExit() throws Exception {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            java.util.concurrent.atomic.AtomicBoolean blockFirstEvent =
                    new java.util.concurrent.atomic.AtomicBoolean(true);
            doAnswer(invocation -> {
                if (blockFirstEvent.compareAndSet(true, false)) {
                    entered.countDown();
                    release.await();
                }
                return null;
            }).when(eventPublisher).publishEvent(any());
            ArtworkDownloadExecutor executor = newExecutor(task -> {
                Thread worker = new Thread(task, "illust-drain-test");
                worker.setDaemon(true);
                worker.start();
            });

            executor.downloadImages(1004L, "title", List.of("https://i.pximg.net/d.jpg"),
                    "https://www.pixiv.net/artworks/1004", new DownloadRequest.Other(), null, null);
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

            QueueGenerationDrain drain = executor.prepareQuiesceDownloads();
            executor.cancelQuiescedDownloads();
            assertThat(drain.awaitDrained(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(20))).isFalse();

            release.countDown();
            assertThat(drain.awaitDrained(System.nanoTime() + TimeUnit.SECONDS.toNanos(2))).isTrue();
        }

        @Test
        @SuppressWarnings("unchecked")
        @DisplayName("tracker 致命取消与状态清理普通失败并存时保留致命主失败且完成清理")
        void shouldPreserveTrackerFatalAcrossStatusCleanupFailure() {
            QueueTaskTracker tracker = (QueueTaskTracker) ReflectionTestUtils
                    .getField(artworkDownloadExecutor, "taskTracker");
            QueueTaskTracker.Task task = tracker.prepareQueued("owner-a");
            ProbeVmError fatal = new ProbeVmError();
            task.onCancellation(() -> { throw fatal; });
            task.bind(() -> { });
            ConcurrentHashMap<String, DownloadStatus> statuses =
                    (ConcurrentHashMap<String, DownloadStatus>) ReflectionTestUtils
                            .getField(artworkDownloadExecutor, "downloadStatusMap");
            DownloadStatus status = new DownloadStatus(1005L, "title", 1);
            status.setOwnerUuid("owner-a");
            statuses.put("owner-a:1005", status);
            IllegalStateException cleanupFailure = new IllegalStateException("status-publish-failed");
            doThrow(cleanupFailure).when(eventPublisher).publishEvent(any());

            QueueGenerationDrain drain = artworkDownloadExecutor.prepareQuiesceDownloads();
            assertThatThrownBy(artworkDownloadExecutor::cancelQuiescedDownloads).isSameAs(fatal);

            assertThat(fatal.getSuppressed()).contains(cleanupFailure);
            assertThat(statuses).isEmpty();
            assertThat(drain.isDrained()).isTrue();
        }

        @Test
        @SuppressWarnings("unchecked")
        @DisplayName("普通 force clear 在任务取消失败后仍移除状态并保留致命主失败")
        void ordinaryForceClearStillCleansStatusesAfterCancellationFailure() {
            QueueTaskTracker tracker = (QueueTaskTracker) ReflectionTestUtils
                    .getField(artworkDownloadExecutor, "taskTracker");
            QueueTaskTracker.Task task = tracker.prepareQueued("owner-a");
            ProbeVmError fatal = new ProbeVmError();
            task.onCancellation(() -> { throw fatal; });
            task.bind(() -> { });
            ConcurrentHashMap<String, DownloadStatus> statuses =
                    (ConcurrentHashMap<String, DownloadStatus>) ReflectionTestUtils
                            .getField(artworkDownloadExecutor, "downloadStatusMap");
            DownloadStatus status = new DownloadStatus(1006L, "title", 1);
            status.setOwnerUuid("owner-a");
            statuses.put("owner-a:1006", status);
            IllegalStateException cleanupFailure = new IllegalStateException("ordinary-clear-publish-failed");
            doThrow(cleanupFailure).when(eventPublisher).publishEvent(any());

            assertThatThrownBy(artworkDownloadExecutor::forceClearDownloads).isSameAs(fatal);

            assertThat(fatal.getSuppressed()).contains(cleanupFailure);
            assertThat(statuses).isEmpty();
            assertThat(tracker.activeTaskCount()).isZero();
        }
    }

    @Nested
    @DisplayName("validateUserDownloadFolder")
    class ValidateUserDownloadFolderTests {

        @ParameterizedTest
        @ValueSource(strings = {"", " ", ".", "..", "../escape", "..\\escape", "C:\\temp", "/tmp/escape", "safe/../escape"})
        @DisplayName("不安全用户名目录段应被拒绝")
        void shouldRejectUnsafeUsernameFolder(String username) {
            DownloadRequest.Other other = new DownloadRequest.Other();
            other.setUserDownload(true);
            other.setUsername(username);

            assertThatThrownBy(() -> ArtworkDownloadExecutor.validateUserDownloadFolder(other))
                    .isInstanceOf(LocalizedException.class)
                    .satisfies(error -> assertThat(((LocalizedException) error).getMessageCode())
                            .isEqualTo("download.path.segment.invalid"));
        }

        @Test
        @DisplayName("普通用户名目录段应通过")
        void shouldAcceptPlainUsernameFolder() {
            DownloadRequest.Other other = new DownloadRequest.Other();
            other.setUserDownload(true);
            other.setUsername("pixiv_user_123");

            assertThatCode(() -> ArtworkDownloadExecutor.validateUserDownloadFolder(other))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("download status ownership")
    class DownloadStatusOwnershipTests {

        @Test
        @SuppressWarnings("unchecked")
        @DisplayName("same artwork id is isolated by owner")
        void shouldIsolateSameArtworkIdByOwner() {
            ConcurrentHashMap<String, DownloadStatus> statuses =
                    (ConcurrentHashMap<String, DownloadStatus>) ReflectionTestUtils
                            .getField(artworkDownloadExecutor, "downloadStatusMap");
            DownloadStatus ownerA = new DownloadStatus(123L, "owner-a-title", 1, "owner-a");
            DownloadStatus ownerB = new DownloadStatus(123L, "owner-b-title", 1, "owner-b");
            statuses.put("owner-a:123", ownerA);
            statuses.put("owner-b:123", ownerB);

            assertThat(artworkDownloadExecutor.getDownloadStatus(123L, "owner-a", false)).isSameAs(ownerA);
            assertThat(artworkDownloadExecutor.getDownloadStatus(123L, "owner-b", false)).isSameAs(ownerB);
            assertThat(artworkDownloadExecutor.getDownloadStatus("owner-a", false)).containsExactly(123L);

            artworkDownloadExecutor.cancelDownload(123L, "owner-a", false);

            assertThat(ownerA.isCancelled()).isTrue();
            assertThat(ownerB.isCancelled()).isFalse();
        }

        @Test
        @SuppressWarnings("unchecked")
        @DisplayName("force clear removes only matching owner statuses")
        void shouldForceClearOnlyMatchingOwnerStatuses() {
            ConcurrentHashMap<String, DownloadStatus> statuses =
                    (ConcurrentHashMap<String, DownloadStatus>) ReflectionTestUtils
                            .getField(artworkDownloadExecutor, "downloadStatusMap");
            DownloadStatus ownerA = new DownloadStatus(123L, "owner-a-title", 1, "owner-a");
            DownloadStatus ownerB = new DownloadStatus(123L, "owner-b-title", 1, "owner-b");
            statuses.put("owner-a:123", ownerA);
            statuses.put("owner-b:123", ownerB);

            int cleared = artworkDownloadExecutor.forceClearDownloadsForOwner("owner-a");

            assertThat(cleared).isEqualTo(1);
            assertThat(ownerA.isCancelled()).isTrue();
            assertThat(ownerA.isCompleted()).isTrue();
            assertThat(statuses).containsOnlyKeys("owner-b:123");
            assertThat(ownerB.isCancelled()).isFalse();
            verify(eventPublisher).publishEvent(argThat(event -> event instanceof DownloadProgressEvent progress
                    && progress.getArtworkId().equals(123L)
                    && progress.getDownloadStatus().isCancelled()));
        }
    }

    // ========== validatePixivUrl (SSRF 防护) ==========

    @Nested
    @DisplayName("validatePixivUrl - SSRF 防护")
    class ValidatePixivUrlTests {

        @Test
        @DisplayName("合法的 Pixiv 图片 URL 应通过校验")
        void shouldAcceptValidPixivUrl() {
            assertThatCode(() -> ArtworkDownloadExecutor.validatePixivUrl(
                    "https://i.pximg.net/img-original/img/2024/01/01/00/00/00/12345_p0.jpg"
            )).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("null 和空字符串应跳过校验")
        void shouldSkipNullOrBlank() {
            assertThatCode(() -> ArtworkDownloadExecutor.validatePixivUrl(null)).doesNotThrowAnyException();
            assertThatCode(() -> ArtworkDownloadExecutor.validatePixivUrl("")).doesNotThrowAnyException();
            assertThatCode(() -> ArtworkDownloadExecutor.validatePixivUrl("   ")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("HTTP 协议应被拒绝（仅允许 HTTPS）")
        void shouldRejectHttpUrl() {
            assertThatThrownBy(() -> ArtworkDownloadExecutor.validatePixivUrl(
                    "http://i.pximg.net/img/12345.jpg"
            )).isInstanceOf(LocalizedException.class)
              .satisfies(error -> assertThat(((LocalizedException) error).getMessageCode())
                      .isEqualTo("download.url.https-only"))
              .hasMessageContaining("只允许 HTTPS 协议");
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "https://evil.com/img.jpg",
                "https://pximg.net.evil.com/img.jpg",
                "https://notpximg.net/img.jpg",
                "https://example.com/fake.pximg.net/img.jpg"
        })
        @DisplayName("非 pximg.net 域名应被拒绝")
        void shouldRejectNonPixivDomain(String url) {
            assertThatThrownBy(() -> ArtworkDownloadExecutor.validatePixivUrl(url))
                    .isInstanceOf(LocalizedException.class)
                    .satisfies(error -> assertThat(((LocalizedException) error).getMessageCode())
                            .isEqualTo("download.url.host.not-allowed"))
                    .hasMessageContaining("域名不在白名单内");
        }

        @Test
        @DisplayName("FTP 等非 HTTPS 协议应被拒绝")
        void shouldRejectFtpProtocol() {
            assertThatThrownBy(() -> ArtworkDownloadExecutor.validatePixivUrl(
                    "ftp://i.pximg.net/img.jpg"
            )).isInstanceOf(LocalizedException.class)
              .satisfies(error -> assertThat(((LocalizedException) error).getMessageCode())
                      .isEqualTo("download.url.https-only"))
              .hasMessageContaining("只允许 HTTPS 协议");
        }

        @Test
        @DisplayName("无效 URL 格式应被拒绝")
        void shouldRejectInvalidUrl() {
            assertThatThrownBy(() -> ArtworkDownloadExecutor.validatePixivUrl(
                    "not a url at all %%"
            )).isInstanceOf(LocalizedException.class)
              .satisfies(error -> assertThat(((LocalizedException) error).getMessageCode())
                      .isEqualTo("download.url.invalid"));
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "https://i.pximg.net/img-master/img/2024/01/01/00/00/00/12345_p0_master1200.jpg",
                "https://i-f.pximg.net/img-original/img/2024/01/01/00/00/00/12345_p0.png",
                "https://public-img-zip.pximg.net/works/12345/2024/01/01/00/00/00/12345_ugoira600x600.zip"
        })
        @DisplayName("各种合法 pximg.net 子域名应通过")
        void shouldAcceptVariousPixivSubdomains(String url) {
            assertThatCode(() -> ArtworkDownloadExecutor.validatePixivUrl(url))
                    .doesNotThrowAnyException();
        }
    }

    // ========== getDownloadStatus ==========

    @Nested
    @DisplayName("getDownloadStatus")
    class GetDownloadStatusTests {

        @Test
        @DisplayName("不存在的作品ID应返回 null")
        void shouldReturnNullForUnknownArtwork() {
            assertThat(artworkDownloadExecutor.getDownloadStatus(99999L)).isNull();
        }

        @Test
        @DisplayName("getDownloadStatus() 无参版本应返回空列表（无活跃下载时）")
        void shouldReturnEmptyListWhenNoActiveDownloads() {
            List<Long> active = artworkDownloadExecutor.getDownloadStatus();
            assertThat(active).isEmpty();
        }
    }

    // ========== cancelDownload ==========

    @Nested
    @DisplayName("cancelDownload")
    class CancelDownloadTests {

        @Test
        @DisplayName("取消不存在的下载任务应无异常")
        void shouldNotThrowWhenCancellingNonExistentDownload() {
            assertThatCode(() -> artworkDownloadExecutor.cancelDownload(99999L))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("author handling")
    class AuthorHandlingTests {

        @BeforeEach
        void setupDownloadPath() {
            lenient().when(downloadSettings.getRootFolder()).thenReturn(tempDir.toString());
            lenient().when(downloadSettings.isUserFlatFolder()).thenReturn(true);
            lenient().when(ugoiraService.processUgoira(anyLong(), any(), any(), anyString(), any(), any(), any()))
                    .thenReturn(1);
            lenient().when(pixivDatabase.getUniqueTime()).thenReturn(1700000100L);
        }

        @Test
        @DisplayName("authorId 为空时应触发异步补齐")
        void shouldLookupMissingAuthorWhenAuthorIdMissing() {
            DownloadRequest.Other other = new DownloadRequest.Other();
            other.setUgoira(true);
            other.setUgoiraZipUrl("https://public-img-zip.pximg.net/test.zip");
            other.setUgoiraDelays(List.of(100));

            artworkDownloadExecutor.downloadImages(12345L, "test", List.of("https://public-img-zip.pximg.net/test.zip"),
                    "https://www.pixiv.net/", other, "cookie=value", null);

            verify(authorService).asyncLookupMissing(12345L, "cookie=value");
            verify(authorService, never()).observe(anyLong(), any());
        }

        @Test
        @DisplayName("authorId 非空时应上报作者信息")
        void shouldObserveAuthorWhenAuthorIdPresent() {
            DownloadRequest.Other other = new DownloadRequest.Other();
            other.setUgoira(true);
            other.setUgoiraZipUrl("https://public-img-zip.pximg.net/test.zip");
            other.setUgoiraDelays(List.of(100));
            other.setAuthorId(999L);
            other.setAuthorName("author");

            artworkDownloadExecutor.downloadImages(12345L, "test", List.of("https://public-img-zip.pximg.net/test.zip"),
                    "https://www.pixiv.net/", other, null, null);

            verify(authorService).observe(999L, "author");
            verify(authorService, never()).asyncLookupMissing(anyLong(), any());
        }

        @Test
        @DisplayName("作者信息记录异常不应阻断下载记录")
        void shouldIgnoreAuthorRecordFailure() {
            DownloadRequest.Other other = new DownloadRequest.Other();
            other.setUgoira(true);
            other.setUgoiraZipUrl("https://public-img-zip.pximg.net/test.zip");
            other.setUgoiraDelays(List.of(100));
            other.setAuthorId(999L);
            doThrow(new RuntimeException("boom")).when(authorService).observe(999L, null);

            artworkDownloadExecutor.downloadImages(12345L, "test", List.of("https://public-img-zip.pximg.net/test.zip"),
                    "https://www.pixiv.net/", other, null, null);

            verify(pixivDatabase).insertArtwork(12345L, "test", tempDir.resolve("12345").toAbsolutePath().toString(),
                    1, "webp", 1700000100L, 0, false, 999L, null, 1L, null, null, null);
        }
    }

    @Nested
    @DisplayName("xRestrict 子目录分支")
    class XRestrictDirectoryTests {

        @BeforeEach
        void setupDownloadPath() {
            lenient().when(downloadSettings.getRootFolder()).thenReturn(tempDir.toString());
            // 走用户独立目录分支：isUserDownload=true 且 isUserFlatFolder=false
            lenient().when(downloadSettings.isUserFlatFolder()).thenReturn(false);
            lenient().when(ugoiraService.processUgoira(anyLong(), any(), any(), anyString(), any(), any(), any()))
                    .thenReturn(1);
            lenient().when(pixivDatabase.getUniqueTime()).thenReturn(1700000100L);
        }

        private DownloadRequest.Other userOther(int xRestrict) {
            DownloadRequest.Other other = new DownloadRequest.Other();
            other.setUgoira(true);
            other.setUgoiraZipUrl("https://public-img-zip.pximg.net/test.zip");
            other.setUgoiraDelays(List.of(100));
            other.setUserDownload(true);
            other.setUsername("alice");
            other.setXRestrict(xRestrict);
            return other;
        }

        @Test
        @DisplayName("xRestrict==1 时下载目录应进入 R18 子目录")
        void shouldRouteToR18WhenXRestrictIsOne() {
            DownloadRequest.Other other = userOther(1);

            artworkDownloadExecutor.downloadImages(12345L, "title", List.of("https://public-img-zip.pximg.net/test.zip"),
                    "https://www.pixiv.net/", other, null, null);

            Path expected = tempDir.resolve("alice").resolve("R18").resolve("12345");
            verify(pixivDatabase).insertArtwork(eq(12345L), eq("title"),
                    eq(expected.toAbsolutePath().toString()),
                    eq(1), eq("webp"), eq(1700000100L), eq(1), eq(false),
                    isNull(), isNull(), eq(1L), isNull(), isNull(), isNull());
        }

        @Test
        @DisplayName("xRestrict==2 时下载目录应进入 R18G 子目录")
        void shouldRouteToR18gWhenXRestrictIsTwo() {
            DownloadRequest.Other other = userOther(2);

            artworkDownloadExecutor.downloadImages(22345L, "title", List.of("https://public-img-zip.pximg.net/test.zip"),
                    "https://www.pixiv.net/", other, null, null);

            Path expected = tempDir.resolve("alice").resolve("R18G").resolve("22345");
            verify(pixivDatabase).insertArtwork(eq(22345L), eq("title"),
                    eq(expected.toAbsolutePath().toString()),
                    eq(1), eq("webp"), eq(1700000100L), eq(2), eq(false),
                    isNull(), isNull(), eq(1L), isNull(), isNull(), isNull());
        }

        @Test
        @DisplayName("xRestrict==0 时不应再插入 R18/R18G 子目录")
        void shouldNotAddR18FolderWhenSafeWork() {
            DownloadRequest.Other other = userOther(0);

            artworkDownloadExecutor.downloadImages(32345L, "title", List.of("https://public-img-zip.pximg.net/test.zip"),
                    "https://www.pixiv.net/", other, null, null);

            Path expected = tempDir.resolve("alice").resolve("32345");
            verify(pixivDatabase).insertArtwork(eq(32345L), eq("title"),
                    eq(expected.toAbsolutePath().toString()),
                    eq(1), eq("webp"), eq(1700000100L), eq(0), eq(false),
                    isNull(), isNull(), eq(1L), isNull(), isNull(), isNull());
        }

        @Test
        @DisplayName("isUserFlatFolder=true 时即便 xRestrict==2 也不应下沉到 R18G")
        void shouldRespectFlatFolderEvenWhenXRestrictIsR18g() {
            when(downloadSettings.isUserFlatFolder()).thenReturn(true);
            DownloadRequest.Other other = userOther(2);

            artworkDownloadExecutor.downloadImages(42345L, "title", List.of("https://public-img-zip.pximg.net/test.zip"),
                    "https://www.pixiv.net/", other, null, null);

            Path expected = tempDir.resolve("42345");
            verify(pixivDatabase).insertArtwork(eq(42345L), eq("title"),
                    eq(expected.toAbsolutePath().toString()),
                    eq(1), eq("webp"), eq(1700000100L), eq(2), eq(false),
                    isNull(), isNull(), eq(1L), isNull(), isNull(), isNull());
        }
    }

    @Nested
    @DisplayName("collection download root")
    class CollectionDownloadRootTests {

        @BeforeEach
        void setupDownloadPath() {
            lenient().when(downloadSettings.getRootFolder()).thenReturn(tempDir.toString());
            lenient().when(downloadSettings.isUserFlatFolder()).thenReturn(true);
            lenient().when(ugoiraService.processUgoira(anyLong(), any(), any(), anyString(), any(), any(), any()))
                    .thenReturn(1);
            lenient().when(pixivDatabase.getUniqueTime()).thenReturn(1700000100L);
        }

        @Test
        @DisplayName("批量下载指定收藏夹时应使用收藏夹下载根目录")
        void shouldUseCollectionDownloadRootWhenCollectionIdIsProvided() {
            DownloadRequest.Other other = new DownloadRequest.Other();
            other.setUgoira(true);
            other.setUgoiraZipUrl("https://public-img-zip.pximg.net/test.zip");
            other.setUgoiraDelays(List.of(100));
            other.setCollectionId(7L);
            Path collectionRoot = tempDir.resolve("收藏😀");
            when(collectionService.resolveDownloadRoot(7L, tempDir)).thenReturn(collectionRoot);

            artworkDownloadExecutor.downloadImages(12345L, "test", List.of("https://public-img-zip.pximg.net/test.zip"),
                    "https://www.pixiv.net/", other, null, null);

            Path expectedPath = collectionRoot.resolve("12345");
            verify(ugoiraService).processUgoira(
                    eq(12345L),
                    same(other),
                    eq(expectedPath),
                    eq("https://www.pixiv.net/"),
                    isNull(),
                    any(),
                    any()
            );
            verify(pixivDatabase).insertArtwork(12345L, "test", expectedPath.toAbsolutePath().toString(),
                    1, "webp", 1700000100L, 0, false, null, null, 1L, null, null, null);
            verify(collectionService).addArtwork(7L, 12345L);
        }
    }

    @Nested
    @DisplayName("前端转发原始 meta 旁路捕获")
    class ForwardedMetaCaptureTests {

        @BeforeEach
        void setupDownloadPath() {
            lenient().when(downloadSettings.getRootFolder()).thenReturn(tempDir.toString());
            lenient().when(downloadSettings.isUserFlatFolder()).thenReturn(true);
            lenient().when(ugoiraService.processUgoira(anyLong(), any(), any(), anyString(), any(), any(), any()))
                    .thenReturn(1);
            lenient().when(pixivDatabase.getUniqueTime()).thenReturn(1700000100L);
        }

        private DownloadRequest.Other ugoiraOther() {
            DownloadRequest.Other other = new DownloadRequest.Other();
            other.setUgoira(true);
            other.setUgoiraZipUrl("https://public-img-zip.pximg.net/test.zip");
            other.setUgoiraDelays(List.of(100));
            return other;
        }

        @Test
        @DisplayName("下载成功且带 rawMetaJson 时应旁路转发捕获")
        void shouldCaptureForwardedMetaWhenPresent() {
            DownloadRequest.Other other = ugoiraOther();
            other.setRawMetaJson("{\"uploadDate\":\"2026-06-06T21:27:00+00:00\"}");

            artworkDownloadExecutor.downloadImages(12345L, "title", List.of("https://public-img-zip.pximg.net/test.zip"),
                    "https://www.pixiv.net/", other, null, null);

            verify(workMetaCaptureService).captureForwardedArtwork(12345L,
                    "{\"uploadDate\":\"2026-06-06T21:27:00+00:00\"}");
        }

        @Test
        @DisplayName("未带 rawMetaJson 时不应触发转发捕获")
        void shouldNotCaptureWhenRawMetaJsonAbsent() {
            artworkDownloadExecutor.downloadImages(22345L, "title", List.of("https://public-img-zip.pximg.net/test.zip"),
                    "https://www.pixiv.net/", ugoiraOther(), null, null);

            verify(workMetaCaptureService, never()).captureForwardedArtwork(anyLong(), any());
        }
    }

    // ========== DownloadStatus ==========

    @Nested
    @DisplayName("DownloadStatus")
    class DownloadStatusTests {

        @Test
        @DisplayName("初始状态应正确")
        void shouldHaveCorrectInitialState() {
            DownloadStatus status = new DownloadStatus(12345L, "测试", 5);

            assertThat(status.getArtworkId()).isEqualTo(12345L);
            assertThat(status.getTitle()).isEqualTo("测试");
            assertThat(status.getTotalImages()).isEqualTo(5);
            assertThat(status.getDownloadedCount()).isZero();
            assertThat(status.getCurrentImageIndex()).isEqualTo(-1);
            assertThat(status.isCompleted()).isFalse();
            assertThat(status.isFailed()).isFalse();
            assertThat(status.isCancelled()).isFalse();
        }

        @Test
        @DisplayName("进度百分比计算")
        void shouldCalculateProgressPercentage() {
            DownloadStatus status = new DownloadStatus(1L, "test", 10);
            assertThat(status.getProgressPercentage()).isEqualTo(0.0);

            status.setDownloadedCount(5);
            assertThat(status.getProgressPercentage()).isEqualTo(50.0);

            status.setDownloadedCount(10);
            assertThat(status.getProgressPercentage()).isEqualTo(100.0);
        }

        @Test
        @DisplayName("totalImages 为 0 时进度百分比应为 0")
        void shouldReturnZeroProgressWhenNoImages() {
            DownloadStatus status = new DownloadStatus(1L, "test", 0);
            assertThat(status.getProgressPercentage()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("状态描述应随状态变化")
        void shouldReturnCorrectStatusDescription() {
            DownloadStatus status = new DownloadStatus(1L, "test", 5);

            assertThat(status.getStatusMessageCode()).isEqualTo("download.status.pending");
            assertThat(APP_MESSAGES.get(status.getStatusMessageCode(), status.getStatusMessageArgs())).isEqualTo("等待开始");

            status.setCurrentImageIndex(2);
            assertThat(status.getStatusMessageCode()).isEqualTo("download.status.in-progress");
            assertThat(APP_MESSAGES.get(status.getStatusMessageCode(), status.getStatusMessageArgs())).isEqualTo("下载中 (3/5)");

            status.setCompleted(true);
            status.setSuccessCount(5);
            assertThat(status.getStatusMessageCode()).isEqualTo("download.status.completed");
            assertThat(APP_MESSAGES.get(status.getStatusMessageCode(), status.getStatusMessageArgs())).isEqualTo("已完成 (5/5)");

            status.setCompleted(false);
            status.setCancelled(true);
            assertThat(status.getStatusMessageCode()).isEqualTo("download.status.cancelled");
            assertThat(APP_MESSAGES.get(status.getStatusMessageCode(), status.getStatusMessageArgs())).isEqualTo("已取消");

            status.setCancelled(false);
            status.setFailed(true);
            status.setErrorMessage("网络超时");
            assertThat(status.getStatusMessageCode()).isEqualTo("download.status.failed");
            assertThat(APP_MESSAGES.get(status.getStatusMessageCode(), status.getStatusMessageArgs())).isEqualTo("失败: 网络超时");
        }
    }

    @Nested
    @DisplayName("普通图片下载 .part 临时文件")
    class PartTempFileTests {

        private static final String IMAGE_URL =
                "https://i.pximg.net/img-original/img/2024/01/01/00/00/00/12345_p0.jpg";

        @BeforeEach
        void setupDownloadPath() {
            lenient().when(downloadSettings.getRootFolder()).thenReturn(tempDir.toString());
            lenient().when(downloadSettings.isUserFlatFolder()).thenReturn(false);
            lenient().when(pixivDatabase.getUniqueTime()).thenReturn(1700000100L);
        }

        @Test
        @DisplayName("下载成功后最终文件就位且不残留 .part")
        void shouldRenamePartToFinalAndLeaveNoTempFile() throws Exception {
            byte[] payload = {1, 2, 3, 4, 5};
            when(downloadRestTemplate.execute(eq(IMAGE_URL), eq(HttpMethod.GET), any(), any()))
                    .thenAnswer(invocation -> {
                        ResponseExtractor<Boolean> extractor = invocation.getArgument(3);
                        ClientHttpResponse response = mock(ClientHttpResponse.class);
                        lenient().when(response.getStatusCode()).thenReturn(HttpStatus.OK);
                        HttpHeaders headers = new HttpHeaders();
                        headers.setContentLength(payload.length);
                        lenient().when(response.getHeaders()).thenReturn(headers);
                        lenient().when(response.getBody()).thenReturn(new ByteArrayInputStream(payload));
                        return extractor.extractData(response);
                    });

            artworkDownloadExecutor.downloadImages(12345L, "title", List.of(IMAGE_URL),
                    "https://www.pixiv.net/", new DownloadRequest.Other(), null, null);

            Path artworkDir = tempDir.resolve("12345");
            try (var stream = Files.list(artworkDir)) {
                List<Path> files = stream.toList();
                assertThat(files).hasSize(1);
                Path finalFile = files.get(0);
                assertThat(finalFile.getFileName().toString()).doesNotEndWith(".part");
                assertThat(Files.readAllBytes(finalFile)).containsExactly(payload);
            }
        }
    }

    @Nested
    @DisplayName("部分图片下载失败")
    class PartialFailureTests {

        private static final String OK_URL =
                "https://i.pximg.net/img-original/img/2024/01/01/00/00/00/67890_p0.jpg";
        private static final String FAIL_URL =
                "https://i.pximg.net/img-original/img/2024/01/01/00/00/00/67890_p1.jpg";

        @Test
        @DisplayName("有图片下载失败时不写下载历史且状态标记为失败")
        void shouldNotRecordHistoryWhenAnyImageFails() {
            lenient().when(downloadSettings.getRootFolder()).thenReturn(tempDir.toString());
            lenient().when(downloadSettings.isUserFlatFolder()).thenReturn(false);
            lenient().when(pixivDatabase.getUniqueTime()).thenReturn(1700000200L);
            byte[] payload = {1, 2, 3};
            when(downloadRestTemplate.execute(eq(OK_URL), eq(HttpMethod.GET), any(), any()))
                    .thenAnswer(invocation -> {
                        ResponseExtractor<Boolean> extractor = invocation.getArgument(3);
                        ClientHttpResponse response = mock(ClientHttpResponse.class);
                        lenient().when(response.getStatusCode()).thenReturn(HttpStatus.OK);
                        HttpHeaders headers = new HttpHeaders();
                        headers.setContentLength(payload.length);
                        lenient().when(response.getHeaders()).thenReturn(headers);
                        lenient().when(response.getBody()).thenReturn(new ByteArrayInputStream(payload));
                        return extractor.extractData(response);
                    });
            when(downloadRestTemplate.execute(eq(FAIL_URL), eq(HttpMethod.GET), any(), any()))
                    .thenAnswer(invocation -> {
                        ResponseExtractor<Boolean> extractor = invocation.getArgument(3);
                        ClientHttpResponse response = mock(ClientHttpResponse.class);
                        lenient().when(response.getStatusCode()).thenReturn(HttpStatus.NOT_FOUND);
                        return extractor.extractData(response);
                    });

            boolean succeeded = artworkDownloadExecutor.downloadImagesBlocking(67890L, "title",
                    List.of(OK_URL, FAIL_URL), "https://www.pixiv.net/", new DownloadRequest.Other(), null, null);

            assertThat(succeeded).isFalse();
            DownloadStatus status = artworkDownloadExecutor.getDownloadStatus(67890L);
            assertThat(status.isFailed()).isTrue();
            assertThat(status.isCompleted()).isTrue();
            assertThat(status.getSuccessCount()).isEqualTo(1);
            assertThat(status.getFailedCount()).isEqualTo(1);
            assertThat(status.getErrorMessage()).contains("1/2");
            verify(pixivDatabase, never()).insertArtwork(anyLong(), any(), any(), anyInt(), any(),
                    anyLong(), any(), any(), any(), any(), anyLong(), any(), any(), any());
            verify(downloadStatisticsService, never()).recordStatistics(anyInt());
        }
    }
    private static final class ProbeVmError extends VirtualMachineError {
    }
}

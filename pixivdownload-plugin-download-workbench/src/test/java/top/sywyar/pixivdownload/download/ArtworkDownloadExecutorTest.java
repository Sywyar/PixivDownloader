package top.sywyar.pixivdownload.download;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;
import top.sywyar.pixivdownload.config.DownloadSettings;
import top.sywyar.pixivdownload.core.collection.CollectionDownloadRootResolver;
import top.sywyar.pixivdownload.core.collection.WorkCollectionMembership;
import top.sywyar.pixivdownload.plugin.api.download.queue.QueueGenerationDrain;
import top.sywyar.pixivdownload.plugin.api.download.queue.QueueNotAcceptingException;
import top.sywyar.pixivdownload.plugin.api.download.queue.QueueTaskTracker;
import top.sywyar.pixivdownload.core.hash.ArtworkHashIndexMaintenance;
import top.sywyar.pixivdownload.core.pixiv.PixivBookmarkActions;
import top.sywyar.pixivdownload.core.pixiv.PixivImageDownloader;
import top.sywyar.pixivdownload.core.pixiv.PixivImageTransferObserver;
import top.sywyar.pixivdownload.core.quota.VisitorDownloadQuotaService;
import top.sywyar.pixivdownload.core.artwork.download.ArtworkAuthorLookup;
import top.sywyar.pixivdownload.core.artwork.download.ArtworkDownloadCompletion;
import top.sywyar.pixivdownload.core.artwork.download.ArtworkDownloadHistory;
import top.sywyar.pixivdownload.core.artwork.download.ArtworkDownloadLookup;
import top.sywyar.pixivdownload.core.artwork.download.ArtworkDownloadStatistics;
import top.sywyar.pixivdownload.core.artwork.download.ArtworkSeriesObservation;
import top.sywyar.pixivdownload.core.artwork.download.ArtworkSeriesObserver;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.core.work.model.WorkTag;
import top.sywyar.pixivdownload.core.work.service.AuthorObservationService;
import top.sywyar.pixivdownload.core.work.service.DownloadPathGuard;
import top.sywyar.pixivdownload.core.work.service.DownloadPathRejectedException;
import top.sywyar.pixivdownload.core.work.service.WorkMetadataCapture;
import top.sywyar.pixivdownload.download.request.DownloadRequest;
import top.sywyar.pixivdownload.download.web.LocalizedException;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.download.testsupport.WorkbenchTestMessages;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ArtworkDownloadExecutor 单元测试")
class ArtworkDownloadExecutorTest {
    private static final MessageResolver MESSAGES = WorkbenchTestMessages.messages();

    @TempDir
    Path tempDir;

    @Mock
    private DownloadSettings downloadSettings;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private ArtworkDownloadHistory artworkDownloadHistory;
    @Mock
    private ArtworkDownloadLookup artworkDownloadLookup;
    @Mock
    private ArtworkDownloadStatistics artworkDownloadStatistics;
    @Mock
    private VisitorDownloadQuotaService visitorDownloadQuotaService;
    @Mock
    private PixivImageDownloader pixivImageDownloader;
    @Mock
    private TaskScheduler taskScheduler;
    @Mock
    private PixivBookmarkActions pixivBookmarkActions;
    @Mock
    private UgoiraService ugoiraService;
    @Mock
    private AuthorObservationService authorObservationService;
    @Mock
    private ArtworkAuthorLookup artworkAuthorLookup;
    @Mock
    private DownloadPathGuard downloadPathGuard;
    @Mock
    private CollectionDownloadRootResolver collectionDownloadRootResolver;
    @Mock
    private WorkCollectionMembership workCollectionMembership;
    @Mock
    private ArtworkSeriesObserver artworkSeriesObserver;
    @Mock
    private ArtworkHashIndexMaintenance artworkHashIndexMaintenance;
    @Mock
    private WorkMetadataCapture workMetadataCapture;
    private ArtworkDownloadExecutor artworkDownloadExecutor;
    private final TaskExecutor downloadTaskExecutor = Runnable::run;

    @BeforeEach
    void setUp() {
        LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
        lenient().when(taskScheduler.schedule(any(Runnable.class), any(java.time.Instant.class)))
                .thenReturn(mock(ScheduledFuture.class));
        lenient().when(downloadPathGuard.requireSafeDirectoryName(anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        artworkDownloadExecutor = newExecutor(downloadTaskExecutor);
    }

    private ArtworkDownloadExecutor newExecutor(TaskExecutor taskExecutor) {
        return new ArtworkDownloadExecutor(downloadSettings, eventPublisher,
                artworkDownloadHistory, artworkDownloadLookup, artworkDownloadStatistics,
                visitorDownloadQuotaService, pixivImageDownloader, taskScheduler, taskExecutor,
                pixivBookmarkActions, ugoiraService,
                authorObservationService, artworkAuthorLookup, downloadPathGuard,
                collectionDownloadRootResolver, workCollectionMembership,
                artworkSeriesObserver, artworkHashIndexMaintenance,
                workMetadataCapture, MESSAGES);
    }

    private ArtworkDownloadCompletion capturedDownloadCompletion() {
        org.mockito.ArgumentCaptor<ArtworkDownloadCompletion> completion =
                org.mockito.ArgumentCaptor.forClass(ArtworkDownloadCompletion.class);
        verify(artworkDownloadHistory).record(completion.capture());
        return completion.getValue();
    }

    private void stubSuccessfulImageDownload(String sourceUrl, byte[] payload) throws Exception {
        when(pixivImageDownloader.download(
                eq(URI.create(sourceUrl)), any(URI.class), any(Path.class), nullable(String.class), any()))
                .thenAnswer(invocation -> {
                    Path target = invocation.getArgument(2);
                    PixivImageTransferObserver observer = invocation.getArgument(4);
                    observer.checkCancelled();
                    observer.onContentLength(payload.length);
                    observer.onBytesTransferred(0L);
                    Files.write(target, payload);
                    observer.onBytesTransferred(payload.length);
                    return true;
                });
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
            doThrow(new DownloadPathRejectedException())
                    .when(downloadPathGuard).requireSafeDirectoryName(username);

            assertThatThrownBy(() -> artworkDownloadExecutor.validateUserDownloadFolder(other))
                    .isInstanceOf(LocalizedException.class)
                    .satisfies(error -> assertThat(
                            ((LocalizedException) error).messageCode())
                            .isEqualTo("download.path.segment.invalid"));
        }

        @Test
        @DisplayName("普通用户名目录段应通过")
        void shouldAcceptPlainUsernameFolder() {
            DownloadRequest.Other other = new DownloadRequest.Other();
            other.setUserDownload(true);
            other.setUsername("pixiv_user_123");

            assertThatCode(() -> artworkDownloadExecutor.validateUserDownloadFolder(other))
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
              .satisfies(error -> assertThat(((LocalizedException) error).messageCode())
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
                    .satisfies(error -> assertThat(((LocalizedException) error).messageCode())
                            .isEqualTo("download.url.host.not-allowed"))
                    .hasMessageContaining("域名不在白名单内");
        }

        @Test
        @DisplayName("FTP 等非 HTTPS 协议应被拒绝")
        void shouldRejectFtpProtocol() {
            assertThatThrownBy(() -> ArtworkDownloadExecutor.validatePixivUrl(
                    "ftp://i.pximg.net/img.jpg"
            )).isInstanceOf(LocalizedException.class)
              .satisfies(error -> assertThat(((LocalizedException) error).messageCode())
                      .isEqualTo("download.url.https-only"))
              .hasMessageContaining("只允许 HTTPS 协议");
        }

        @Test
        @DisplayName("无效 URL 格式应被拒绝")
        void shouldRejectInvalidUrl() {
            assertThatThrownBy(() -> ArtworkDownloadExecutor.validatePixivUrl(
                    "not a url at all %%"
            )).isInstanceOf(LocalizedException.class)
              .satisfies(error -> assertThat(((LocalizedException) error).messageCode())
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
            lenient().when(artworkDownloadHistory.allocateRecordTime(0L)).thenReturn(1700000100L);
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

            verify(artworkAuthorLookup).resolveMissing(12345L, "cookie=value");
            verify(authorObservationService, never()).observe(anyLong(), any());
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

            verify(authorObservationService).observe(999L, "author");
            verifyNoInteractions(artworkAuthorLookup);
        }

        @Test
        @DisplayName("作者信息记录异常不应阻断下载记录")
        void shouldIgnoreAuthorRecordFailure() {
            DownloadRequest.Other other = new DownloadRequest.Other();
            other.setUgoira(true);
            other.setUgoiraZipUrl("https://public-img-zip.pximg.net/test.zip");
            other.setUgoiraDelays(List.of(100));
            other.setAuthorId(999L);
            doThrow(new RuntimeException("boom")).when(authorObservationService).observe(999L, null);

            artworkDownloadExecutor.downloadImages(12345L, "test", List.of("https://public-img-zip.pximg.net/test.zip"),
                    "https://www.pixiv.net/", other, null, null);

            ArtworkDownloadCompletion record = capturedDownloadCompletion();
            assertThat(record.artworkId()).isEqualTo(12345L);
            assertThat(record.folder()).isEqualTo(tempDir.resolve("12345").toAbsolutePath());
            assertThat(record.authorId()).isEqualTo(999L);
        }
    }

    @Nested
    @DisplayName("series handling")
    class SeriesHandlingTests {

        @BeforeEach
        void setupDownloadPath() {
            lenient().when(downloadSettings.getRootFolder()).thenReturn(tempDir.toString());
            lenient().when(downloadSettings.isUserFlatFolder()).thenReturn(true);
            lenient().when(ugoiraService.processUgoira(anyLong(), any(), any(), anyString(), any(), any(), any()))
                    .thenReturn(1);
            lenient().when(artworkDownloadHistory.allocateRecordTime(0L)).thenReturn(1700000100L);
        }

        @Test
        @DisplayName("应把已知系列事实与短生命周期凭证分开提交")
        void submitsKnownSeriesObservationWithoutEmbeddingCredential() {
            DownloadRequest.Other other = new DownloadRequest.Other();
            other.setUgoira(true);
            other.setUgoiraZipUrl("https://public-img-zip.pximg.net/test.zip");
            other.setUgoiraDelays(List.of(100));
            other.setSeriesId(7L);
            other.setSeriesTitle("series");
            other.setSeriesDescription("description");
            other.setSeriesCoverUrl("https://i.pximg.net/cover.jpg");
            other.setAuthorId(8L);

            artworkDownloadExecutor.downloadImages(
                    12345L,
                    "title",
                    List.of("https://public-img-zip.pximg.net/test.zip"),
                    "https://www.pixiv.net/",
                    other,
                    "credential",
                    null
            );

            org.mockito.ArgumentCaptor<ArtworkSeriesObservation> observation =
                    org.mockito.ArgumentCaptor.forClass(ArtworkSeriesObservation.class);
            verify(artworkSeriesObserver).observe(observation.capture(), eq("credential"));
            assertThat(observation.getValue()).isEqualTo(new ArtworkSeriesObservation(
                    12345L,
                    true,
                    7L,
                    "series",
                    8L,
                    "description",
                    "https://i.pximg.net/cover.jpg"
            ));
            assertThat(observation.getValue().toString()).doesNotContain("credential");
        }

        @Test
        @DisplayName("明确非漫画且无系列时应禁止联网补齐")
        void disablesMissingSeriesLookupForKnownNonManga() {
            DownloadRequest.Other other = new DownloadRequest.Other();
            other.setUgoira(true);
            other.setUgoiraZipUrl("https://public-img-zip.pximg.net/test.zip");
            other.setUgoiraDelays(List.of(100));
            other.setIllustType(0);

            artworkDownloadExecutor.downloadImages(
                    12346L,
                    "title",
                    List.of("https://public-img-zip.pximg.net/test.zip"),
                    "https://www.pixiv.net/",
                    other,
                    null,
                    null
            );

            verify(artworkSeriesObserver).observe(
                    argThat(observation -> observation.artworkId() == 12346L
                            && !observation.lookupWhenMissing()
                            && observation.seriesId() == null),
                    isNull()
            );
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
            lenient().when(artworkDownloadHistory.allocateRecordTime(0L)).thenReturn(1700000100L);
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
            ArtworkDownloadCompletion record = capturedDownloadCompletion();
            assertThat(record.artworkId()).isEqualTo(12345L);
            assertThat(record.folder()).isEqualTo(expected.toAbsolutePath());
            assertThat(record.restriction()).isEqualTo(1);
        }

        @Test
        @DisplayName("xRestrict==2 时下载目录应进入 R18G 子目录")
        void shouldRouteToR18gWhenXRestrictIsTwo() {
            DownloadRequest.Other other = userOther(2);

            artworkDownloadExecutor.downloadImages(22345L, "title", List.of("https://public-img-zip.pximg.net/test.zip"),
                    "https://www.pixiv.net/", other, null, null);

            Path expected = tempDir.resolve("alice").resolve("R18G").resolve("22345");
            ArtworkDownloadCompletion record = capturedDownloadCompletion();
            assertThat(record.artworkId()).isEqualTo(22345L);
            assertThat(record.folder()).isEqualTo(expected.toAbsolutePath());
            assertThat(record.restriction()).isEqualTo(2);
        }

        @Test
        @DisplayName("xRestrict==0 时不应再插入 R18/R18G 子目录")
        void shouldNotAddR18FolderWhenSafeWork() {
            DownloadRequest.Other other = userOther(0);

            artworkDownloadExecutor.downloadImages(32345L, "title", List.of("https://public-img-zip.pximg.net/test.zip"),
                    "https://www.pixiv.net/", other, null, null);

            Path expected = tempDir.resolve("alice").resolve("32345");
            ArtworkDownloadCompletion record = capturedDownloadCompletion();
            assertThat(record.artworkId()).isEqualTo(32345L);
            assertThat(record.folder()).isEqualTo(expected.toAbsolutePath());
            assertThat(record.restriction()).isZero();
        }

        @Test
        @DisplayName("isUserFlatFolder=true 时即便 xRestrict==2 也不应下沉到 R18G")
        void shouldRespectFlatFolderEvenWhenXRestrictIsR18g() {
            when(downloadSettings.isUserFlatFolder()).thenReturn(true);
            DownloadRequest.Other other = userOther(2);

            artworkDownloadExecutor.downloadImages(42345L, "title", List.of("https://public-img-zip.pximg.net/test.zip"),
                    "https://www.pixiv.net/", other, null, null);

            Path expected = tempDir.resolve("42345");
            ArtworkDownloadCompletion record = capturedDownloadCompletion();
            assertThat(record.artworkId()).isEqualTo(42345L);
            assertThat(record.folder()).isEqualTo(expected.toAbsolutePath());
            assertThat(record.restriction()).isEqualTo(2);
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
            lenient().when(artworkDownloadHistory.allocateRecordTime(0L)).thenReturn(1700000100L);
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
            when(collectionDownloadRootResolver.resolveDownloadRoot(7L, tempDir)).thenReturn(collectionRoot);

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
            ArtworkDownloadCompletion record = capturedDownloadCompletion();
            assertThat(record.artworkId()).isEqualTo(12345L);
            assertThat(record.folder()).isEqualTo(expectedPath.toAbsolutePath());
            verify(workCollectionMembership).addWork(WorkType.ARTWORK, 7L, 12345L);
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
            lenient().when(artworkDownloadHistory.allocateRecordTime(0L)).thenReturn(1700000100L);
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

            verify(workMetadataCapture).captureForwarded(WorkType.ARTWORK, 12345L,
                    "{\"uploadDate\":\"2026-06-06T21:27:00+00:00\"}");
        }

        @Test
        @DisplayName("未带 rawMetaJson 时不应触发转发捕获")
        void shouldNotCaptureWhenRawMetaJsonAbsent() {
            artworkDownloadExecutor.downloadImages(22345L, "title", List.of("https://public-img-zip.pximg.net/test.zip"),
                    "https://www.pixiv.net/", ugoiraOther(), null, null);

            verify(workMetadataCapture, never()).captureForwarded(any(), anyLong(), any());
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
            assertThat(MESSAGES.get(status.getStatusMessageCode(), status.getStatusMessageArgs())).isEqualTo("等待开始");

            status.setCurrentImageIndex(2);
            assertThat(status.getStatusMessageCode()).isEqualTo("download.status.in-progress");
            assertThat(MESSAGES.get(status.getStatusMessageCode(), status.getStatusMessageArgs())).isEqualTo("下载中 (3/5)");

            status.setCompleted(true);
            status.setSuccessCount(5);
            assertThat(status.getStatusMessageCode()).isEqualTo("download.status.completed");
            assertThat(MESSAGES.get(status.getStatusMessageCode(), status.getStatusMessageArgs())).isEqualTo("已完成 (5/5)");

            status.setCompleted(false);
            status.setCancelled(true);
            assertThat(status.getStatusMessageCode()).isEqualTo("download.status.cancelled");
            assertThat(MESSAGES.get(status.getStatusMessageCode(), status.getStatusMessageArgs())).isEqualTo("已取消");

            status.setCancelled(false);
            status.setFailed(true);
            status.setErrorMessage("网络超时");
            assertThat(status.getStatusMessageCode()).isEqualTo("download.status.failed");
            assertThat(MESSAGES.get(status.getStatusMessageCode(), status.getStatusMessageArgs())).isEqualTo("失败: 网络超时");
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
            lenient().when(artworkDownloadHistory.allocateRecordTime(0L)).thenReturn(1700000100L);
        }

        @Test
        @DisplayName("下载成功后最终文件就位且不残留 .part")
        void shouldRenamePartToFinalAndLeaveNoTempFile() throws Exception {
            byte[] payload = {1, 2, 3, 4, 5};
            stubSuccessfulImageDownload(IMAGE_URL, payload);

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

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = "   ")
        @DisplayName("空 Referer 应回退到 Pixiv 首页")
        void shouldFallbackToPixivHomeForBlankReferer(String referer) throws Exception {
            byte[] payload = {6, 7, 8};
            stubSuccessfulImageDownload(IMAGE_URL, payload);

            boolean succeeded = artworkDownloadExecutor.downloadImagesBlocking(
                    22345L, "title", List.of(IMAGE_URL), referer,
                    new DownloadRequest.Other(), null, null);

            assertThat(succeeded).isTrue();
            verify(pixivImageDownloader).download(
                    eq(URI.create(IMAGE_URL)),
                    eq(URI.create("https://www.pixiv.net/")),
                    any(Path.class), isNull(), any());
        }

        @Test
        @DisplayName("图片端口返回失败后应退避并重试")
        void shouldRetryWhenImagePortReturnsFalse() throws Exception {
            byte[] payload = {9, 10};
            AtomicInteger attempts = new AtomicInteger();
            when(pixivImageDownloader.download(
                    eq(URI.create(IMAGE_URL)), any(URI.class), any(Path.class),
                    nullable(String.class), any()))
                    .thenAnswer(invocation -> {
                        if (attempts.incrementAndGet() == 1) {
                            return false;
                        }
                        Path target = invocation.getArgument(2);
                        Files.write(target, payload);
                        return true;
                    });

            boolean succeeded = artworkDownloadExecutor.downloadImagesBlocking(
                    32345L, "title", List.of(IMAGE_URL), "https://www.pixiv.net/",
                    new DownloadRequest.Other(), null, null);

            assertThat(succeeded).isTrue();
            assertThat(attempts).hasValue(2);
        }

        @Test
        @DisplayName("图片端口抛出瞬时异常后应重试并成功")
        void shouldRetryAfterTransientImagePortException() throws Exception {
            byte[] payload = {11, 12};
            AtomicInteger attempts = new AtomicInteger();
            when(pixivImageDownloader.download(
                    eq(URI.create(IMAGE_URL)), any(URI.class), any(Path.class),
                    nullable(String.class), any()))
                    .thenAnswer(invocation -> {
                        if (attempts.incrementAndGet() == 1) {
                            throw new IOException("transient");
                        }
                        Path target = invocation.getArgument(2);
                        Files.write(target, payload);
                        return true;
                    });

            boolean succeeded = artworkDownloadExecutor.downloadImagesBlocking(
                    42345L, "title", List.of(IMAGE_URL), "https://www.pixiv.net/",
                    new DownloadRequest.Other(), null, null);

            assertThat(succeeded).isTrue();
            assertThat(attempts).hasValue(2);
        }
    }

    @Nested
    @DisplayName("下载历史兼容容错")
    class DownloadHistoryCompatibilityTests {

        @BeforeEach
        void setupDownloadPath() {
            lenient().when(downloadSettings.getRootFolder()).thenReturn(tempDir.toString());
            lenient().when(downloadSettings.isUserFlatFolder()).thenReturn(true);
            lenient().when(ugoiraService.processUgoira(anyLong(), any(), any(), anyString(), any(), any(), any()))
                    .thenReturn(1);
            lenient().when(artworkDownloadHistory.allocateRecordTime(0L)).thenReturn(1700000100L);
        }

        @Test
        @SuppressWarnings("unchecked")
        @DisplayName("标签列表中的 null 元素应被忽略且保留其它标签")
        void shouldIgnoreNullTagElements() {
            DownloadRequest.Other other = new DownloadRequest.Other();
            other.setUgoira(true);
            other.setUgoiraZipUrl("https://public-img-zip.pximg.net/test.zip");
            other.setUgoiraDelays(List.of(100));
            other.setTags(java.util.Arrays.asList(
                    null, new WorkTag(7L, "tag", "translated")));

            artworkDownloadExecutor.downloadImages(
                    52345L, "title", List.of("https://public-img-zip.pximg.net/test.zip"),
                    "https://www.pixiv.net/", other, null, null);

            ArtworkDownloadCompletion record = capturedDownloadCompletion();
            assertThat(record.tags()).singleElement().satisfies(tag -> {
                assertThat(tag.tagId()).isEqualTo(7L);
                assertThat(tag.name()).isEqualTo("tag");
                assertThat(tag.translatedName()).isEqualTo("translated");
            });
        }

        @Test
        @DisplayName("下载事实应提交文件名语义而不是宿主数据库标识")
        void shouldSubmitFileNameSemanticsInsteadOfDatabaseIds() {
            DownloadRequest.Other other = new DownloadRequest.Other();
            other.setUgoira(true);
            other.setUgoiraZipUrl("https://public-img-zip.pximg.net/test.zip");
            other.setUgoiraDelays(List.of(100));
            other.setFileNameTemplate("{artwork_title}_p{page}");
            other.setAuthorName("author");

            artworkDownloadExecutor.downloadImages(
                    52346L, "title", List.of("https://public-img-zip.pximg.net/test.zip"),
                    "https://www.pixiv.net/", other, null, null);

            ArtworkDownloadCompletion completion = capturedDownloadCompletion();
            assertThat(completion.fileNameTemplate()).isEqualTo("{artwork_title}_p{page}");
            assertThat(completion.normalizedAuthorName()).isEqualTo("author");
        }
    }

    @Nested
    @DisplayName("下载事实与后置事实处理")
    class PostDownloadFactFailureTests {

        @BeforeEach
        void setupDownloadPath() {
            lenient().when(downloadSettings.getRootFolder()).thenReturn(tempDir.toString());
            lenient().when(downloadSettings.isUserFlatFolder()).thenReturn(true);
            lenient().when(ugoiraService.processUgoira(anyLong(), any(), any(), anyString(), any(), any(), any()))
                    .thenReturn(1);
            lenient().when(artworkDownloadHistory.allocateRecordTime(0L)).thenReturn(1700000100L);
        }

        @Test
        @DisplayName("历史端口失败时不得报告作品下载完成")
        void historyFailureFailsCompletedDownload() {
            doThrow(new RuntimeException("history failed"))
                    .when(artworkDownloadHistory).record(any());

            DownloadRequest.Other other = new DownloadRequest.Other();
            other.setUgoira(true);
            other.setUgoiraZipUrl("https://public-img-zip.pximg.net/test.zip");
            other.setUgoiraDelays(List.of(100));

            boolean succeeded = artworkDownloadExecutor.downloadImagesBlocking(
                    62345L,
                    "title",
                    List.of("https://public-img-zip.pximg.net/test.zip"),
                    "https://www.pixiv.net/",
                    other,
                    null,
                    null
            );

            assertThat(succeeded).isFalse();
            DownloadStatus status = artworkDownloadExecutor.getDownloadStatus(62345L);
            assertThat(status.isCompleted()).isTrue();
            assertThat(status.isFailed()).isTrue();
            assertThat(status.getErrorMessage()).isEqualTo("history failed");
            verify(artworkDownloadStatistics, never()).recordCompleted(anyInt());
        }

        @Test
        @DisplayName("统计端口失败不应翻转已完成媒体下载")
        void statisticsFailureDoesNotFailCompletedDownload() {
            doThrow(new RuntimeException("statistics failed"))
                    .when(artworkDownloadStatistics).recordCompleted(1);

            assertCompletedDownload(62346L);
        }

        @Test
        @DisplayName("作者补齐端口失败不应翻转已完成媒体下载")
        void authorLookupFailureDoesNotFailCompletedDownload() {
            doThrow(new RuntimeException("author failed"))
                    .when(artworkAuthorLookup).resolveMissing(62347L, null);

            assertCompletedDownload(62347L);
        }

        @Test
        @DisplayName("系列观察端口失败不应翻转已完成媒体下载")
        void seriesObservationFailureDoesNotFailCompletedDownload() {
            doThrow(new RuntimeException("series failed"))
                    .when(artworkSeriesObserver).observe(any(), nullable(String.class));

            assertCompletedDownload(62348L);
        }

        private void assertCompletedDownload(long artworkId) {
            DownloadRequest.Other other = new DownloadRequest.Other();
            other.setUgoira(true);
            other.setUgoiraZipUrl("https://public-img-zip.pximg.net/test.zip");
            other.setUgoiraDelays(List.of(100));

            boolean succeeded = artworkDownloadExecutor.downloadImagesBlocking(
                    artworkId,
                    "title",
                    List.of("https://public-img-zip.pximg.net/test.zip"),
                    "https://www.pixiv.net/",
                    other,
                    null,
                    null
            );

            assertThat(succeeded).isTrue();
            DownloadStatus status = artworkDownloadExecutor.getDownloadStatus(artworkId);
            assertThat(status.isCompleted()).isTrue();
            assertThat(status.isFailed()).isFalse();
        }
    }

    @Test
    @DisplayName("插画判重应保留 verifyFiles 参数")
    void delegatesDownloadedLookupWithVerificationFlag() {
        when(artworkDownloadLookup.isDownloaded(42L, true)).thenReturn(true);

        assertThat(artworkDownloadExecutor.isArtworkDownloaded(42L, true)).isTrue();
        assertThat(artworkDownloadExecutor.isArtworkDownloaded(43L, false)).isFalse();

        verify(artworkDownloadLookup).isDownloaded(42L, true);
        verify(artworkDownloadLookup).isDownloaded(43L, false);
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
        void shouldNotRecordHistoryWhenAnyImageFails() throws Exception {
            lenient().when(downloadSettings.getRootFolder()).thenReturn(tempDir.toString());
            lenient().when(downloadSettings.isUserFlatFolder()).thenReturn(false);
            lenient().when(artworkDownloadHistory.allocateRecordTime(0L)).thenReturn(1700000200L);
            byte[] payload = {1, 2, 3};
            stubSuccessfulImageDownload(OK_URL, payload);
            when(pixivImageDownloader.download(
                    eq(URI.create(FAIL_URL)), any(URI.class), any(Path.class), nullable(String.class), any()))
                    .thenReturn(false);

            boolean succeeded = artworkDownloadExecutor.downloadImagesBlocking(67890L, "title",
                    List.of(OK_URL, FAIL_URL), "https://www.pixiv.net/", new DownloadRequest.Other(), null, null);

            assertThat(succeeded).isFalse();
            DownloadStatus status = artworkDownloadExecutor.getDownloadStatus(67890L);
            assertThat(status.isFailed()).isTrue();
            assertThat(status.isCompleted()).isTrue();
            assertThat(status.getSuccessCount()).isEqualTo(1);
            assertThat(status.getFailedCount()).isEqualTo(1);
            assertThat(status.getErrorMessage()).contains("1/2");
            verify(artworkDownloadHistory, never()).record(any());
            verify(artworkDownloadStatistics, never()).recordCompleted(anyInt());
        }
    }
    private static final class ProbeVmError extends VirtualMachineError {
    }
}

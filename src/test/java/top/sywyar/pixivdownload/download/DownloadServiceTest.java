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
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.author.AuthorService;
import top.sywyar.pixivdownload.download.config.DownloadConfig;
import top.sywyar.pixivdownload.download.db.ArtworkRecord;
import top.sywyar.pixivdownload.download.db.PixivDatabase;
import top.sywyar.pixivdownload.download.request.DownloadRequest;
import top.sywyar.pixivdownload.download.response.StatisticsResponse;
import top.sywyar.pixivdownload.quota.UserQuotaService;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DownloadService 单元测试")
class DownloadServiceTest {
    @TempDir
    Path tempDir;

    @Mock
    private DownloadConfig downloadConfig;
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

    private DownloadService downloadService;

    @BeforeEach
    void setUp() {
        downloadService = new DownloadService(downloadConfig, eventPublisher, pixivDatabase, userQuotaService,
                downloadRestTemplate, taskScheduler, pixivBookmarkService, ugoiraService, authorService);
    }

    // ========== validatePixivUrl (SSRF 防护) ==========

    @Nested
    @DisplayName("validatePixivUrl - SSRF 防护")
    class ValidatePixivUrlTests {

        @Test
        @DisplayName("合法的 Pixiv 图片 URL 应通过校验")
        void shouldAcceptValidPixivUrl() {
            assertThatCode(() -> DownloadService.validatePixivUrl(
                    "https://i.pximg.net/img-original/img/2024/01/01/00/00/00/12345_p0.jpg"
            )).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("null 和空字符串应跳过校验")
        void shouldSkipNullOrBlank() {
            assertThatCode(() -> DownloadService.validatePixivUrl(null)).doesNotThrowAnyException();
            assertThatCode(() -> DownloadService.validatePixivUrl("")).doesNotThrowAnyException();
            assertThatCode(() -> DownloadService.validatePixivUrl("   ")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("HTTP 协议应被拒绝（仅允许 HTTPS）")
        void shouldRejectHttpUrl() {
            assertThatThrownBy(() -> DownloadService.validatePixivUrl(
                    "http://i.pximg.net/img/12345.jpg"
            )).isInstanceOf(SecurityException.class)
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
            assertThatThrownBy(() -> DownloadService.validatePixivUrl(url))
                    .isInstanceOf(SecurityException.class)
                    .hasMessageContaining("域名不在白名单内");
        }

        @Test
        @DisplayName("FTP 等非 HTTPS 协议应被拒绝")
        void shouldRejectFtpProtocol() {
            assertThatThrownBy(() -> DownloadService.validatePixivUrl(
                    "ftp://i.pximg.net/img.jpg"
            )).isInstanceOf(SecurityException.class)
              .hasMessageContaining("只允许 HTTPS 协议");
        }

        @Test
        @DisplayName("无效 URL 格式应被拒绝")
        void shouldRejectInvalidUrl() {
            assertThatThrownBy(() -> DownloadService.validatePixivUrl(
                    "not a url at all %%"
            )).isInstanceOf(SecurityException.class);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "https://i.pximg.net/img-master/img/2024/01/01/00/00/00/12345_p0_master1200.jpg",
                "https://i-f.pximg.net/img-original/img/2024/01/01/00/00/00/12345_p0.png",
                "https://public-img-zip.pximg.net/works/12345/2024/01/01/00/00/00/12345_ugoira600x600.zip"
        })
        @DisplayName("各种合法 pximg.net 子域名应通过")
        void shouldAcceptVariousPixivSubdomains(String url) {
            assertThatCode(() -> DownloadService.validatePixivUrl(url))
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
            assertThat(downloadService.getDownloadStatus(99999L)).isNull();
        }

        @Test
        @DisplayName("getDownloadStatus() 无参版本应返回空列表（无活跃下载时）")
        void shouldReturnEmptyListWhenNoActiveDownloads() {
            List<Long> active = downloadService.getDownloadStatus();
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
            assertThatCode(() -> downloadService.cancelDownload(99999L))
                    .doesNotThrowAnyException();
        }
    }

    // ========== getStatistics ==========

    @Nested
    @DisplayName("getStatistics")
    class GetStatisticsTests {

        @Test
        @DisplayName("正常获取统计数据")
        void shouldReturnStatistics() {
            when(pixivDatabase.getStats()).thenReturn(new int[]{100, 500, 30});

            StatisticsResponse response = downloadService.getStatistics();

            assertThat(response.isSuccess()).isTrue();
            assertThat(response.getTotalArtworks()).isEqualTo(100);
            assertThat(response.getTotalImages()).isEqualTo(500);
            assertThat(response.getTotalMoved()).isEqualTo(30);
        }

        @Test
        @DisplayName("数据库异常时应向上传播")
        void shouldPropagateOnDatabaseError() {
            when(pixivDatabase.getStats()).thenThrow(new RuntimeException("DB error"));

            assertThatCode(() -> downloadService.getStatistics())
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("DB error");
        }
    }

    // ========== getDownloadedRecord ==========

    @Nested
    @DisplayName("getDownloadedRecord")
    class GetDownloadedRecordTests {

        @Test
        @DisplayName("已存在的作品应返回记录")
        void shouldReturnArtworkRecord() {
            ArtworkRecord record = new ArtworkRecord(12345L, "测试作品", "/path/to/folder",
                    3, "jpg", 1700000000L, false, null, null, false, null);
            when(pixivDatabase.getArtwork(12345L)).thenReturn(record);

            ArtworkRecord result = downloadService.getDownloadedRecord(12345L);

            assertThat(result).isNotNull();
            assertThat(result.artworkId()).isEqualTo(12345L);
            assertThat(result.title()).isEqualTo("测试作品");
        }

        @Test
        @DisplayName("不存在的作品应返回 null")
        void shouldReturnNullForNonExistentArtwork() {
            when(pixivDatabase.getArtwork(99999L)).thenReturn(null);

            assertThat(downloadService.getDownloadedRecord(99999L)).isNull();
        }

        @Test
        @DisplayName("数据库异常时应向上传播")
        void shouldPropagateOnDatabaseError() {
            when(pixivDatabase.getArtwork(12345L)).thenThrow(new RuntimeException("DB error"));

            assertThatCode(() -> downloadService.getDownloadedRecord(12345L))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("DB error");
        }

        @Test
        @DisplayName("verifyFiles=true 且目录为空时应删除脏记录")
        void shouldDeleteStaleRecordWhenDirectoryIsEmpty() throws Exception {
            Path folder = Files.createDirectories(tempDir.resolve("12345"));
            ArtworkRecord record = new ArtworkRecord(12345L, "测试作品", folder.toString(),
                    3, "jpg", 1700000000L, false, null, null, false, null);
            when(pixivDatabase.getArtwork(12345L)).thenReturn(record);

            ArtworkRecord result = downloadService.getDownloadedRecord(12345L, true);

            assertThat(result).isNull();
            verify(pixivDatabase).deleteArtwork(12345L);
        }

        @Test
        @DisplayName("verifyFiles=true 且目录里没有作品图片文件时应删除脏记录")
        void shouldDeleteStaleRecordWhenDirectoryHasNoArtworkImages() throws Exception {
            Path folder = Files.createDirectories(tempDir.resolve("22345"));
            Files.writeString(folder.resolve("note.txt"), "orphan");
            Files.writeString(folder.resolve("22345_p0.json"), "{}");
            ArtworkRecord record = new ArtworkRecord(22345L, "测试作品", folder.toString(),
                    1, "jpg", 1700000000L, false, null, null, false, null);
            when(pixivDatabase.getArtwork(22345L)).thenReturn(record);

            ArtworkRecord result = downloadService.getDownloadedRecord(22345L, true);

            assertThat(result).isNull();
            verify(pixivDatabase).deleteArtwork(22345L);
        }

        @Test
        @DisplayName("verifyFiles=true 时应优先使用 move_folder 检测作品图片文件")
        void shouldUseMoveFolderWhenArtworkImageExists() throws Exception {
            Path originalFolder = Files.createDirectories(tempDir.resolve("32345-original"));
            Path movedFolder = Files.createDirectories(tempDir.resolve("32345-moved"));
            Files.write(movedFolder.resolve("32345_p0.webp"), new byte[]{1, 2, 3});
            ArtworkRecord record = new ArtworkRecord(32345L, "测试作品", originalFolder.toString(),
                    1, "webp", 1700000000L, true, movedFolder.toString(), 1700000001L, false, null);
            when(pixivDatabase.getArtwork(32345L)).thenReturn(record);

            ArtworkRecord result = downloadService.getDownloadedRecord(32345L, true);

            assertThat(result).isSameAs(record);
            verify(pixivDatabase, never()).deleteArtwork(32345L);
        }
    }

    // ========== recordStatistics ==========

    @Nested
    @DisplayName("recordStatistics")
    class RecordStatisticsTests {

        @Test
        @DisplayName("正常记录统计不抛异常")
        void shouldRecordStatisticsSuccessfully() {
            downloadService.recordStatistics(5);
            verify(pixivDatabase).incrementStats(5);
        }

        @Test
        @DisplayName("数据库异常时不向上抛出")
        void shouldNotThrowOnDatabaseError() {
            doThrow(new RuntimeException("DB error")).when(pixivDatabase).incrementStats(anyInt());

            assertThatCode(() -> downloadService.recordStatistics(5))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("author handling")
    class AuthorHandlingTests {

        @BeforeEach
        void setupDownloadPath() {
            lenient().when(downloadConfig.getRootFolder()).thenReturn(tempDir.toString());
            lenient().when(downloadConfig.isUserFlatFolder()).thenReturn(true);
            lenient().when(ugoiraService.processUgoira(anyLong(), any(), any(), anyString(), any()))
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

            downloadService.downloadImages(12345L, "test", List.of("https://public-img-zip.pximg.net/test.zip"),
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

            downloadService.downloadImages(12345L, "test", List.of("https://public-img-zip.pximg.net/test.zip"),
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

            downloadService.downloadImages(12345L, "test", List.of("https://public-img-zip.pximg.net/test.zip"),
                    "https://www.pixiv.net/", other, null, null);

            verify(pixivDatabase).insertArtwork(12345L, "test", tempDir.resolve("12345").toAbsolutePath().toString(),
                    1, "webp", 1700000100L, false, 999L);
        }
    }

    // ========== getSortTimeArtworkPaged ==========

    @Nested
    @DisplayName("getSortTimeArtworkPaged")
    class GetSortTimeArtworkPagedTests {

        @Test
        @DisplayName("分页查询应正确计算 offset")
        void shouldCalculateOffsetCorrectly() {
            when(pixivDatabase.getArtworkIdsSortedByTimeDescPaged(20, 10))
                    .thenReturn(List.of(100L, 99L, 98L));

            List<Long> result = downloadService.getSortTimeArtworkPaged(2, 10);

            assertThat(result).containsExactly(100L, 99L, 98L);
            verify(pixivDatabase).getArtworkIdsSortedByTimeDescPaged(20, 10);
        }
    }

    // ========== getArtworkCount ==========

    @Test
    @DisplayName("getArtworkCount 应委托给数据库")
    void shouldDelegateCountToDatabase() {
        when(pixivDatabase.countArtworks()).thenReturn(42L);

        assertThat(downloadService.getArtworkCount()).isEqualTo(42L);
    }

    // ========== getDownloadedRecord (List version) ==========

    @Test
    @DisplayName("getDownloadedRecord() 应返回所有作品ID的字符串列表")
    void shouldReturnAllArtworkIdsAsStrings() {
        when(pixivDatabase.getAllArtworkIds()).thenReturn(List.of(1L, 2L, 3L));

        List<String> result = downloadService.getDownloadedRecord();

        assertThat(result).containsExactly("1", "2", "3");
    }

    // ========== findFileByName ==========

    @Nested
    @DisplayName("findFileByName")
    class FindFileByNameTests {

        @Test
        @DisplayName("目录不存在时应返回 null")
        void shouldReturnNullWhenDirectoryNotExists() {
            File result = DownloadService.findFileByName("/non/existent/path", "test");
            assertThat(result).isNull();
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

            assertThat(status.getStatusDescription()).isEqualTo("等待开始");

            status.setCurrentImageIndex(2);
            assertThat(status.getStatusDescription()).contains("下载中");

            status.setCompleted(true);
            status.setSuccessCount(5);
            assertThat(status.getStatusDescription()).contains("已完成");

            status.setCompleted(false);
            status.setCancelled(true);
            assertThat(status.getStatusDescription()).isEqualTo("已取消");

            status.setCancelled(false);
            status.setFailed(true);
            status.setErrorMessage("网络超时");
            assertThat(status.getStatusDescription()).contains("失败").contains("网络超时");
        }
    }
}

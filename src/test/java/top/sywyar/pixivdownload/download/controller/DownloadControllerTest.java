package top.sywyar.pixivdownload.download.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.sywyar.pixivdownload.GlobalExceptionHandler;
import top.sywyar.pixivdownload.author.AuthorService;
import top.sywyar.pixivdownload.download.DownloadService;
import top.sywyar.pixivdownload.download.DownloadStatus;
import top.sywyar.pixivdownload.download.db.ArtworkRecord;
import top.sywyar.pixivdownload.download.db.PixivDatabase;
import top.sywyar.pixivdownload.download.request.DownloadRequest;
import top.sywyar.pixivdownload.download.response.ImageResponse;
import top.sywyar.pixivdownload.download.response.StatisticsResponse;
import top.sywyar.pixivdownload.quota.MultiModeConfig;
import top.sywyar.pixivdownload.quota.UserQuotaService;
import top.sywyar.pixivdownload.setup.SetupService;

import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DownloadController 单元测试")
class DownloadControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private DownloadService downloadService;
    @Mock
    private SetupService setupService;
    @Mock
    private UserQuotaService userQuotaService;
    @Mock
    private PixivDatabase pixivDatabase;
    @Mock
    private AuthorService authorService;

    private MultiModeConfig multiModeConfig;

    @BeforeEach
    void setUp() {
        multiModeConfig = new MultiModeConfig();
        DownloadController controller = new DownloadController(
                downloadService, setupService, userQuotaService, multiModeConfig, pixivDatabase, authorService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // ========== GET /api/download/status ==========

    @Test
    @DisplayName("GET /api/download/status 应返回服务状态")
    void shouldReturnServiceStatus() throws Exception {
        mockMvc.perform(get("/api/download/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("服务运行正常"));
    }

    // ========== GET /api/download/status/{artworkId} ==========

    @Nested
    @DisplayName("GET /api/download/status/{artworkId}")
    class GetDownloadStatusTests {

        @Test
        @DisplayName("已存在的下载任务应返回状态")
        void shouldReturnDownloadStatus() throws Exception {
            DownloadStatus status = new DownloadStatus(12345L, "测试作品", 5);
            status.setDownloadedCount(3);
            status.setCurrentImageIndex(2);
            status.setDownloadPath("/path/to/download");
            when(downloadService.getDownloadStatus(12345L)).thenReturn(status);

            mockMvc.perform(get("/api/download/status/12345"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.artworkId").value(12345))
                    .andExpect(jsonPath("$.totalImages").value(5))
                    .andExpect(jsonPath("$.downloadedCount").value(3));
        }

        @Test
        @DisplayName("不存在的下载任务应返回未找到")
        void shouldReturnNotFoundStatus() throws Exception {
            when(downloadService.getDownloadStatus(99999L)).thenReturn(null);

            mockMvc.perform(get("/api/download/status/99999"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value("未找到该作品的下载状态"));
        }
    }

    // ========== GET /api/download/status/active ==========

    @Test
    @DisplayName("GET /api/download/status/active 应返回活跃下载列表")
    void shouldReturnActiveDownloads() throws Exception {
        when(downloadService.getDownloadStatus()).thenReturn(List.of(1L, 2L, 3L));

        mockMvc.perform(get("/api/download/status/active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.artworkIds", hasSize(3)))
                .andExpect(jsonPath("$.artworkIds[0]").value(1))
                .andExpect(jsonPath("$.artworkIds[1]").value(2))
                .andExpect(jsonPath("$.artworkIds[2]").value(3));
    }

    // ========== POST /api/cancel/{artworkId} ==========

    @Test
    @DisplayName("POST /api/cancel/{artworkId} 应取消下载")
    void shouldCancelDownload() throws Exception {
        mockMvc.perform(post("/api/cancel/12345"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("下载任务已取消"));

        verify(downloadService).cancelDownload(12345L);
    }

    // ========== POST /api/download/pixiv ==========

    @Nested
    @DisplayName("POST /api/download/pixiv")
    class DownloadPixivTests {

        @Test
        @DisplayName("合法请求应成功发起下载")
        void shouldStartDownload() throws Exception {
            when(setupService.getMode()).thenReturn("solo");

            DownloadRequest request = new DownloadRequest();
            request.setArtworkId(12345L);
            request.setTitle("测试作品");
            request.setImageUrls(List.of("https://i.pximg.net/img/12345_p0.jpg"));

            mockMvc.perform(post("/api/download/pixiv")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("下载任务已开始处理"));
        }

        @Test
        @DisplayName("非法 URL（非 pximg.net）应返回 400")
        void shouldReturn400ForInvalidUrl() throws Exception {
            DownloadRequest request = new DownloadRequest();
            request.setArtworkId(12345L);
            request.setTitle("测试");
            request.setImageUrls(List.of("https://evil.com/malicious.jpg"));

            mockMvc.perform(post("/api/download/pixiv")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("多人模式配额超出时应返回 429")
        void shouldReturn429WhenQuotaExceeded() throws Exception {
            when(setupService.getMode()).thenReturn("multi");
            multiModeConfig.getQuota().setEnabled(true);
            multiModeConfig.getQuota().setArchiveExpireMinutes(60);
            multiModeConfig.setPostDownloadMode("pack-and-delete");

            when(userQuotaService.checkAndReserve(anyString(), anyInt()))
                    .thenReturn(new UserQuotaService.QuotaCheckResult(false, 50, 50, 3600));
            when(userQuotaService.triggerArchive(anyString())).thenReturn("archive-token");

            DownloadRequest request = new DownloadRequest();
            request.setArtworkId(12345L);
            request.setTitle("测试");
            request.setImageUrls(List.of("https://i.pximg.net/img/12345_p0.jpg"));

            mockMvc.perform(post("/api/download/pixiv")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request))
                            .header("X-User-UUID", "12345678-1234-1234-1234-123456789abc"))
                    .andExpect(status().is(429))
                    .andExpect(jsonPath("$.quotaExceeded").value(true))
                    .andExpect(jsonPath("$.archiveToken").value("archive-token"));
        }

        @Test
        @DisplayName("多人模式下已登录管理员应跳过配额检查")
        void shouldSkipQuotaCheckForAdminInMultiMode() throws Exception {
            when(setupService.getMode()).thenReturn("multi");
            when(setupService.isAdminLoggedIn(any())).thenReturn(true);
            multiModeConfig.getQuota().setEnabled(true);

            DownloadRequest request = new DownloadRequest();
            request.setArtworkId(12345L);
            request.setTitle("测试");
            request.setImageUrls(List.of("https://i.pximg.net/img/12345_p0.jpg"));

            mockMvc.perform(post("/api/download/pixiv")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            verify(userQuotaService, never()).checkAndReserve(anyString(), anyInt());
            verify(downloadService).downloadImages(
                    eq(12345L),
                    eq("测试"),
                    eq(List.of("https://i.pximg.net/img/12345_p0.jpg")),
                    eq("https://www.pixiv.net/"),
                    any(),
                    any(),
                    isNull()
            );
        }
    }

    // ========== GET /api/downloaded/{artworkId} ==========

    @Nested
    @DisplayName("GET /api/downloaded/{artworkId}")
    class GetDownloadedTests {

        @Test
        @DisplayName("已下载的作品应返回详情")
        void shouldReturnDownloadedArtwork() throws Exception {
            ArtworkRecord record = new ArtworkRecord(12345L, "测试作品", "/path/to/folder",
                    3, "jpg", 1700000000L, false, null, null, false, null);
            when(downloadService.getDownloadedRecord(12345L, false)).thenReturn(record);

            mockMvc.perform(get("/api/downloaded/12345"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.artworkId").value(12345))
                    .andExpect(jsonPath("$.title").value("测试作品"))
                    .andExpect(jsonPath("$.count").value(3));
        }

        @Test
        @DisplayName("未找到的作品应返回 400")
        void shouldReturn400ForNotFound() throws Exception {
            when(downloadService.getDownloadedRecord(99999L, false)).thenReturn(null);

            mockMvc.perform(get("/api/downloaded/99999"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("verifyFiles=true 时应透传实际目录校验参数")
        void shouldPassVerifyFilesFlag() throws Exception {
            ArtworkRecord record = new ArtworkRecord(12345L, "娴嬭瘯浣滃搧", "/path/to/folder",
                    1, "jpg", 1700000000L, false, null, null, false, null);
            when(downloadService.getDownloadedRecord(12345L, true)).thenReturn(record);

            mockMvc.perform(get("/api/downloaded/12345").param("verifyFiles", "true"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.artworkId").value(12345));

            verify(downloadService).getDownloadedRecord(12345L, true);
        }
    }

    // ========== POST /api/downloaded/batch ==========

    @Test
    @DisplayName("POST /api/downloaded/batch 应批量返回作品信息")
    void shouldReturnBatchArtworks() throws Exception {
        ArtworkRecord record1 = new ArtworkRecord(1L, "A", "/a", 1, "jpg", 100L, false, null, null, null, null);
        ArtworkRecord record2 = new ArtworkRecord(2L, "B", "/b", 2, "png", 200L, false, null, null, null, null);
        when(downloadService.getDownloadedRecord(1L)).thenReturn(record1);
        when(downloadService.getDownloadedRecord(2L)).thenReturn(record2);
        when(downloadService.getDownloadedRecord(3L)).thenReturn(null);

        mockMvc.perform(post("/api/downloaded/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"artworkIds\": [1, 2, 3]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.artworks", hasSize(2)))
                .andExpect(jsonPath("$.artworks[0].artworkId").value(1))
                .andExpect(jsonPath("$.artworks[1].artworkId").value(2));
    }

    // ========== GET /api/downloaded/statistics ==========

    @Test
    @DisplayName("GET /api/downloaded/statistics 应返回统计数据")
    void shouldReturnStatistics() throws Exception {
        when(downloadService.getStatistics())
                .thenReturn(new StatisticsResponse(true, 100, 500, 30, "获取成功"));

        mockMvc.perform(get("/api/downloaded/statistics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.totalArtworks").value(100))
                .andExpect(jsonPath("$.totalImages").value(500))
                .andExpect(jsonPath("$.totalMoved").value(30));
    }

    // ========== GET /api/downloaded/by-move-folder ==========

    @Nested
    @DisplayName("GET /api/downloaded/by-move-folder")
    class GetByMoveFolderTests {

        @Test
        @DisplayName("找到的移动路径应返回作品ID")
        void shouldReturnArtworkId() throws Exception {
            ArtworkRecord record = new ArtworkRecord(12345L, "test", "/path", 1, "jpg",
                    100L, true, "/moved", 200L, null, null);
            when(pixivDatabase.getArtworkByMoveFolder("/moved")).thenReturn(record);

            mockMvc.perform(get("/api/downloaded/by-move-folder").param("path", "/moved"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.artworkId").value(12345));
        }

        @Test
        @DisplayName("未找到应返回 404")
        void shouldReturn404WhenNotFound() throws Exception {
            when(pixivDatabase.getArtworkByMoveFolder("/nonexistent")).thenReturn(null);

            mockMvc.perform(get("/api/downloaded/by-move-folder").param("path", "/nonexistent"))
                    .andExpect(status().isNotFound());
        }
    }

    // ========== POST /api/downloaded/move/{artworkId} ==========

    @Test
    @DisplayName("POST /api/downloaded/move/{artworkId} 应记录移动操作")
    void shouldRecordMoveOperation() throws Exception {
        mockMvc.perform(post("/api/downloaded/move/12345")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"movePath\":\"/new/path\",\"moveTime\":1700000000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(downloadService).moveArtWork(12345L, "/new/path", 1700000000L);
    }

    // ========== GET /api/downloaded/history ==========

    @Test
    @DisplayName("GET /api/downloaded/history 应返回所有作品ID")
    void shouldReturnHistory() throws Exception {
        when(downloadService.getDownloadedRecord()).thenReturn(List.of("1", "2", "3"));

        mockMvc.perform(get("/api/downloaded/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.artworkIds", hasSize(3)));
    }

    // ========== GET /api/downloaded/history/paged ==========

    @Test
    @DisplayName("GET /api/downloaded/history/paged 应返回分页历史")
    void shouldReturnPagedHistory() throws Exception {
        when(downloadService.getArtworkCount()).thenReturn(25L);
        when(downloadService.getSortTimeArtworkPaged(0, 10)).thenReturn(List.of(25L, 24L));

        ArtworkRecord r1 = new ArtworkRecord(25L, "A", "/a", 1, "jpg", 200L, false, null, null, null, null);
        ArtworkRecord r2 = new ArtworkRecord(24L, "B", "/b", 2, "png", 100L, false, null, null, null, null);
        when(downloadService.getDownloadedRecord(25L)).thenReturn(r1);
        when(downloadService.getDownloadedRecord(24L)).thenReturn(r2);

        mockMvc.perform(get("/api/downloaded/history/paged")
                        .param("page", "0").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(25))
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.totalPages").value(3));
    }

    // ========== GET /api/downloaded/thumbnail & image ==========

    @Nested
    @DisplayName("GET /api/downloaded/thumbnail & image")
    class ThumbnailAndImageTests {

        @Test
        @DisplayName("获取缩略图成功")
        void shouldReturnThumbnail() throws Exception {
            ImageResponse imageResponse = new ImageResponse(true, "base64data", "jpg", 100, 200, 150, "成功");
            when(downloadService.getImageResponse(12345L, 0, true)).thenReturn(imageResponse);

            mockMvc.perform(get("/api/downloaded/thumbnail/12345/0"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.image").value("base64data"));
        }

        @Test
        @DisplayName("获取缩略图不存在应返回 404")
        void shouldReturn404ForMissingThumbnail() throws Exception {
            when(downloadService.getImageResponse(99999L, 0, true)).thenReturn(null);

            mockMvc.perform(get("/api/downloaded/thumbnail/99999/0"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("获取原始图片成功")
        void shouldReturnImage() throws Exception {
            ImageResponse imageResponse = new ImageResponse(true, "base64data", "png", 200, 400, 300, "成功");
            when(downloadService.getImageResponse(12345L, 0, false)).thenReturn(imageResponse);

            mockMvc.perform(get("/api/downloaded/image/12345/0"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));
        }
    }
}

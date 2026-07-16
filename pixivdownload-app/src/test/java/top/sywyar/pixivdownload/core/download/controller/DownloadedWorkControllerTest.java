package top.sywyar.pixivdownload.core.download.controller;

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
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.core.download.ArtworkMetadataRecoveryService;
import top.sywyar.pixivdownload.core.download.ArtworkMoveService;
import top.sywyar.pixivdownload.core.download.DownloadStatisticsService;
import top.sywyar.pixivdownload.core.download.DownloadedArtworkService;
import top.sywyar.pixivdownload.core.db.ArtworkRecord;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.core.download.response.StatisticsResponse;

import java.util.List;
import java.util.Set;

import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DownloadedWorkController 单元测试")
class DownloadedWorkControllerTest {
    private static final AppMessages APP_MESSAGES = TestI18nBeans.appMessages();

    private MockMvc mockMvc;

    @Mock
    private DownloadedArtworkService downloadedArtworkService;
    @Mock
    private ArtworkMetadataRecoveryService artworkMetadataRecoveryService;
    @Mock
    private DownloadStatisticsService downloadStatisticsService;
    @Mock
    private ArtworkMoveService artworkMoveService;
    @Mock
    private PixivDatabase pixivDatabase;
    @Mock
    private AuthorService authorService;
    @Mock
    private top.sywyar.pixivdownload.setup.guest.GuestAccessGuard guestAccessGuard;
    @Mock
    private top.sywyar.pixivdownload.core.metadata.artwork.GalleryRepository galleryRepository;

    @BeforeEach
    void setUp() {
        DownloadedWorkController controller = new DownloadedWorkController(
                downloadedArtworkService, artworkMetadataRecoveryService, downloadStatisticsService,
                artworkMoveService, pixivDatabase, authorService, guestAccessGuard, galleryRepository, APP_MESSAGES);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(APP_MESSAGES))
                .build();
    }

    // ========== GET /api/downloaded/{artworkId} ==========

    @Nested
    @DisplayName("GET /api/downloaded/{artworkId}")
    class GetDownloadedTests {

        @Test
        @DisplayName("已下载的作品应返回详情")
        void shouldReturnDownloadedArtwork() throws Exception {
            ArtworkRecord record = new ArtworkRecord(12345L, "测试作品", "/path/to/folder",
                    3, "jpg", 1700000000L, false, null, null, 0, true, null, null);
            when(downloadedArtworkService.getDownloadedRecord(12345L, false)).thenReturn(record);

            mockMvc.perform(get("/api/downloaded/12345"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.artworkId").value(12345))
                    .andExpect(jsonPath("$.title").value("测试作品"))
                    .andExpect(jsonPath("$.count").value(3))
                    .andExpect(jsonPath("$.isAi").value(true));
        }

        @Test
        @DisplayName("未找到的作品应返回 400")
        void shouldReturn400ForNotFound() throws Exception {
            when(downloadedArtworkService.getDownloadedRecord(99999L, false)).thenReturn(null);

            mockMvc.perform(get("/api/downloaded/99999"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("verifyFiles=true 时应透传实际目录校验参数")
        void shouldPassVerifyFilesFlag() throws Exception {
            ArtworkRecord record = new ArtworkRecord(12345L, "娴嬭瘯浣滃搧", "/path/to/folder",
                    1, "jpg", 1700000000L, false, null, null, 0, null, null, null);
            when(downloadedArtworkService.getDownloadedRecord(12345L, true)).thenReturn(record);

            mockMvc.perform(get("/api/downloaded/12345").param("verifyFiles", "true"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.artworkId").value(12345));

            verify(downloadedArtworkService).getDownloadedRecord(12345L, true);
        }

        @Test
        @DisplayName("响应应输出 xRestrict 整数字段，且不再含旧的 R18 布尔字段")
        void shouldSerializeXRestrictInsteadOfLegacyR18Boolean() throws Exception {
            ArtworkRecord record = new ArtworkRecord(12345L, "title", "/path/to/folder",
                    1, "jpg", 1700000000L, false, null, null, 2, null, null, null);
            when(downloadedArtworkService.getDownloadedRecord(12345L, false)).thenReturn(record);

            mockMvc.perform(get("/api/downloaded/12345"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.xRestrict").value(2))
                    .andExpect(jsonPath("$.R18").doesNotExist())
                    .andExpect(jsonPath("$.r18").doesNotExist());
        }
    }

    // ========== POST /api/downloaded/batch ==========

    @Test
    @DisplayName("POST /api/downloaded/batch 默认仅批量返回有效作品")
    void shouldReturnBatchArtworks() throws Exception {
        ArtworkRecord record1 = new ArtworkRecord(1L, "A", "/a", 1, "jpg", 100L, false, null, null, null, true, null, null);
        ArtworkRecord record2 = new ArtworkRecord(2L, "B", "/b", 2, "png", 200L, false, null, null, null, false, null, null);
        ArtworkRecord deleted = new ArtworkRecord(3L, "C", "/c", 1, "jpg", 300L,
                false, null, null, null, false, null, null, null, null, null, null,
                true, null, null);
        when(pixivDatabase.getArtworks(List.of(1L, 2L, 3L))).thenReturn(List.of(record1, record2, deleted));

        mockMvc.perform(post("/api/downloaded/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"artworkIds\": [1, 2, 3]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.artworks", hasSize(2)))
                .andExpect(jsonPath("$.artworks[0].artworkId").value(1))
                .andExpect(jsonPath("$.artworks[0].isAi").value(true))
                .andExpect(jsonPath("$.artworks[1].artworkId").value(2))
                .andExpect(jsonPath("$.artworks[1].isAi").value(false))
                .andExpect(jsonPath("$.deletedArtworkIds", empty()));

        verify(pixivDatabase).getArtworks(List.of(1L, 2L, 3L));
        verify(pixivDatabase).getArtworkTags(List.of(1L, 2L));
        verify(pixivDatabase).getFileNameTemplates(Set.of(1L));
        verify(pixivDatabase, never()).getArtworkTags(anyLong());
        verify(pixivDatabase, never()).getFileNameTemplate(anyLong());
        verify(downloadedArtworkService, never()).getDownloadedRecord(anyLong());
    }

    @Test
    @DisplayName("POST /api/downloaded/batch 可将软删除记录作为独立 ID 列表返回")
    void shouldReturnDeletedArtworkIdsSeparatelyWhenRequested() throws Exception {
        ArtworkRecord active = new ArtworkRecord(1L, "A", "/a", 1, "jpg", 100L,
                false, null, null, null, true, null, null);
        ArtworkRecord deleted = new ArtworkRecord(2L, "B", "/b", 2, "png", 200L,
                false, null, null, null, false, null, null, null, null, null, null,
                true, null, null);
        when(pixivDatabase.getArtworks(List.of(1L, 2L, 3L))).thenReturn(List.of(active, deleted));

        mockMvc.perform(post("/api/downloaded/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"artworkIds\": [1, 2, 3], \"includeDeleted\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.artworks", hasSize(1)))
                .andExpect(jsonPath("$.artworks[0].artworkId").value(1))
                .andExpect(jsonPath("$.deletedArtworkIds", contains(2)));
    }

    // ========== GET /api/downloaded/statistics ==========

    @Test
    @DisplayName("GET /api/downloaded/statistics 应返回统计数据")
    void shouldReturnStatistics() throws Exception {
        when(downloadStatisticsService.getStatistics())
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
                    100L, true, "/moved", 200L, null, null, null, null);
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

        verify(artworkMoveService).moveArtWork(12345L, "/new/path", 1700000000L, null);
    }

    @Test
    @DisplayName("POST /api/downloaded/move/{artworkId} 应透传 classifierTargetFolder")
    void shouldForwardClassifierTargetFolder() throws Exception {
        mockMvc.perform(post("/api/downloaded/move/12345")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"movePath\":\"/dst/0/\",\"moveTime\":1700000000,"
                                + "\"classifierTargetFolder\":\"/dst/\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(artworkMoveService).moveArtWork(12345L, "/dst/0", 1700000000L, "/dst");
    }

    // ========== GET /api/downloaded/history ==========

    @Test
    @DisplayName("GET /api/downloaded/history 应返回所有作品ID")
    void shouldReturnHistory() throws Exception {
        when(downloadedArtworkService.getDownloadedRecord()).thenReturn(List.of("1", "2", "3"));

        mockMvc.perform(get("/api/downloaded/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.artworkIds", hasSize(3)));
    }

    // ========== GET /api/downloaded/history/paged ==========

    @Test
    @DisplayName("GET /api/downloaded/history/paged 应返回分页历史")
    void shouldReturnPagedHistory() throws Exception {
        when(downloadedArtworkService.getArtworkCount()).thenReturn(25L);
        when(downloadedArtworkService.getSortTimeArtworkPaged(0, 10)).thenReturn(List.of(25L, 24L));

        ArtworkRecord r1 = new ArtworkRecord(25L, "A", "/a", 1, "jpg", 200L, false, null, null, null, true, null, null);
        ArtworkRecord r2 = new ArtworkRecord(24L, "B", "/b", 2, "png", 100L, false, null, null, null, false, null, null);
        when(downloadedArtworkService.getDownloadedRecord(25L)).thenReturn(r1);
        when(downloadedArtworkService.getDownloadedRecord(24L)).thenReturn(r2);

        mockMvc.perform(get("/api/downloaded/history/paged")
                        .param("page", "0").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(25))
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[0].isAi").value(true))
                .andExpect(jsonPath("$.content[1].isAi").value(false))
                .andExpect(jsonPath("$.totalPages").value(3));
    }
}

package top.sywyar.pixivdownload.core.download.controller;

import top.sywyar.pixivdownload.series.MangaSeriesService;
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
import top.sywyar.pixivdownload.core.download.DownloadStatisticsService;
import top.sywyar.pixivdownload.core.download.DownloadedArtworkService;
import top.sywyar.pixivdownload.core.db.ArtworkRecord;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.core.download.response.StatisticsResponse;
import top.sywyar.pixivdownload.core.work.model.WorkRestriction;
import top.sywyar.pixivdownload.core.metadata.artwork.GalleryRepository;
import top.sywyar.pixivdownload.setup.guest.GuestInviteSession;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.LongStream;

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
    private PixivDatabase pixivDatabase;
    @Mock
    private AuthorService authorService;
    @Mock
    private MangaSeriesService mangaSeriesService;
    @Mock
    private top.sywyar.pixivdownload.setup.guest.GuestAccessGuard guestAccessGuard;
    @Mock
    private top.sywyar.pixivdownload.core.metadata.artwork.GalleryRepository galleryRepository;

    @BeforeEach
    void setUp() {
        DownloadedWorkController controller = new DownloadedWorkController(
                downloadedArtworkService, artworkMetadataRecoveryService, downloadStatisticsService,
                pixivDatabase, authorService, mangaSeriesService, guestAccessGuard, galleryRepository, APP_MESSAGES);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(APP_MESSAGES))
                .build();
    }

    @Test
    void invalidRecoveryBodiesReturn400BeforeAccessingTheLibrary() throws Exception {
        for (String body : List.of("", "{", "null", "{}", "{\"title\":\"  \"}")) {
            mockMvc.perform(post("/api/downloaded/42/recover-metadata")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(body.startsWith("{") && body.endsWith("}")
                            ? "error.request.validation" : "error.request.body.invalid"));
        }
        verifyNoInteractions(artworkMetadataRecoveryService);
    }

    @Test
    void partialDownloadExposesOnlyWrittenOriginalPageIndices(@org.junit.jupiter.api.io.TempDir java.nio.file.Path folder) throws Exception {
        java.nio.file.Files.write(folder.resolve("last.webp"), new byte[]{1});
        var media = new top.sywyar.pixivdownload.core.asset.ArtworkMediaManifest("jpg", List.of("webp"), false);
        when(pixivDatabase.partialArtwork(42L)).thenReturn(new top.sywyar.pixivdownload.core.artwork.download.PartialArtwork(
                42, folder.toString(), 3, "{artwork_id}_p{page}", 180, "original", "revision", List.of("first", "middle", "last"),
                java.util.Map.of(1, media, 2, media), java.util.Map.of()));
        mockMvc.perform(get("/api/library/artworks/42/partial")).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalPages").value(3)).andExpect(jsonPath("$.folder").value(folder.toString()))
                .andExpect(jsonPath("$.files['1']").doesNotExist()).andExpect(jsonPath("$.files['2'][0]").value("last.webp"));
        mockMvc.perform(get("/api/library/artworks/43/partial")).andExpect(status().isNotFound());
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
        verify(pixivDatabase).getFileNameTemplates(
                Set.of(PixivDatabase.DEFAULT_FILE_NAME_TEMPLATE_ID));
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
                .thenReturn(new StatisticsResponse(true, 100, 500, 30, 105, 509, "获取成功"));

        mockMvc.perform(get("/api/downloaded/statistics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.totalArtworks").value(100))
                .andExpect(jsonPath("$.totalImages").value(500))
                .andExpect(jsonPath("$.libraryArtworks").value(105))
                .andExpect(jsonPath("$.libraryImages").value(509))
                .andExpect(jsonPath("$.totalMoved").value(30));
    }

    // ========== POST /api/library/artworks/{artworkId}/move ==========

    @Test
    void retiredMoveIgnoresLegacyArguments() throws Exception {
        mockMvc.perform(post("/api/library/artworks/bad-id/move").contentType("text/plain").content("{").accept("image/png"))
                .andExpect(status().isGone()).andExpect(content().bytes(new byte[0]));
        verifyNoInteractions(pixivDatabase, downloadedArtworkService, downloadStatisticsService);
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

    @Test
    void zeroHistorySizeUsesOneBeforeQueryingAndCountingPages() throws Exception {
        when(downloadedArtworkService.getArtworkCount()).thenReturn(3L);
        mockMvc.perform(get("/api/downloaded/history/paged").param("size", "0"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.totalPages").value(3));
        verify(downloadedArtworkService).getSortTimeArtworkPaged(0, 1);
    }

    @Test
    void largeHistoryPagesKeepTheirOrderAcrossMetadataQueries() throws Exception {
        int size = 1200;
        var ids = LongStream.iterate(size, id -> id - 1).limit(size).boxed().toList();
        when(downloadedArtworkService.getArtworkCount()).thenReturn((long) size);
        when(downloadedArtworkService.getSortTimeArtworkPaged(0, size)).thenReturn(ids);
        when(downloadedArtworkService.getDownloadedRecord(anyLong())).thenAnswer(call -> {
            long id = call.getArgument(0);
            return new ArtworkRecord(id, "Title", "/work", 1, "png", id, false, null, null, null, false, null, null);
        });

        mockMvc.perform(get("/api/downloaded/history/paged").param("size", String.valueOf(size)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].artworkId", contains(ids.stream().map(Long::intValue).toArray(Integer[]::new))));
        verify(pixivDatabase, atLeast(2)).getArtworkTags(anyCollection());
    }

    @Test
    @DisplayName("访客分页只投影受限查询结果，保留顺序并跳过缺失记录")
    void shouldProjectGuestHistoryWithinItsQueryScope() throws Exception {
        GuestInviteSession session = new GuestInviteSession(1L, "test-invite", true, false, false,
                false, Set.of(12L), false, Set.of(42L), true, Set.of(), true, Set.of(),
                false, Set.of(3L), true);
        WorkRestriction restriction = new WorkRestriction(Set.of(0), false, List.of(12L),
                false, List.of(42L), false, List.of(3L), true);
        when(galleryRepository.findArtworkIds(argThat(query -> query.page() == 0
                && query.size() == 200 && "authorId".equals(query.sort())
                && "desc".equals(query.order()) && restriction.equals(query.restriction()))))
                .thenReturn(new GalleryRepository.QueryResult(List.of(9L, 10L, 7L), 230L));
        when(downloadedArtworkService.getDownloadedRecord(9L)).thenReturn(new ArtworkRecord(
                9L, "A", "/a", 1, "jpg", 200L, false, null, null, 0, false, 42L, null));
        when(downloadedArtworkService.getDownloadedRecord(10L)).thenReturn(null);
        when(downloadedArtworkService.getDownloadedRecord(7L)).thenReturn(new ArtworkRecord(
                7L, "B", "/b", 2, "png", 100L, false, null, null, 0, false, 42L, null));
        when(authorService.getAuthorNames(Set.of(42L))).thenReturn(Map.of(42L, "Author"));

        mockMvc.perform(get("/api/downloaded/history/paged")
                        .requestAttr(GuestInviteSession.REQUEST_ATTR, session)
                        .param("page", "-1").param("size", "500").param("sort", "author_id"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].artworkId", contains(9, 7)))
                .andExpect(jsonPath("$.content[*].authorName", contains("Author", "Author")))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(200))
                .andExpect(jsonPath("$.totalElements").value(230))
                .andExpect(jsonPath("$.totalPages").value(2));

        verify(downloadedArtworkService, never()).getArtworkCount();
        verify(downloadedArtworkService, never()).getSortAuthorArtworkPaged(anyInt(), anyInt());
        verify(downloadedArtworkService, never()).getSortTimeArtworkPaged(anyInt(), anyInt());
    }
}

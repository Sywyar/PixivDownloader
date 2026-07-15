package top.sywyar.pixivdownload.douyin.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import top.sywyar.pixivdownload.core.appconfig.MultiModeConfig;
import top.sywyar.pixivdownload.core.web.AcquisitionCredentialResolver;
import top.sywyar.pixivdownload.douyin.client.DouyinClientErrorCode;
import top.sywyar.pixivdownload.douyin.client.DouyinClientException;
import top.sywyar.pixivdownload.douyin.download.DouyinDownloadService;
import top.sywyar.pixivdownload.douyin.model.DouyinAccountSource;
import top.sywyar.pixivdownload.douyin.model.DouyinCollectionListing;
import top.sywyar.pixivdownload.douyin.model.DouyinCollectionSummary;
import top.sywyar.pixivdownload.douyin.model.DouyinDownloadRequest;
import top.sywyar.pixivdownload.douyin.model.DouyinListing;
import top.sywyar.pixivdownload.douyin.model.DouyinStartResponse;
import top.sywyar.pixivdownload.douyin.model.DouyinWork;
import top.sywyar.pixivdownload.douyin.model.favorite.DouyinFavoriteFolderListing;
import top.sywyar.pixivdownload.douyin.model.favorite.DouyinFavoriteFolderSummary;
import top.sywyar.pixivdownload.setup.SetupService;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@DisplayName("Douyin 控制器凭证传输边界")
class DouyinControllerSecurityTest {

    private DouyinDownloadService service;
    private SetupService setupService;
    private MultiModeConfig multiModeConfig;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(DouyinDownloadService.class);
        setupService = mock(SetupService.class);
        multiModeConfig = new MultiModeConfig();
        DouyinController controller = new DouyinController(service, setupService, multiModeConfig);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new IllegalArgumentAdvice())
                .build();
    }

    @Test
    @DisplayName("非 loopback 客户端通过 HTTP 提交 Cookie 时拒绝请求")
    void rejectsCredentialSubmissionOverRemoteHttp() {
        DouyinDownloadService directService = mock(DouyinDownloadService.class);
        DouyinController controller = new DouyinController(
                directService, mock(SetupService.class), new MultiModeConfig());
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.isSecure()).thenReturn(false);
        when(request.getRemoteAddr()).thenReturn("203.0.113.10");

        var response = controller.download(
                new DouyinDownloadRequest("https://www.douyin.com/video/1", "title", "fixture-credential-7f4c2a91"),
                request);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verifyNoInteractions(directService);
    }

    @Test
    @DisplayName("通用取得凭证应传给 Douyin series 服务")
    void forwardsGenericCredentialForSeriesPreview() throws Exception {
        when(service.listSeriesWorks("series-1", 1, 24, "generic-cookie"))
                .thenReturn(DouyinListing.empty(1, 24));

        int status = mockMvc.perform(get("/api/douyin/series/series-1")
                        .header(AcquisitionCredentialResolver.HEADER_NAME, " generic-cookie "))
                .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(200);
        verify(service).listSeriesWorks("series-1", 1, 24, "generic-cookie");
    }

    @Test
    @DisplayName("旧 Douyin 凭证头仍应传给 series 服务")
    void forwardsLegacyCredentialForSeriesPreview() throws Exception {
        when(service.listSeriesWorks("series-1", 1, 24, "legacy-cookie"))
                .thenReturn(DouyinListing.empty(1, 24));

        int status = mockMvc.perform(get("/api/douyin/series/series-1")
                        .header("X-Douyin-Cookie", " legacy-cookie "))
                .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(200);
        verify(service).listSeriesWorks("series-1", 1, 24, "legacy-cookie");
    }

    @Test
    @DisplayName("通用与旧 Douyin 凭证冲突时应返回 400")
    void rejectsConflictingSeriesCredentials() throws Exception {
        int status = mockMvc.perform(get("/api/douyin/series/series-1")
                        .header(AcquisitionCredentialResolver.HEADER_NAME, "generic-cookie")
                        .header("X-Douyin-Cookie", "legacy-cookie"))
                .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(400);
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("单页预览入口拒绝越界页码与页大小")
    void rejectsUnboundedSinglePagePreviews() throws Exception {
        for (String endpoint : List.of(
                "/api/douyin/series/series-1",
                "/api/douyin/search?word=cat",
                "/api/douyin/music/music-1")) {
            String separator = endpoint.contains("?") ? "&" : "?";
            assertThat(mockMvc.perform(get(endpoint + separator + "page=0"))
                    .andReturn().getResponse().getStatus()).isEqualTo(400);
            assertThat(mockMvc.perform(get(endpoint + separator + "page="
                            + (DouyinController.MAX_PREVIEW_PAGE + 1)))
                    .andReturn().getResponse().getStatus()).isEqualTo(400);
            assertThat(mockMvc.perform(get(endpoint + separator + "pageSize="
                            + (DouyinController.MAX_PREVIEW_PAGE_SIZE + 1)))
                    .andReturn().getResponse().getStatus()).isEqualTo(400);
        }

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("下载入口从通用取得凭证头解析 Cookie 且请求体保持无凭证")
    void forwardsGenericCredentialForDownload() throws Exception {
        when(service.start(any(), anyString()))
                .thenReturn(new DouyinStartResponse(true, "status-1", "work-1", "douyin.status.queued"));

        int status = mockMvc.perform(post("/api/douyin/download")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(AcquisitionCredentialResolver.HEADER_NAME, " generic-cookie ")
                        .content("{\"input\":\"7351234567890123456\",\"title\":\"title\",\"cookie\":null}"))
                .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(202);
        verify(service).start(argThat(request -> "generic-cookie".equals(request.cookie())), anyString());
    }

    @Test
    @DisplayName("下载入口通用凭证头与旧请求体凭证冲突时返回 400")
    void rejectsConflictingDownloadCredentials() throws Exception {
        int status = mockMvc.perform(post("/api/douyin/download")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(AcquisitionCredentialResolver.HEADER_NAME, "generic-cookie")
                        .content("{\"input\":\"7351234567890123456\",\"cookie\":\"legacy-cookie\"}"))
                .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(400);
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("搜索范围在极大结束页下按绝对上限终止且不发生整数溢出")
    void boundsMaximumSearchRangeWithoutOverflow() throws Exception {
        DouyinWork work = work("work-1");
        when(service.searchPublic(anyString(), anyInt(), anyInt(), any()))
                .thenAnswer(invocation -> new DouyinListing(
                        List.of(work), 1, invocation.getArgument(1), 24, false,
                        null, null, null, "next", true));

        var response = mockMvc.perform(get("/api/douyin/search/range")
                        .param("word", "cat")
                        .param("startPage", "1")
                        .param("endPage", Integer.toString(Integer.MAX_VALUE)))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString())
                .contains("\"requestedPages\":2147483647", "\"acceptedPages\":50",
                        "\"fetchedPages\":50", "\"limitPage\":50");
        verify(service, times(DouyinController.ABSOLUTE_MAX_SEARCH_RANGE_PAGES))
                .searchPublic(anyString(), anyInt(), anyInt(), any());
    }

    @Test
    @DisplayName("搜索范围拒绝越界页大小")
    void rejectsUnboundedSearchRangePageSize() throws Exception {
        int status = mockMvc.perform(get("/api/douyin/search/range")
                        .param("word", "cat")
                        .param("pageSize", Integer.toString(DouyinController.MAX_PREVIEW_PAGE_SIZE + 1)))
                .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(400);
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("多人模式非管理员搜索范围遵循配置页数限制")
    void appliesConfiguredMultiModeSearchRangeLimit() throws Exception {
        when(setupService.getMode()).thenReturn("multi");
        when(setupService.isAdminLoggedIn(any())).thenReturn(false);
        multiModeConfig.setLimitPage(3);
        DouyinWork work = work("work-2");
        when(service.searchPublic(anyString(), anyInt(), anyInt(), any()))
                .thenAnswer(invocation -> new DouyinListing(
                        List.of(work), 1, invocation.getArgument(1), 24, false,
                        null, null, null, "next", true));

        var response = mockMvc.perform(get("/api/douyin/search/range")
                        .param("word", "cat").param("startPage", "2").param("endPage", "10"))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString())
                .contains("\"requestedPages\":9", "\"acceptedPages\":3",
                        "\"fetchedPages\":3", "\"limitPage\":3");
        verify(service, times(3)).searchPublic(anyString(), anyInt(), anyInt(), any());
    }

    @Test
    @DisplayName("用户作品预览按有界 offset 与 limit 返回页内卡片")
    void pagesUserPreviewWithoutFullIdScanOrCardLookup() throws Exception {
        when(service.listUserWorks("sec-user", 24, 2, null))
                .thenReturn(new DouyinListing(List.of(work("work-24"), work("work-25")),
                        27, 2, 2, false, "user", "sec-user", "作者", "cursor-2", true));

        var response = mockMvc.perform(get("/api/douyin/user/sec-user/works/ids")
                        .param("offset", "24").param("limit", "2"))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).contains(
                "\"ids\":[\"work-24\",\"work-25\"]",
                "\"items\":[", "\"total\":27", "\"offset\":24", "\"limit\":2",
                "\"nextCursor\":\"cursor-2\"", "\"hasMore\":true");
        verify(service).listUserWorks("sec-user", 24, 2, null);
        verify(service, never()).listAllUserWorkIds(anyString(), any());
        verify(service, never()).workCards(any(), any());
    }

    @Test
    @DisplayName("用户作品预览携带游标时直接取得真实游标页")
    void pagesUserPreviewWithOpaqueCursor() throws Exception {
        when(service.listUserWorksPage("sec-user", "opaque-2", 24, null))
                .thenReturn(new DouyinListing(List.of(work("work-2")),
                        49, 1, 24, false, "user", "sec-user", "作者", "opaque-3", true));

        var response = mockMvc.perform(get("/api/douyin/user/sec-user/works/ids")
                        .param("offset", "24").param("limit", "24").param("cursor", "opaque-2"))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString())
                .contains("\"ids\":[\"work-2\"]", "\"nextCursor\":\"opaque-3\"");
        verify(service).listUserWorksPage("sec-user", "opaque-2", 24, null);
        verify(service, never()).listUserWorks(anyString(), anyInt(), anyInt(), any());
    }

    @Test
    @DisplayName("用户作品预览拒绝越界 offset 与 limit")
    void rejectsUnboundedUserPreviewWindow() throws Exception {
        var invalidOffset = mockMvc.perform(get("/api/douyin/user/sec-user/works/ids")
                        .param("offset", "-1").param("limit", "24"))
                .andReturn().getResponse();
        assertThat(invalidOffset.getStatus()).isEqualTo(400);
        assertThat(invalidOffset.getContentAsString()).contains(
                "\"code\":\"UNSUPPORTED_CONTENT\"",
                "\"messageKey\":\"douyin.error.unsupported-content\"");
        assertThat(mockMvc.perform(get("/api/douyin/user/sec-user/works/ids")
                        .param("offset", Integer.toString(DouyinController.MAX_USER_PREVIEW_OFFSET + 1))
                        .param("limit", "24"))
                .andReturn().getResponse().getStatus()).isEqualTo(400);
        assertThat(mockMvc.perform(get("/api/douyin/user/sec-user/works/ids")
                        .param("offset", "0").param("limit", "0"))
                .andReturn().getResponse().getStatus()).isEqualTo(400);
        assertThat(mockMvc.perform(get("/api/douyin/user/sec-user/works/ids")
                        .param("offset", "0")
                        .param("limit", Integer.toString(DouyinController.MAX_PREVIEW_PAGE_SIZE + 1)))
                .andReturn().getResponse().getStatus()).isEqualTo(400);
        for (String name : List.of("offset", "limit")) {
            var malformed = mockMvc.perform(get("/api/douyin/user/sec-user/works/ids")
                            .param("offset", "offset".equals(name) ? "not-a-number" : "0")
                            .param("limit", "limit".equals(name) ? "not-a-number" : "24"))
                    .andReturn().getResponse();
            assertThat(malformed.getStatus()).isEqualTo(400);
            assertThat(malformed.getContentAsString()).contains(
                    "\"code\":\"UNSUPPORTED_CONTENT\"",
                    "\"messageKey\":\"douyin.error.unsupported-content\"");
        }

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("非法预览参数直接调用控制器也返回稳定的 400 错误结构")
    void returnsStructuredBadRequestFromDirectControllerCall() {
        DouyinController controller = new DouyinController(service, setupService, multiModeConfig);

        ResponseEntity<?> response = controller.userIds("sec-user", -1, 24, null, null, null);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isInstanceOfSatisfying(DouyinController.ErrorView.class, error -> {
            assertThat(error.code()).isEqualTo("UNSUPPORTED_CONTENT");
            assertThat(error.messageKey()).isEqualTo("douyin.error.unsupported-content");
        });
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("卡片兼容端点拒绝过多 ID 防止逐项查询放大")
    void rejectsTooManyCardIds() {
        DouyinController controller = new DouyinController(service, setupService, multiModeConfig);
        List<String> ids = java.util.stream.IntStream.range(0, DouyinController.MAX_CARD_IDS + 1)
                .mapToObj(Integer::toString)
                .toList();

        ResponseEntity<?> response = controller.userCards("sec-user", ids, null, null);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isInstanceOfSatisfying(DouyinController.ErrorView.class, error -> {
            assertThat(error.code()).isEqualTo("UNSUPPORTED_CONTENT");
            assertThat(error.messageKey()).isEqualTo("douyin.error.unsupported-content");
        });
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("账号收藏合集将游标与页大小传给真实分页客户端")
    void pagesFavoriteCollectionsWithoutFullReplay() throws Exception {
        DouyinCollectionSummary collection = new DouyinCollectionSummary(
                "mix-2", "合集二", 3, "owner-1", "作者");
        when(service.listFavoriteCollections("cursor-1", 12, null))
                .thenReturn(new DouyinCollectionListing(List.of(collection), 25, "cursor-2", true));

        var response = mockMvc.perform(get("/api/douyin/me/favorite-collections")
                        .param("cursor", "cursor-1").param("pageSize", "12"))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).contains(
                "\"collections\":[{\"id\":\"mix-2\"", "\"total\":25",
                "\"nextCursor\":\"cursor-2\"", "\"hasMore\":true");
        verify(service).listFavoriteCollections("cursor-1", 12, null);
        verify(service, never()).listAllFavoriteCollections(any());
    }

    @Test
    @DisplayName("收藏作品上游游标由控制器透明传递并返回")
    void transparentlyPassesFavoriteWorksCursor() throws Exception {
        String current = "favorite-current";
        String next = "favorite-next";
        when(service.listAccountWorksPage(
                DouyinAccountSource.FAVORITE_WORKS, current, 12, null))
                .thenReturn(new DouyinListing(List.of(work("favorite-1")),
                        2, 1, 12, false, "收藏作品", "account", "账号", next, true));

        var response = mockMvc.perform(get("/api/douyin/me/favorite-works")
                        .param("cursor", current).param("pageSize", "12"))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).contains(
                "\"id\":\"favorite-1\"", "\"nextCursor\":\"" + next + "\"",
                "\"hasMore\":true");
        verify(service).listAccountWorksPage(
                DouyinAccountSource.FAVORITE_WORKS, current, 12, null);
    }

    @Test
    @DisplayName("收藏作品上游结构异常返回稳定的网关错误码与消息键")
    void returnsStableFavoriteWorksUpstreamShapeError() throws Exception {
        when(service.listAccountWorksPage(
                DouyinAccountSource.FAVORITE_WORKS, "0", 24, null))
                .thenThrow(new DouyinClientException(
                        DouyinClientErrorCode.RESPONSE_STRUCTURE_UNRECOGNIZED,
                        "safe diagnostic"));

        var response = mockMvc.perform(get("/api/douyin/me/favorite-works"))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(502);
        assertThat(response.getContentAsString()).contains(
                "\"code\":\"RESPONSE_STRUCTURE_UNRECOGNIZED\"",
                "\"messageKey\":\"douyin.error.response-structure-unrecognized\"");
    }

    @Test
    @DisplayName("账号收藏合集拒绝过大页与过长游标")
    void rejectsUnboundedFavoriteCollectionPage() throws Exception {
        assertThat(mockMvc.perform(get("/api/douyin/me/favorite-collections")
                        .param("pageSize", Integer.toString(DouyinController.MAX_PREVIEW_PAGE_SIZE + 1)))
                .andReturn().getResponse().getStatus()).isEqualTo(400);
        var invalidCursor = mockMvc.perform(get("/api/douyin/me/favorite-collections")
                        .param("cursor", "x".repeat(DouyinController.MAX_CURSOR_LENGTH + 1)))
                .andReturn().getResponse();
        assertThat(invalidCursor.getStatus()).isEqualTo(400);
        assertThat(invalidCursor.getContentAsString()).contains(
                "\"code\":\"UNSUPPORTED_CONTENT\"",
                "\"messageKey\":\"douyin.error.unsupported-content\"");

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("收藏合集作品只返回真实游标页且不重放全量作品")
    void pagesFavoriteCollectionWorksWithoutFullReplay() throws Exception {
        when(service.listSeriesWorksPage("mix-1", "work-cursor-1", 12, null))
                .thenReturn(new DouyinListing(List.of(work("work-2")),
                        31, 1, 12, false, "合集", "mix-1", "作者", "work-cursor-2", true));

        var response = mockMvc.perform(get("/api/douyin/me/favorite-collections/mix-1/works")
                        .param("cursor", "work-cursor-1").param("pageSize", "12"))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).contains(
                "\"works\":[{\"id\":\"work-2\"", "\"total\":31",
                "\"nextCursor\":\"work-cursor-2\"", "\"hasMore\":true");
        verify(service).listSeriesWorksPage("mix-1", "work-cursor-1", 12, null);
        verify(service, never()).listAllSeriesWorks(anyString(), any());
    }

    @Test
    @DisplayName("自建收藏夹列表透明传递通用凭证与真实游标")
    void pagesFavoriteFoldersWithGenericCredential() throws Exception {
        when(service.listFavoriteFolders("folder-current", 12, "generic-cookie"))
                .thenReturn(new DouyinFavoriteFolderListing(
                        List.of(new DouyinFavoriteFolderSummary("folder-a", "收藏夹 A")),
                        4, "folder-next", true));

        var response = mockMvc.perform(get("/api/douyin/me/favorite-folders")
                        .param("cursor", "folder-current")
                        .param("pageSize", "12")
                        .header(AcquisitionCredentialResolver.HEADER_NAME, " generic-cookie "))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).contains(
                "\"folders\":[{\"id\":\"folder-a\",\"title\":\"收藏夹 A\"}]",
                "\"total\":4", "\"nextCursor\":\"folder-next\"", "\"hasMore\":true");
        verify(service).listFavoriteFolders("folder-current", 12, "generic-cookie");
    }

    @Test
    @DisplayName("自建收藏夹作品透明传递路径 ID、通用凭证与真实游标")
    void pagesFavoriteFolderWorksWithGenericCredential() throws Exception {
        when(service.listFavoriteFolderWorksPage(
                "folder-a", "works-current", 12, "generic-cookie"))
                .thenReturn(new DouyinListing(List.of(work("folder-work")),
                        7, 1, 12, false, "收藏夹 A", "folder-a", null, "works-next", true));

        var response = mockMvc.perform(get("/api/douyin/me/favorite-folders/folder-a/works")
                        .param("cursor", "works-current")
                        .param("pageSize", "12")
                        .header(AcquisitionCredentialResolver.HEADER_NAME, "generic-cookie"))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).contains(
                "\"folderId\":\"folder-a\"", "\"works\":[{\"id\":\"folder-work\"",
                "\"total\":7", "\"nextCursor\":\"works-next\"", "\"hasMore\":true");
        verify(service).listFavoriteFolderWorksPage(
                "folder-a", "works-current", 12, "generic-cookie");
    }

    @Test
    @DisplayName("非 loopback HTTP 请求不得向自建收藏夹端点提交凭证")
    void rejectsFavoriteFolderCredentialOverRemoteHttp() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.isSecure()).thenReturn(false);
        when(request.getRemoteAddr()).thenReturn("203.0.113.10");
        DouyinController controller = new DouyinController(service, setupService, multiModeConfig);

        ResponseEntity<?> response = controller.favoriteFolders(
                "0", 24, "sessionid=test", request);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("收藏合集作品拒绝过大页与过长游标")
    void rejectsUnboundedFavoriteCollectionWorksPage() throws Exception {
        assertThat(mockMvc.perform(get("/api/douyin/me/favorite-collections/mix-1/works")
                        .param("pageSize", Integer.toString(DouyinController.MAX_PREVIEW_PAGE_SIZE + 1)))
                .andReturn().getResponse().getStatus()).isEqualTo(400);
        assertThat(mockMvc.perform(get("/api/douyin/me/favorite-collections/mix-1/works")
                        .param("cursor", "x".repeat(DouyinController.MAX_CURSOR_LENGTH + 1)))
                .andReturn().getResponse().getStatus()).isEqualTo(400);

        verifyNoInteractions(service);
    }

    private static DouyinWork work(String id) {
        return new DouyinWork(id, "title", null, null,
                "https://www.douyin.com/video/" + id, null,
                URI.create("https://v3.douyinvod.com/" + id + ".mp4"));
    }

    @RestControllerAdvice
    static class IllegalArgumentAdvice {

        @ExceptionHandler(IllegalArgumentException.class)
        ResponseEntity<Void> handleIllegalArgument() {
            return ResponseEntity.badRequest().build();
        }
    }
}

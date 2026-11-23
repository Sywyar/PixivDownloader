package top.sywyar.pixivdownload.download.controller;

import top.sywyar.pixivdownload.core.web.HttpParameterException;
import top.sywyar.pixivdownload.core.web.HttpParameterError;
import top.sywyar.pixivdownload.core.web.HttpIds;
import top.sywyar.pixivdownload.core.pixiv.PixivQueryParameters;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.Parameter;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.SchemaProperty;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import top.sywyar.pixivdownload.core.web.KnownErrorCodes;
import top.sywyar.pixivdownload.core.pixiv.PixivAjaxException;
import top.sywyar.pixivdownload.core.pixiv.PixivCookieUserResolver;
import top.sywyar.pixivdownload.core.pixiv.PixivProxyAccessDecision;
import top.sywyar.pixivdownload.core.pixiv.PixivProxyAccessPolicy;
import top.sywyar.pixivdownload.core.web.AcquisitionCredentialResolver;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.core.work.model.WorkVisibilityScope;
import top.sywyar.pixivdownload.core.work.service.WorkVisibilityService;
import top.sywyar.pixivdownload.download.PixivFetchService;
import top.sywyar.pixivdownload.download.response.artwork.ArtworkMetaResponse;
import top.sywyar.pixivdownload.download.response.artwork.ArtworkPagesResponse;
import top.sywyar.pixivdownload.download.response.artwork.SeriesResponse;
import top.sywyar.pixivdownload.download.response.artwork.UgoiraMetaResponse;
import top.sywyar.pixivdownload.download.response.collection.CollectionPageResponse;
import top.sywyar.pixivdownload.download.response.collection.CollectionWorksResponse;
import top.sywyar.pixivdownload.download.response.error.ErrorResponse;
import top.sywyar.pixivdownload.download.response.error.ProxyRateLimitResponse;
import top.sywyar.pixivdownload.download.response.search.SearchRangeResponse;
import top.sywyar.pixivdownload.download.response.search.SearchResponse;
import top.sywyar.pixivdownload.download.response.user.FollowLatestResponse;
import top.sywyar.pixivdownload.download.response.user.FollowingPageResponse;
import top.sywyar.pixivdownload.download.response.user.UserArtworksResponse;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;
import top.sywyar.pixivdownload.plugin.api.web.HttpApiGroup;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentityResolver;

import java.io.IOException;
import java.net.URI;
import java.util.*;

/**
 * 代理 Pixiv AJAX API，供 pixiv-batch.html 使用。
 * 前端通过中性取得凭证头传入 Cookie。
 */
@ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
@ApiResponse(responseCode = "401", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
@ApiResponse(responseCode = "429", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ProxyRateLimitResponse.class)))
@ApiResponse(responseCode = "500", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
@ApiResponse(responseCode = "502", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
@ApiResponse(responseCode = "503", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
@HttpApiGroup("download-workbench-pixiv-art")
@RestController
@RequestMapping("/api/pixiv")
@Slf4j
@RequiredArgsConstructor
public class PixivProxyController {

    private final ObjectMapper objectMapper;
    private final PixivFetchService pixivFetchService;
    private final PixivProxyAccessPolicy pixivProxyAccessPolicy;
    private final RequestOwnerIdentityResolver requestOwnerIdentityResolver;
    private final WorkVisibilityService workVisibilityService;
    private final MessageResolver messages;

    /** 插件控制器自行投影预期的参数/安全拒绝，不依赖宿主全局异常处理器。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> invalidParameter() {
        return ResponseEntity.badRequest().body(ApiErrorResponse.of("error.request.param.invalid",
                messages.get("error.request.param.invalid")));
    }

    @ExceptionHandler(HttpParameterException.class)
    public ResponseEntity<HttpParameterError> invalidInput(HttpParameterException failure) {
        var error = new HttpParameterError("error.request.param.invalid", messages.get("error.request.field.invalid", failure.field()),
                failure.in(), failure.field(), failure.reason());
        log.warn("HTTP input rejected: code={} in={} reason={}", error.code(), error.in(), error.reason());
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON).body(error);
    }

    @ExceptionHandler({IllegalArgumentException.class, SecurityException.class})
    public ResponseEntity<ErrorResponse> handleBadRequest(RuntimeException failure) {
        String detail = failure.getMessage();
        if (detail == null || detail.isBlank()) {
            detail = messages.get("error.request.param.invalid");
        }
        log.warn(messages.getForLog("workbench.log.request.failed", detail));
        return ResponseEntity.badRequest().body(new ErrorResponse(detail));
    }

    /**
     * 多人模式访问控制：判定与配额预留统一交给宿主代理访问策略端口，
     * 返回 null 表示校验通过；返回 ResponseEntity 表示应直接返回该错误。
     */
    private ResponseEntity<?> checkMultiModeAccess(HttpServletRequest request) {
        PixivProxyAccessDecision decision = pixivProxyAccessPolicy.evaluate(
                requestOwnerIdentityResolver.resolveExistingOwnerUuid(request).orElse(null),
                requestOwnerIdentityResolver.isAdminAuthenticated(request));
        return switch (decision.outcome()) {
            case ALLOWED -> null;
            case OWNER_REQUIRED -> ResponseEntity.status(401)
                    .body(new ErrorResponse(decision.errorMessage()));
            case RATE_LIMITED -> ResponseEntity.status(429)
                    .header("Retry-After", Long.toString(java.time.Duration.ofHours(decision.windowHours()).toSeconds()))
                    .body(new ProxyRateLimitResponse(
                            decision.errorMessage(), decision.maxRequests(), decision.windowHours()));
        };
    }

    /** 校验正整数标识并执行作品可见性检查，返回唯一可用于上游路径的十进制值。 */
    private long requireVisibleArtwork(WorkVisibilityScope visibilityScope, String artworkId) {
        if (artworkId != null && artworkId.length() > 19)
            throw new HttpParameterException("path", "artworkId", "invalid_value");
        long id = HttpIds.positive("path", "artworkId", artworkId);
        workVisibilityService.requireVisible(visibilityScope, WorkType.ARTWORK, id);
        return id;
    }

    private String proxyGet(String url, String cookie) {
        return pixivFetchService.proxyGet(url, cookie);
    }

    private String proxyGetUri(URI uri, String cookie) {
        return pixivFetchService.proxyGetUri(uri, cookie);
    }

    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json", schema = @Schema(implementation = UserArtworksResponse.class)))
    @GetMapping("/user/{userId}/artworks")
    public ResponseEntity<?> getUserArtworks(
            @PathVariable String userId,
            @RequestHeader(value = AcquisitionCredentialResolver.HEADER_NAME, required = false) String cookie,
            HttpServletRequest request) throws IOException {
        cookie = AcquisitionCredentialResolver.resolve(cookie);
        ResponseEntity<?> deny = checkMultiModeAccess(request);
        if (deny != null) return deny;
        String body = proxyGet(
                "https://www.pixiv.net/ajax/user/" + userId + "/profile/all", cookie);
        JsonNode root = objectMapper.readTree(body);
        if (root.path("error").asBoolean(false)) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse(root.path("message").asString()));
        }
        JsonNode b = root.path("body");
        List<String> ids = new ArrayList<>();
        b.path("illusts").propertyNames().iterator().forEachRemaining(ids::add);
        b.path("manga").propertyNames().iterator().forEachRemaining(ids::add);
        ids.sort((a, c2) -> Long.compare(Long.parseLong(c2), Long.parseLong(a)));
        return ResponseEntity.ok(new UserArtworksResponse(ids));
    }

    /**
     * 发现某画师已完成并公开的「约稿作品」（リクエスト 成品）ID 列表。成品本质是普通插画，
     * 前端预览/入队/下载复用 illust 链路（卡片走 {@code /user/{id}/illust-cards}）。
     */
    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json", schema = @Schema(implementation = UserArtworksResponse.class)))
    @GetMapping("/user/{userId}/request-artworks")
    public ResponseEntity<?> getUserRequestArtworks(
            @PathVariable String userId,
            @RequestHeader(value = AcquisitionCredentialResolver.HEADER_NAME, required = false) String cookie,
            HttpServletRequest request) throws IOException {
        cookie = AcquisitionCredentialResolver.resolve(cookie);
        ResponseEntity<?> deny = checkMultiModeAccess(request);
        if (deny != null) return deny;
        try {
            return ResponseEntity.ok(new UserArtworksResponse(
                    pixivFetchService.discoverUserRequestArtworkIds(userId, cookie)));
        } catch (PixivFetchService.PixivFetchException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ArtworkMetaResponse.class)))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = "WORK_NOT_FOUND"))))
    @KnownErrorCodes(status = "400", value = "error.request.param.invalid")
    @GetMapping("/artwork/{artworkId}/meta")
    public ResponseEntity<?> getArtworkMeta(
            @Parameter(schema = @Schema(type = "string", minLength = 1, maxLength = 19, pattern = "^0*[1-9][0-9]*$"))
            @PathVariable String artworkId,
            @RequestHeader(value = AcquisitionCredentialResolver.HEADER_NAME, required = false) String cookie,
            HttpServletRequest request,
            WorkVisibilityScope visibilityScope) throws IOException {
        cookie = AcquisitionCredentialResolver.resolve(cookie);
        long id = requireVisibleArtwork(visibilityScope, artworkId);
        ResponseEntity<?> deny = checkMultiModeAccess(request);
        if (deny != null) return deny;
        String body;
        try {
            body = proxyGet("https://www.pixiv.net/ajax/illust/" + id, cookie);
        } catch (PixivAjaxException failure) {
            if (failure.statusCode() != 404) throw failure;
            return ResponseEntity.status(404).body(ApiErrorResponse.of("WORK_NOT_FOUND", messages.get("error.work.not-found")));
        }
        JsonNode root = objectMapper.readTree(body);
        if (root.path("error").asBoolean(false)) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse(root.path("message").asString()));
        }
        return ResponseEntity.ok(PixivProxyResponseMapper.artworkMeta(objectMapper, root.path("body")));
    }

    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ArtworkPagesResponse.class)))
    @KnownErrorCodes(status = "400", value = "error.request.param.invalid")
    @GetMapping("/artwork/{artworkId}/pages")
    public ResponseEntity<?> getArtworkPages(
            @Parameter(schema = @Schema(type = "string", minLength = 1, maxLength = 19, pattern = "^0*[1-9][0-9]*$"))
            @PathVariable String artworkId,
            @RequestHeader(value = AcquisitionCredentialResolver.HEADER_NAME, required = false) String cookie,
            HttpServletRequest request,
            WorkVisibilityScope visibilityScope) throws IOException {
        cookie = AcquisitionCredentialResolver.resolve(cookie);
        long id = requireVisibleArtwork(visibilityScope, artworkId);
        ResponseEntity<?> deny = checkMultiModeAccess(request);
        if (deny != null) return deny;
        String body = proxyGet(
                "https://www.pixiv.net/ajax/illust/" + id + "/pages", cookie);
        JsonNode root = objectMapper.readTree(body);
        if (root.path("error").asBoolean(false)) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse(root.path("message").asString()));
        }
        return ResponseEntity.ok(PixivProxyResponseMapper.artworkPages(root.path("body")));
    }

    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json", schema = @Schema(implementation = UgoiraMetaResponse.class)))
    @KnownErrorCodes(status = "400", value = "error.request.param.invalid")
    @GetMapping("/artwork/{artworkId}/ugoira")
    public ResponseEntity<?> getUgoiraMeta(
            @Parameter(schema = @Schema(type = "string", minLength = 1, maxLength = 19, pattern = "^0*[1-9][0-9]*$"))
            @PathVariable String artworkId,
            @RequestHeader(value = AcquisitionCredentialResolver.HEADER_NAME, required = false) String cookie,
            HttpServletRequest request,
            WorkVisibilityScope visibilityScope) throws IOException {
        cookie = AcquisitionCredentialResolver.resolve(cookie);
        long id = requireVisibleArtwork(visibilityScope, artworkId);
        ResponseEntity<?> deny = checkMultiModeAccess(request);
        if (deny != null) return deny;
        String body = proxyGet(
                "https://www.pixiv.net/ajax/illust/" + id + "/ugoira_meta", cookie);
        JsonNode root;
        try {
            root = body == null ? null : objectMapper.readTree(body);
        } catch (tools.jackson.core.JacksonException invalid) {
            log.warn("Pixiv returned invalid ugoira metadata for artwork {}", id);
            root = null;
        }
        if (root != null && root.path("error").asBoolean(false)) {
            return ResponseEntity.badRequest().body(new ErrorResponse(root.path("message").asString()));
        }
        if (root == null || !root.path("body").path("frames").isArray()
                || (root.path("body").path("originalSrc").asString("").isBlank()
                    && root.path("body").path("src").asString("").isBlank())) {
            return ResponseEntity.status(502).body(ApiErrorResponse.of("pixiv.proxy.invalid-response",
                    messages.get("pixiv.proxy.invalid-response")));
        }
        return ResponseEntity.ok(PixivProxyResponseMapper.ugoiraMeta(root.path("body")));
    }

    private int resolveSearchFillLimitPage(HttpServletRequest request) {
        return pixivProxyAccessPolicy.resolveSearchFillLimitPage(
                requestOwnerIdentityResolver.isAdminAuthenticated(request));
    }

    private SearchResponse fetchSearchPage(
            String word,
            String order,
            String mode,
            String sMode,
            int page,
            String cookie) throws IOException {
        int safePage = Math.max(page, 1);
        URI searchUri = UriComponentsBuilder
                .fromUriString("https://www.pixiv.net/ajax/search/artworks/{word}")
                .queryParam("word", "{word}")
                .queryParam("order", order)
                .queryParam("mode", mode)
                .queryParam("type", "illust_and_ugoira")
                .queryParam("s_mode", sMode)
                .queryParam("p", safePage)
                .queryParam("lang", "zh")
                .buildAndExpand(Map.of("word", word))
                .encode()
                .toUri();
        String body = proxyGetUri(searchUri, cookie);
        JsonNode root = objectMapper.readTree(body);
        if (root.path("error").asBoolean(false)) {
            throw new IllegalArgumentException(root.path("message").asString(messages.get("pixiv.proxy.search.failed")));
        }
        JsonNode illustManga = root.path("body").path("illustManga");
        return PixivProxyResponseMapper.searchResponse(illustManga, safePage);
    }

    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json",
            schema = @Schema(implementation = SearchResponse.class), examples = @ExampleObject(value = "{\"items\":[],\"total\":0,\"page\":1}")))
    @GetMapping("/search")
    public ResponseEntity<?> searchArtworks(
            @RequestParam String word,
            @Parameter(schema = @Schema(implementation = PixivQueryParameters.Order.class))
            @RequestParam(defaultValue = "date_d") String order,
            @Parameter(schema = @Schema(implementation = PixivQueryParameters.Mode.class))
            @RequestParam(defaultValue = "all") String mode,
            @Parameter(schema = @Schema(implementation = PixivQueryParameters.SearchMode.class))
            @RequestParam(defaultValue = "s_tag") String sMode,
            @Parameter(example = "1", schema = @Schema(type = "integer", format = "int32", defaultValue = "1"))
            @RequestParam(defaultValue = "1") int page,
            @RequestHeader(value = AcquisitionCredentialResolver.HEADER_NAME, required = false) String cookie,
            HttpServletRequest request) throws IOException {
        cookie = AcquisitionCredentialResolver.resolve(cookie);
        ResponseEntity<?> deny = checkMultiModeAccess(request);
        if (deny != null) return deny;
        var validationError = PixivQueryParameters.search(messages, word, order, mode, sMode);
        if (validationError != null) {
            return ResponseEntity.badRequest().body(validationError);
        }
        try {
            return ResponseEntity.ok(fetchSearchPage(word, order, mode, sMode, page, cookie));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @FunctionalInterface
    private interface RangePageFetcher {
        SearchResponse fetch(int page) throws IOException;
    }

    /**
     * 按页码范围 [startParam, endParam] 抓取并跨页去重，受 multi-mode.limit-page 约束。
     * perPage 仅用于估算总页数以便提前停止抓取。
     */
    private SearchRangeResponse buildSearchRange(
            int startParam, int endParam, int perPage, int limitPage, RangePageFetcher fetcher) throws IOException {
        int startPage = Math.max(1, Math.min(startParam, endParam));
        int endRequested = Math.max(startPage, Math.max(startParam, endParam));
        int requestedPages = endRequested - startPage + 1;
        int acceptedPages = limitPage > 0 ? Math.min(requestedPages, limitPage) : requestedPages;
        int cappedEnd = startPage + acceptedPages - 1;

        LinkedHashMap<String, SearchResponse.SearchItem> deduped = new LinkedHashMap<>();
        int total = 0;
        int totalPages = Integer.MAX_VALUE;
        int fetchedPages = 0;
        int endPage = startPage;

        for (int p = startPage; p <= cappedEnd; p++) {
            if (p > totalPages) break;
            SearchResponse pageResponse = fetcher.fetch(p);
            total = pageResponse.getTotal();
            totalPages = Math.max(1, (int) Math.ceil(total / (double) perPage));
            if (p > totalPages) break;
            for (var item : pageResponse.getItems()) {
                deduped.putIfAbsent(item.id(), item);
            }
            fetchedPages++;
            endPage = p;
            if (p >= totalPages) break;
        }

        return new SearchRangeResponse(
                new ArrayList<>(deduped.values()),
                total,
                startPage,
                endPage,
                requestedPages,
                acceptedPages,
                fetchedPages,
                limitPage
        );
    }

    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json", schema = @Schema(implementation = SearchRangeResponse.class)))
    @GetMapping("/search/range")
    public ResponseEntity<?> rangeSearchArtworks(
            @RequestParam String word,
            @Parameter(schema = @Schema(implementation = PixivQueryParameters.Order.class))
            @RequestParam(defaultValue = "date_d") String order,
            @Parameter(schema = @Schema(implementation = PixivQueryParameters.Mode.class))
            @RequestParam(defaultValue = "all") String mode,
            @Parameter(schema = @Schema(implementation = PixivQueryParameters.SearchMode.class))
            @RequestParam(defaultValue = "s_tag") String sMode,
            @Parameter(schema = @Schema(minimum = "1"))
            @RequestParam(defaultValue = "1") int startPage,
            @Parameter(schema = @Schema(minimum = "1"))
            @RequestParam(defaultValue = "1") int endPage,
            @RequestHeader(value = AcquisitionCredentialResolver.HEADER_NAME, required = false) String cookie,
            HttpServletRequest request) throws IOException {
        String resolvedCredential = AcquisitionCredentialResolver.resolve(cookie);
        ResponseEntity<?> deny = checkMultiModeAccess(request);
        if (deny != null) return deny;
        var validationError = PixivQueryParameters.search(messages, word, order, mode, sMode);
        if (validationError != null) {
            return ResponseEntity.badRequest().body(validationError);
        }
        var rangeError = PixivQueryParameters.range(messages, startPage, endPage);
        if (rangeError != null) return ResponseEntity.badRequest().body(rangeError);
        try {
            int limitPage = resolveSearchFillLimitPage(request);
            return ResponseEntity.ok(buildSearchRange(startPage, endPage, 60, limitPage,
                    p -> fetchSearchPage(word, order, mode, sMode, p, resolvedCredential)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json", schema = @Schema(implementation = SeriesResponse.class)))
    @KnownErrorCodes(status = "400", value = "error.request.param.invalid")
    @GetMapping("/series/{seriesId}")
    public ResponseEntity<?> getSeries(
            @PathVariable String seriesId,
            @Parameter(example = "1", schema = @Schema(type = "integer", format = "int32", defaultValue = "1"))
            @RequestParam(defaultValue = "1") int page,
            @RequestHeader(value = AcquisitionCredentialResolver.HEADER_NAME, required = false) String cookie,
            HttpServletRequest request) throws IOException {
        cookie = AcquisitionCredentialResolver.resolve(cookie);
        ResponseEntity<?> deny = checkMultiModeAccess(request);
        if (deny != null) return deny;
        long parsedId = HttpIds.positive("path", "seriesId", seriesId);
        int safePage = Math.max(1, page);
        URI seriesUri = UriComponentsBuilder
                .fromUriString("https://www.pixiv.net/ajax/series/{seriesId}")
                .queryParam("p", safePage)
                .queryParam("lang", "zh")
                .buildAndExpand(Map.of("seriesId", parsedId))
                .encode()
                .toUri();
        String body = proxyGetUri(seriesUri, cookie);
        JsonNode root = objectMapper.readTree(body);
        if (root.path("error").asBoolean(false)) {
            return ResponseEntity.badRequest().body(new ErrorResponse(root.path("message").asString()));
        }
        return ResponseEntity.ok(PixivProxyResponseMapper.seriesResponse(root.path("body"), parsedId, safePage));
    }

    /**
     * 批量获取画师插画/漫画的卡片元数据（供 User 模式预览渲染与客户端附加筛选）。
     * 经 {@code /ajax/user/{id}/illusts?ids[]=...} 拉取，返回与搜索结果同形的 {@link SearchResponse}，
     * 并按请求传入的 ids 顺序保序（Pixiv 返回的是按 id 键控的对象，顺序不保证）。
     */
    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json", schema = @Schema(implementation = SearchResponse.class)))
    @GetMapping("/user/{userId}/illust-cards")
    public ResponseEntity<?> getUserIllustCards(
            @PathVariable String userId,
            @RequestParam List<String> ids,
            @RequestHeader(value = AcquisitionCredentialResolver.HEADER_NAME, required = false) String cookie,
            HttpServletRequest request) throws IOException {
        cookie = AcquisitionCredentialResolver.resolve(cookie);
        ResponseEntity<?> deny = checkMultiModeAccess(request);
        if (deny != null) return deny;
        if (ids == null || ids.isEmpty()) {
            return ResponseEntity.ok(new SearchResponse(List.of(), 0, 1));
        }
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromUriString("https://www.pixiv.net/ajax/user/{userId}/illusts");
        for (String id : ids) {
            builder.queryParam("ids[]", id);
        }
        URI uri = builder
                .queryParam("lang", "zh")
                .buildAndExpand(Map.of("userId", userId))
                .encode()
                .toUri();
        String body = proxyGetUri(uri, cookie);
        JsonNode root = objectMapper.readTree(body);
        if (root.path("error").asBoolean(false)) {
            return ResponseEntity.badRequest().body(new ErrorResponse(root.path("message").asString()));
        }
        List<SearchResponse.SearchItem> items = PixivProxyResponseMapper.parseUserIllustCards(root.path("body"), ids);
        return ResponseEntity.ok(new SearchResponse(items, items.size(), 1));
    }

    // ── /me 端点：基于 cookie 解析当前用户 uid，代理「我的」书签 / 关注 / 珍藏集 ─────────────

    /**
     * 从 Pixiv cookie 串里抽出登录用户的 userId。
     * <p>PHPSESSID 格式为 {@code {userId}_{随机后缀}}，下划线前缀即 userId。返回 null 表示
     * Cookie 缺失或无法取得 UID。此方法仅读取本地字段，不验证会话有效期。
     */
    static String extractUidFromCookie(String cookie) {
        return PixivCookieUserResolver.extractUidFromCookie(cookie);
    }

    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json", schema = @Schema(implementation = SearchResponse.class)))
    @KnownErrorCodes(status = "400", value = AcquisitionCredentialResolver.REQUIRED_CODE)
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/me/illust-bookmarks")
    public ResponseEntity<?> getMyIllustBookmarks(
            @Parameter(schema = @Schema(implementation = PixivQueryParameters.Rest.class))
            @RequestParam(defaultValue = "show") String rest,
            @RequestParam(required = false) String tag,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "48") int limit,
            @RequestHeader(value = AcquisitionCredentialResolver.HEADER_NAME, required = false) String cookie,
            HttpServletRequest request) throws IOException {
        cookie = AcquisitionCredentialResolver.resolve(cookie);
        ResponseEntity<?> deny = checkMultiModeAccess(request);
        if (deny != null) return deny;
        var restError = PixivQueryParameters.rest(messages, rest);
        if (restError != null) return ResponseEntity.badRequest().body(restError);
        int safeOffset = Math.max(0, offset);
        int safeLimit = Math.max(1, Math.min(100, limit));
        String uid = extractUidFromCookie(cookie);
        if (uid == null) {
            return ResponseEntity.badRequest().body(new ErrorResponse(AcquisitionCredentialResolver.REQUIRED_CODE,
                    messages.get("pixiv.proxy.me.cookie.missing")));
        }
        URI uri = UriComponentsBuilder
                .fromUriString("https://www.pixiv.net/ajax/user/{uid}/illusts/bookmarks")
                .queryParam("tag", tag == null ? "" : tag)
                .queryParam("offset", safeOffset)
                .queryParam("limit", safeLimit)
                .queryParam("rest", rest)
                .queryParam("lang", "zh")
                .buildAndExpand(Map.of("uid", uid))
                .encode()
                .toUri();
        String body = proxyGetUri(uri, cookie);
        JsonNode root = objectMapper.readTree(body);
        if (root.path("error").asBoolean(false)) {
            return ResponseEntity.badRequest().body(new ErrorResponse(root.path("message").asString()));
        }
        return ResponseEntity.ok(PixivProxyResponseMapper.bookmarkResponse(
                root.path("body"), safeOffset, safeLimit));
    }

    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json", schema = @Schema(implementation = FollowingPageResponse.class)))
    @KnownErrorCodes(status = "400", value = AcquisitionCredentialResolver.REQUIRED_CODE)
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/me/following")
    public ResponseEntity<?> getMyFollowing(
            @Parameter(schema = @Schema(implementation = PixivQueryParameters.Rest.class))
            @RequestParam(defaultValue = "show") String rest,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "24") int limit,
            @RequestHeader(value = AcquisitionCredentialResolver.HEADER_NAME, required = false) String cookie,
            HttpServletRequest request) throws IOException {
        cookie = AcquisitionCredentialResolver.resolve(cookie);
        ResponseEntity<?> deny = checkMultiModeAccess(request);
        if (deny != null) return deny;
        var restError = PixivQueryParameters.rest(messages, rest);
        if (restError != null) return ResponseEntity.badRequest().body(restError);
        int safeOffset = Math.max(0, offset);
        int safeLimit = Math.max(1, Math.min(100, limit));
        String uid = extractUidFromCookie(cookie);
        if (uid == null) {
            return ResponseEntity.badRequest().body(new ErrorResponse(AcquisitionCredentialResolver.REQUIRED_CODE,
                    messages.get("pixiv.proxy.me.cookie.missing")));
        }
        URI uri = UriComponentsBuilder
                .fromUriString("https://www.pixiv.net/ajax/user/{uid}/following")
                .queryParam("offset", safeOffset)
                .queryParam("limit", safeLimit)
                .queryParam("rest", rest)
                .queryParam("tag", "")
                .queryParam("acceptingRequests", 0)
                .queryParam("lang", "zh")
                .buildAndExpand(Map.of("uid", uid))
                .encode()
                .toUri();
        String body = proxyGetUri(uri, cookie);
        JsonNode root = objectMapper.readTree(body);
        if (root.path("error").asBoolean(false)) {
            return ResponseEntity.badRequest().body(new ErrorResponse(root.path("message").asString()));
        }
        return ResponseEntity.ok(PixivProxyResponseMapper.followingPageResponse(
                root.path("body"), safeOffset, safeLimit));
    }

    /**
     * 已关注的用户的新作（フォロー新着作品）。基于 cookie 主人的登录态，代理
     * {@code /ajax/follow_latest/illust?mode=all&p=N}：返回当前页的插画/漫画/动图卡片，按
     * {@code body.page.ids} 的顺序排列。Pixiv 该接口不给作品总数，故以 {@code hasNext} 表示是否还有下一页。
     */
    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json", schema = @Schema(implementation = FollowLatestResponse.class)))
    @KnownErrorCodes(status = "400", value = AcquisitionCredentialResolver.REQUIRED_CODE)
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/me/follow-latest")
    public ResponseEntity<?> getMyFollowLatest(
            @Parameter(example = "1", schema = @Schema(type = "integer", format = "int32", defaultValue = "1"))
            @RequestParam(defaultValue = "1") int p,
            @RequestHeader(value = AcquisitionCredentialResolver.HEADER_NAME, required = false) String cookie,
            HttpServletRequest request) throws IOException {
        cookie = AcquisitionCredentialResolver.resolve(cookie);
        ResponseEntity<?> deny = checkMultiModeAccess(request);
        if (deny != null) return deny;
        if (extractUidFromCookie(cookie) == null) {
            return ResponseEntity.badRequest().body(new ErrorResponse(AcquisitionCredentialResolver.REQUIRED_CODE,
                    messages.get("pixiv.proxy.me.cookie.missing")));
        }
        int safePage = Math.max(1, p);
        URI uri = UriComponentsBuilder
                .fromUriString("https://www.pixiv.net/ajax/follow_latest/illust")
                .queryParam("mode", "all")
                .queryParam("p", safePage)
                .queryParam("lang", "zh")
                .build()
                .encode()
                .toUri();
        String body = proxyGetUri(uri, cookie);
        JsonNode root = objectMapper.readTree(body);
        if (root.path("error").asBoolean(false)) {
            return ResponseEntity.badRequest().body(new ErrorResponse(root.path("message").asString()));
        }
        JsonNode b = root.path("body");
        List<SearchResponse.SearchItem> items = PixivProxyResponseMapper.parseFollowLatestIllusts(b);
        boolean hasNext = PixivProxyResponseMapper.followLatestHasNext(b, items.size());
        return ResponseEntity.ok(new FollowLatestResponse(items, safePage, hasNext));
    }

    /**
     * 当前用户的珍藏集（コレクション）列表。珍藏集不分公开/不公开、不分插画/小说。
     * 两步：先从 {@code profile/all} 取 {@code collectionIds}，再分批 {@code profile/collections?ids[]=}
     * 取封面元数据；Pixiv 无该列表的分页，一次性返回全部。
     */
    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CollectionPageResponse.class)))
    @KnownErrorCodes(status = "400", value = AcquisitionCredentialResolver.REQUIRED_CODE)
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/me/collections")
    public ResponseEntity<?> getMyCollections(
            @RequestHeader(value = AcquisitionCredentialResolver.HEADER_NAME, required = false) String cookie,
            HttpServletRequest request) throws IOException {
        cookie = AcquisitionCredentialResolver.resolve(cookie);
        ResponseEntity<?> deny = checkMultiModeAccess(request);
        if (deny != null) return deny;
        String uid = extractUidFromCookie(cookie);
        if (uid == null) {
            return ResponseEntity.badRequest().body(new ErrorResponse(AcquisitionCredentialResolver.REQUIRED_CODE,
                    messages.get("pixiv.proxy.me.cookie.missing")));
        }
        // 1) profile/all → collectionIds
        String allBody = proxyGet("https://www.pixiv.net/ajax/user/" + uid + "/profile/all?lang=zh", cookie);
        JsonNode allRoot = objectMapper.readTree(allBody);
        if (allRoot.path("error").asBoolean(false)) {
            return ResponseEntity.badRequest().body(new ErrorResponse(allRoot.path("message").asString()));
        }
        List<String> ids = PixivProxyResponseMapper.collectionIds(allRoot.path("body"));
        if (ids.isEmpty()) {
            return ResponseEntity.ok(new CollectionPageResponse(List.of(), 0));
        }
        // 2) 分批 profile/collections?ids[]=（每批 48 个，避免 URL 过长）
        List<CollectionPageResponse.CollectionItem> collections = new ArrayList<>();
        final int batch = 48;
        for (int i = 0; i < ids.size(); i += batch) {
            List<String> slice = ids.subList(i, Math.min(i + batch, ids.size()));
            UriComponentsBuilder builder = UriComponentsBuilder
                    .fromUriString("https://www.pixiv.net/ajax/user/{uid}/profile/collections");
            for (String id : slice) {
                builder.queryParam("ids[]", id);
            }
            URI uri = builder.queryParam("lang", "zh")
                    .buildAndExpand(Map.of("uid", uid))
                    .encode()
                    .toUri();
            String body = proxyGetUri(uri, cookie);
            JsonNode root = objectMapper.readTree(body);
            if (root.path("error").asBoolean(false)) {
                return ResponseEntity.badRequest().body(new ErrorResponse(root.path("message").asString()));
            }
            collections.addAll(PixivProxyResponseMapper.collectionItems(
                    root.path("body").path("works")));
        }
        return ResponseEntity.ok(new CollectionPageResponse(collections, collections.size()));
    }

    /**
     * 单个珍藏集内部的作品（插画 + 小说混合，按珍藏集布局顺序）。
     * 走 {@code /ajax/collection/{collectionId}}：{@code data.detail.tiles[]} 给出顺序与 workType/workId，
     * {@code thumbnails.illust[]} / {@code thumbnails.novel[]} 给出卡片详情。Pixiv 一次返回全部、无分页。
     * 珍藏集 ID 是 20 位以上的数字串，超出 long，故按字符串处理（仅校验为纯数字以防注入）。
     */
    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CollectionWorksResponse.class)))
    @KnownErrorCodes(status = "400", value = AcquisitionCredentialResolver.REQUIRED_CODE)
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @KnownErrorCodes(status = "400", value = "error.request.param.invalid")
    @GetMapping("/me/collection/{collectionId}/works")
    public ResponseEntity<?> getMyCollectionWorks(
            @PathVariable String collectionId,
            @RequestHeader(value = AcquisitionCredentialResolver.HEADER_NAME, required = false) String cookie,
            HttpServletRequest request) throws IOException {
        cookie = AcquisitionCredentialResolver.resolve(cookie);
        ResponseEntity<?> deny = checkMultiModeAccess(request);
        if (deny != null) return deny;
        if (extractUidFromCookie(cookie) == null) {
            return ResponseEntity.badRequest().body(new ErrorResponse(AcquisitionCredentialResolver.REQUIRED_CODE,
                    messages.get("pixiv.proxy.me.cookie.missing")));
        }
        if (collectionId == null || collectionId.isBlank() || !collectionId.chars().allMatch(Character::isDigit)) {
            return ResponseEntity.badRequest()
                    .body(new HttpParameterError("error.request.param.invalid", messages.get("pixiv.proxy.me.collection.id.invalid", collectionId),
                            "path", "collectionId", "invalid_value"));
        }
        URI uri = UriComponentsBuilder
                .fromUriString("https://www.pixiv.net/ajax/collection/{cid}")
                .queryParam("lang", "zh")
                .buildAndExpand(Map.of("cid", collectionId))
                .encode()
                .toUri();
        String body = proxyGetUri(uri, cookie);
        JsonNode root = objectMapper.readTree(body);
        if (root.path("error").asBoolean(false)) {
            return ResponseEntity.badRequest().body(new ErrorResponse(root.path("message").asString()));
        }
        List<CollectionWorksResponse.Work> works = PixivProxyResponseMapper.parseCollectionWorks(
                root.path("body"));
        return ResponseEntity.ok(new CollectionWorksResponse(works, works.size()));
    }
}

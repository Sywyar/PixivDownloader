package top.sywyar.pixivdownload.download.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.sywyar.pixivdownload.download.response.collection.CollectionPageResponse;
import top.sywyar.pixivdownload.download.response.collection.CollectionWorksResponse;
import top.sywyar.pixivdownload.download.response.error.ErrorResponse;
import top.sywyar.pixivdownload.download.response.error.ProxyRateLimitResponse;
import top.sywyar.pixivdownload.download.response.search.SearchRangeResponse;
import top.sywyar.pixivdownload.download.response.search.SearchResponse;
import top.sywyar.pixivdownload.download.response.user.FollowLatestResponse;
import top.sywyar.pixivdownload.download.response.user.MeUidResponse;
import top.sywyar.pixivdownload.download.response.user.UserArtworksResponse;
import top.sywyar.pixivdownload.download.response.user.UserMetaResponse;
import top.sywyar.pixivdownload.download.PixivFetchService;
import top.sywyar.pixivdownload.core.pixiv.PixivCookieUserResolver;
import top.sywyar.pixivdownload.core.pixiv.PixivProxyAccessDecision;
import top.sywyar.pixivdownload.core.pixiv.PixivProxyAccessPolicy;
import top.sywyar.pixivdownload.core.pixiv.thumbnail.PixivThumbnailFetchException;
import top.sywyar.pixivdownload.core.pixiv.thumbnail.PixivThumbnailFailure;
import top.sywyar.pixivdownload.core.pixiv.thumbnail.PixivThumbnailFetcher;
import top.sywyar.pixivdownload.core.web.AcquisitionCredentialResolver;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentityResolver;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.core.work.model.WorkVisibilityScope;
import top.sywyar.pixivdownload.core.work.service.WorkVisibilityService;

import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 代理 Pixiv AJAX API，供 pixiv-batch.html 使用。
 * 前端通过中性取得凭证头传入 Cookie，旧版 {@code X-Pixiv-Cookie} 仍兼容读取。
 */
@RestController
@RequestMapping("/api/pixiv")
@Slf4j
@RequiredArgsConstructor
public class PixivProxyController {

    private final ObjectMapper objectMapper;
    private final PixivThumbnailFetcher pixivThumbnailFetcher;
    private final PixivFetchService pixivFetchService;
    private final PixivProxyAccessPolicy pixivProxyAccessPolicy;
    private final RequestOwnerIdentityResolver requestOwnerIdentityResolver;
    private final WorkVisibilityService workVisibilityService;
    private final MessageResolver messages;

    /** 插件控制器自行投影预期的参数/安全拒绝，不依赖宿主全局异常处理器。 */
    @ExceptionHandler({IllegalArgumentException.class, SecurityException.class})
    public ResponseEntity<ErrorResponse> handleBadRequest(RuntimeException failure) {
        String detail = failure.getMessage();
        if (detail == null || detail.isBlank()) {
            detail = messages.get("error.request.param.invalid");
        }
        log.warn(messages.getForLog("workbench.log.request.failed", detail));
        return ResponseEntity.badRequest().body(new ErrorResponse(detail));
    }

    /** 缩略图端口失败由插件按既有 HTTP 语义投影，不依赖宿主实现层异常处理器。 */
    @ExceptionHandler(PixivThumbnailFetchException.class)
    public ResponseEntity<ErrorResponse> handleThumbnailFetch(PixivThumbnailFetchException failure) {
        return switch (failure.failure()) {
            case INVALID_TARGET -> thumbnailFailureResponse(
                    HttpStatus.BAD_REQUEST,
                    messages.get("pixiv.proxy.thumbnail-url.host.invalid"));
            case HTTP_STATUS -> thumbnailFailureResponse(
                    HttpStatus.BAD_GATEWAY,
                    failure.statusCode() == 401 || failure.statusCode() == 403
                            ? messages.get("pixiv.proxy.thumbnail.upstream.unauthorized")
                            : messages.get("pixiv.proxy.thumbnail.upstream.failed", failure.statusCode()));
            case TRANSPORT -> thumbnailFailureResponse(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    messages.get("pixiv.proxy.thumbnail.transport.failed"));
        };
    }

    private ResponseEntity<ErrorResponse> thumbnailFailureResponse(HttpStatus responseStatus, String detail) {
        log.warn(messages.getForLog("workbench.log.request.failed", detail));
        return ResponseEntity.status(responseStatus).body(new ErrorResponse(detail));
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
                    .body(new ProxyRateLimitResponse(
                            decision.errorMessage(), decision.maxRequests(), decision.windowHours()));
        };
    }

    /**
     * 若请求来自访客邀请会话，校验作品是否在可见范围；越界 403。
     * 非访客请求直接放行（管理员/普通访问由 AuthFilter 决定）。
     */
    private void guardArtworkForGuest(WorkVisibilityScope visibilityScope, String artworkId) {
        if (artworkId == null || artworkId.isBlank()) return;
        try {
            long id = Long.parseLong(artworkId.trim());
            workVisibilityService.requireVisible(visibilityScope, WorkType.ARTWORK, id);
        } catch (NumberFormatException ignored) {
            // 非数字 ID 不命中数据库，让现有逻辑处理；越界由其他校验拦下
        }
    }

    private String proxyGet(String url, String cookie) {
        return pixivFetchService.proxyGet(url, cookie);
    }

    private String proxyGetUri(URI uri, String cookie) {
        return pixivFetchService.proxyGetUri(uri, cookie);
    }

    private static String acquisitionCredential(HttpServletRequest request, String legacyCredential) {
        return AcquisitionCredentialResolver.resolve(
                request == null ? null : request.getHeader(AcquisitionCredentialResolver.HEADER_NAME),
                legacyCredential);
    }

    @GetMapping("/user/{userId}/artworks")
    public ResponseEntity<?> getUserArtworks(
            @PathVariable String userId,
            @RequestHeader(value = "X-Pixiv-Cookie", required = false) String cookie,
            HttpServletRequest request) throws IOException {
        cookie = acquisitionCredential(request, cookie);
        ResponseEntity<?> deny = checkMultiModeAccess(request);
        if (deny != null) return deny;
        String body = proxyGet(
                "https://www.pixiv.net/ajax/user/" + userId + "/profile/all", cookie);
        JsonNode root = objectMapper.readTree(body);
        if (root.path("error").asBoolean(false)) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse(root.path("message").asText()));
        }
        JsonNode b = root.path("body");
        List<String> ids = new ArrayList<>();
        b.path("illusts").fieldNames().forEachRemaining(ids::add);
        b.path("manga").fieldNames().forEachRemaining(ids::add);
        ids.sort((a, c2) -> Long.compare(Long.parseLong(c2), Long.parseLong(a)));
        return ResponseEntity.ok(new UserArtworksResponse(ids));
    }

    /**
     * 发现某画师已完成并公开的「约稿作品」（リクエスト 成品）ID 列表。成品本质是普通插画，
     * 前端预览/入队/下载复用 illust 链路（卡片走 {@code /user/{id}/illust-cards}）。
     */
    @GetMapping("/user/{userId}/request-artworks")
    public ResponseEntity<?> getUserRequestArtworks(
            @PathVariable String userId,
            @RequestHeader(value = "X-Pixiv-Cookie", required = false) String cookie,
            HttpServletRequest request) throws IOException {
        cookie = acquisitionCredential(request, cookie);
        ResponseEntity<?> deny = checkMultiModeAccess(request);
        if (deny != null) return deny;
        try {
            return ResponseEntity.ok(new UserArtworksResponse(
                    pixivFetchService.discoverUserRequestArtworkIds(userId, cookie)));
        } catch (PixivFetchService.PixivFetchException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @GetMapping("/user/{userId}/meta")
    public ResponseEntity<?> getUserMeta(
            @PathVariable String userId,
            @RequestHeader(value = "X-Pixiv-Cookie", required = false) String cookie,
            HttpServletRequest request) throws IOException {
        cookie = acquisitionCredential(request, cookie);
        ResponseEntity<?> deny = checkMultiModeAccess(request);
        if (deny != null) return deny;
        String body = proxyGet(
                "https://www.pixiv.net/ajax/user/" + userId + "?lang=zh", cookie);
        JsonNode root = objectMapper.readTree(body);
        if (root.path("error").asBoolean(false)) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse(root.path("message").asText()));
        }
        String name = root.path("body").path("name").asText();
        return ResponseEntity.ok(new UserMetaResponse(name, userId));
    }

    @GetMapping("/artwork/{artworkId}/meta")
    public ResponseEntity<?> getArtworkMeta(
            @PathVariable String artworkId,
            @RequestHeader(value = "X-Pixiv-Cookie", required = false) String cookie,
            HttpServletRequest request,
            WorkVisibilityScope visibilityScope) throws IOException {
        cookie = acquisitionCredential(request, cookie);
        guardArtworkForGuest(visibilityScope, artworkId);
        ResponseEntity<?> deny = checkMultiModeAccess(request);
        if (deny != null) return deny;
        String body = proxyGet(
                "https://www.pixiv.net/ajax/illust/" + artworkId, cookie);
        JsonNode root = objectMapper.readTree(body);
        if (root.path("error").asBoolean(false)) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse(root.path("message").asText()));
        }
        return ResponseEntity.ok(PixivProxyResponseMapper.artworkMeta(objectMapper, root.path("body")));
    }

    @GetMapping("/artwork/{artworkId}/pages")
    public ResponseEntity<?> getArtworkPages(
            @PathVariable String artworkId,
            @RequestHeader(value = "X-Pixiv-Cookie", required = false) String cookie,
            HttpServletRequest request,
            WorkVisibilityScope visibilityScope) throws IOException {
        cookie = acquisitionCredential(request, cookie);
        guardArtworkForGuest(visibilityScope, artworkId);
        ResponseEntity<?> deny = checkMultiModeAccess(request);
        if (deny != null) return deny;
        String body = proxyGet(
                "https://www.pixiv.net/ajax/illust/" + artworkId + "/pages", cookie);
        JsonNode root = objectMapper.readTree(body);
        if (root.path("error").asBoolean(false)) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse(root.path("message").asText()));
        }
        return ResponseEntity.ok(PixivProxyResponseMapper.artworkPages(root.path("body")));
    }

    @GetMapping("/artwork/{artworkId}/ugoira")
    public ResponseEntity<?> getUgoiraMeta(
            @PathVariable String artworkId,
            @RequestHeader(value = "X-Pixiv-Cookie", required = false) String cookie,
            HttpServletRequest request,
            WorkVisibilityScope visibilityScope) throws IOException {
        cookie = acquisitionCredential(request, cookie);
        guardArtworkForGuest(visibilityScope, artworkId);
        ResponseEntity<?> deny = checkMultiModeAccess(request);
        if (deny != null) return deny;
        String body = proxyGet(
                "https://www.pixiv.net/ajax/illust/" + artworkId + "/ugoira_meta", cookie);
        JsonNode root = objectMapper.readTree(body);
        if (root.path("error").asBoolean(false)) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse(root.path("message").asText()));
        }
        return ResponseEntity.ok(PixivProxyResponseMapper.ugoiraMeta(root.path("body")));
    }

    private static final Set<String> VALID_ORDERS  = Set.of("date_d", "date", "popular_d");
    private static final Set<String> VALID_MODES   = Set.of("all", "safe", "r18");
    private static final Set<String> VALID_S_MODES = Set.of("s_tag", "s_tc");

    private String validateSearchParams(String order, String mode, String sMode) {
        if (!VALID_ORDERS.contains(order)) {
            return messages.get("pixiv.proxy.search.order.invalid", order);
        }
        if (!VALID_MODES.contains(mode)) {
            return messages.get("pixiv.proxy.search.mode.invalid", mode);
        }
        if (!VALID_S_MODES.contains(sMode)) {
            return messages.get("pixiv.proxy.search.s-mode.invalid", sMode);
        }
        return null;
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
            throw new IllegalArgumentException(root.path("message").asText(messages.get("pixiv.proxy.search.failed")));
        }
        JsonNode illustManga = root.path("body").path("illustManga");
        return PixivProxyResponseMapper.searchResponse(illustManga, safePage);
    }

    @GetMapping("/search")
    public ResponseEntity<?> searchArtworks(
            @RequestParam String word,
            @RequestParam(defaultValue = "date_d") String order,
            @RequestParam(defaultValue = "all") String mode,
            @RequestParam(defaultValue = "s_tag") String sMode,
            @RequestParam(defaultValue = "1") int page,
            @RequestHeader(value = "X-Pixiv-Cookie", required = false) String cookie,
            HttpServletRequest request) throws IOException {
        cookie = acquisitionCredential(request, cookie);
        ResponseEntity<?> deny = checkMultiModeAccess(request);
        if (deny != null) return deny;
        String validationError = validateSearchParams(order, mode, sMode);
        if (validationError != null) {
            return ResponseEntity.badRequest().body(new ErrorResponse(validationError));
        }
        try {
            return ResponseEntity.ok(fetchSearchPage(word, order, mode, sMode, page, cookie));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @FunctionalInterface
    private interface RangePageFetcher {
        RangePage fetch(int page) throws IOException;
    }

    /** 单页抓取结果：items 的元素需提供 id（用于跨页去重），total 为 Pixiv 报告的总数。 */
    private record RangePage(List<?> items, int total, java.util.function.Function<Object, String> idOf) {
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

        LinkedHashMap<String, Object> deduped = new LinkedHashMap<>();
        int total = 0;
        int totalPages = Integer.MAX_VALUE;
        int fetchedPages = 0;
        int endPage = startPage;

        for (int p = startPage; p <= cappedEnd; p++) {
            if (p > totalPages) break;
            RangePage pageResponse = fetcher.fetch(p);
            total = pageResponse.total();
            totalPages = Math.max(1, (int) Math.ceil(total / (double) perPage));
            if (p > totalPages) break;
            for (Object item : pageResponse.items()) {
                deduped.putIfAbsent(pageResponse.idOf().apply(item), item);
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

    @GetMapping("/search/range")
    public ResponseEntity<?> rangeSearchArtworks(
            @RequestParam String word,
            @RequestParam(defaultValue = "date_d") String order,
            @RequestParam(defaultValue = "all") String mode,
            @RequestParam(defaultValue = "s_tag") String sMode,
            @RequestParam(defaultValue = "1") int startPage,
            @RequestParam(defaultValue = "1") int endPage,
            @RequestHeader(value = "X-Pixiv-Cookie", required = false) String cookie,
            HttpServletRequest request) throws IOException {
        String resolvedCredential = acquisitionCredential(request, cookie);
        ResponseEntity<?> deny = checkMultiModeAccess(request);
        if (deny != null) return deny;
        String validationError = validateSearchParams(order, mode, sMode);
        if (validationError != null) {
            return ResponseEntity.badRequest().body(new ErrorResponse(validationError));
        }
        if (startPage < 1 || endPage < 1) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse(messages.get("pixiv.proxy.search-range.invalid")));
        }
        try {
            int limitPage = resolveSearchFillLimitPage(request);
            return ResponseEntity.ok(buildSearchRange(startPage, endPage, 60, limitPage, p -> {
                SearchResponse r = fetchSearchPage(word, order, mode, sMode, p, resolvedCredential);
                return new RangePage(r.getItems(), r.getTotal(),
                        o -> ((SearchResponse.SearchItem) o).id());
            }));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @GetMapping("/series/{seriesId}")
    public ResponseEntity<?> getSeries(
            @PathVariable String seriesId,
            @RequestParam(defaultValue = "1") int page,
            @RequestHeader(value = "X-Pixiv-Cookie", required = false) String cookie,
            HttpServletRequest request) throws IOException {
        cookie = acquisitionCredential(request, cookie);
        ResponseEntity<?> deny = checkMultiModeAccess(request);
        if (deny != null) return deny;
        long parsedId;
        try {
            parsedId = Long.parseLong(seriesId);
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(messages.get("pixiv.proxy.series.id.invalid", seriesId)));
        }
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
            return ResponseEntity.badRequest().body(new ErrorResponse(root.path("message").asText()));
        }
        return ResponseEntity.ok(PixivProxyResponseMapper.seriesResponse(root.path("body"), parsedId, safePage));
    }

    /**
     * 批量获取画师插画/漫画的卡片元数据（供 User 模式预览渲染与客户端附加筛选）。
     * 经 {@code /ajax/user/{id}/illusts?ids[]=...} 拉取，返回与搜索结果同形的 {@link SearchResponse}，
     * 并按请求传入的 ids 顺序保序（Pixiv 返回的是按 id 键控的对象，顺序不保证）。
     */
    @GetMapping("/user/{userId}/illust-cards")
    public ResponseEntity<?> getUserIllustCards(
            @PathVariable String userId,
            @RequestParam List<String> ids,
            @RequestHeader(value = "X-Pixiv-Cookie", required = false) String cookie,
            HttpServletRequest request) throws IOException {
        cookie = acquisitionCredential(request, cookie);
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
            return ResponseEntity.badRequest().body(new ErrorResponse(root.path("message").asText()));
        }
        List<SearchResponse.SearchItem> items = PixivProxyResponseMapper.parseUserIllustCards(root.path("body"), ids);
        return ResponseEntity.ok(new SearchResponse(items, items.size(), 1));
    }

    @GetMapping("/thumbnail-proxy")
    public ResponseEntity<byte[]> proxyThumbnail(@RequestParam String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new SecurityException(messages.get("pixiv.proxy.thumbnail-url.invalid"));
        }
        byte[] bytes = pixivThumbnailFetcher.fetch(uri);
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.setCacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePublic());
        return ResponseEntity.ok().headers(responseHeaders).body(bytes);
    }

    // ── /me 端点：基于 cookie 解析当前用户 uid，代理「我的」书签 / 关注 / 珍藏集 ─────────────

    private static final Set<String> VALID_REST = Set.of("show", "hide");

    /**
     * 从 Pixiv cookie 串里抽出登录用户的 userId。
     * <p>PHPSESSID 格式为 {@code {userId}_{随机后缀}}，下划线前缀即 userId。返回 null 表示
     * cookie 缺失或不含合法 PHPSESSID（未登录 / 已过期 / 拼装错误）。
     */
    static String extractUidFromCookie(String cookie) {
        return PixivCookieUserResolver.extractUidFromCookie(cookie);
    }

    @GetMapping("/me/uid")
    public ResponseEntity<?> getMeUid(
            @RequestHeader(value = "X-Pixiv-Cookie", required = false) String cookie,
            HttpServletRequest request) {
        cookie = acquisitionCredential(request, cookie);
        ResponseEntity<?> deny = checkMultiModeAccess(request);
        if (deny != null) return deny;
        String uid = extractUidFromCookie(cookie);
        if (uid == null) {
            return ResponseEntity.status(401).body(new ErrorResponse(messages.get("pixiv.proxy.me.cookie.missing")));
        }
        return ResponseEntity.ok(new MeUidResponse(uid));
    }

    @GetMapping("/me/illust-bookmarks")
    public ResponseEntity<?> getMyIllustBookmarks(
            @RequestParam(defaultValue = "show") String rest,
            @RequestParam(required = false) String tag,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "48") int limit,
            @RequestHeader(value = "X-Pixiv-Cookie", required = false) String cookie,
            HttpServletRequest request) throws IOException {
        cookie = acquisitionCredential(request, cookie);
        ResponseEntity<?> deny = checkMultiModeAccess(request);
        if (deny != null) return deny;
        if (!VALID_REST.contains(rest)) {
            return ResponseEntity.badRequest().body(new ErrorResponse(messages.get("pixiv.proxy.me.rest.invalid", rest)));
        }
        int safeOffset = Math.max(0, offset);
        int safeLimit = Math.max(1, Math.min(100, limit));
        String uid = extractUidFromCookie(cookie);
        if (uid == null) {
            return ResponseEntity.status(401).body(new ErrorResponse(messages.get("pixiv.proxy.me.cookie.missing")));
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
            return ResponseEntity.badRequest().body(new ErrorResponse(root.path("message").asText()));
        }
        return ResponseEntity.ok(PixivProxyResponseMapper.bookmarkResponse(
                root.path("body"), safeOffset, safeLimit));
    }

    @GetMapping("/me/following")
    public ResponseEntity<?> getMyFollowing(
            @RequestParam(defaultValue = "show") String rest,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "24") int limit,
            @RequestHeader(value = "X-Pixiv-Cookie", required = false) String cookie,
            HttpServletRequest request) throws IOException {
        cookie = acquisitionCredential(request, cookie);
        ResponseEntity<?> deny = checkMultiModeAccess(request);
        if (deny != null) return deny;
        if (!VALID_REST.contains(rest)) {
            return ResponseEntity.badRequest().body(new ErrorResponse(messages.get("pixiv.proxy.me.rest.invalid", rest)));
        }
        int safeOffset = Math.max(0, offset);
        int safeLimit = Math.max(1, Math.min(100, limit));
        String uid = extractUidFromCookie(cookie);
        if (uid == null) {
            return ResponseEntity.status(401).body(new ErrorResponse(messages.get("pixiv.proxy.me.cookie.missing")));
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
            return ResponseEntity.badRequest().body(new ErrorResponse(root.path("message").asText()));
        }
        return ResponseEntity.ok(PixivProxyResponseMapper.followingPageResponse(
                root.path("body"), safeOffset, safeLimit));
    }

    /**
     * 已关注的用户的新作（フォロー新着作品）。基于 cookie 主人的登录态，代理
     * {@code /ajax/follow_latest/illust?mode=all&p=N}：返回当前页的插画/漫画/动图卡片，按
     * {@code body.page.ids} 的顺序排列。Pixiv 该接口不给作品总数，故以 {@code hasNext} 表示是否还有下一页。
     */
    @GetMapping("/me/follow-latest")
    public ResponseEntity<?> getMyFollowLatest(
            @RequestParam(defaultValue = "1") int p,
            @RequestHeader(value = "X-Pixiv-Cookie", required = false) String cookie,
            HttpServletRequest request) throws IOException {
        cookie = acquisitionCredential(request, cookie);
        ResponseEntity<?> deny = checkMultiModeAccess(request);
        if (deny != null) return deny;
        if (extractUidFromCookie(cookie) == null) {
            return ResponseEntity.status(401).body(new ErrorResponse(messages.get("pixiv.proxy.me.cookie.missing")));
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
            return ResponseEntity.badRequest().body(new ErrorResponse(root.path("message").asText()));
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
    @GetMapping("/me/collections")
    public ResponseEntity<?> getMyCollections(
            @RequestHeader(value = "X-Pixiv-Cookie", required = false) String cookie,
            HttpServletRequest request) throws IOException {
        cookie = acquisitionCredential(request, cookie);
        ResponseEntity<?> deny = checkMultiModeAccess(request);
        if (deny != null) return deny;
        String uid = extractUidFromCookie(cookie);
        if (uid == null) {
            return ResponseEntity.status(401).body(new ErrorResponse(messages.get("pixiv.proxy.me.cookie.missing")));
        }
        // 1) profile/all → collectionIds
        String allBody = proxyGet("https://www.pixiv.net/ajax/user/" + uid + "/profile/all?lang=zh", cookie);
        JsonNode allRoot = objectMapper.readTree(allBody);
        if (allRoot.path("error").asBoolean(false)) {
            return ResponseEntity.badRequest().body(new ErrorResponse(allRoot.path("message").asText()));
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
                return ResponseEntity.badRequest().body(new ErrorResponse(root.path("message").asText()));
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
    @GetMapping("/me/collection/{collectionId}/works")
    public ResponseEntity<?> getMyCollectionWorks(
            @PathVariable String collectionId,
            @RequestHeader(value = "X-Pixiv-Cookie", required = false) String cookie,
            HttpServletRequest request) throws IOException {
        cookie = acquisitionCredential(request, cookie);
        ResponseEntity<?> deny = checkMultiModeAccess(request);
        if (deny != null) return deny;
        if (extractUidFromCookie(cookie) == null) {
            return ResponseEntity.status(401).body(new ErrorResponse(messages.get("pixiv.proxy.me.cookie.missing")));
        }
        if (collectionId == null || collectionId.isBlank() || !collectionId.chars().allMatch(Character::isDigit)) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse(messages.get("pixiv.proxy.me.collection.id.invalid", collectionId)));
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
            return ResponseEntity.badRequest().body(new ErrorResponse(root.path("message").asText()));
        }
        List<CollectionWorksResponse.Work> works = PixivProxyResponseMapper.parseCollectionWorks(
                root.path("body"));
        return ResponseEntity.ok(new CollectionWorksResponse(works, works.size()));
    }
}

package top.sywyar.pixivdownload.novel.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;
import top.sywyar.pixivdownload.core.pixiv.PixivAjaxClient;
import top.sywyar.pixivdownload.core.pixiv.PixivProxyAccessDecision;
import top.sywyar.pixivdownload.core.pixiv.PixivProxyAccessPolicy;
import top.sywyar.pixivdownload.core.pixiv.PixivCookieUserResolver;
import top.sywyar.pixivdownload.core.web.AcquisitionCredentialResolver;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.novel.browser.NovelBrowserFetchTicketStore;
import top.sywyar.pixivdownload.novel.request.NovelDownloadRequestFactory;
import top.sywyar.pixivdownload.novel.response.NovelBookmarkCountResponse;
import top.sywyar.pixivdownload.novel.response.NovelErrorResponse;
import top.sywyar.pixivdownload.novel.response.NovelProxyRateLimitResponse;
import top.sywyar.pixivdownload.novel.response.NovelSearchResponse;
import top.sywyar.pixivdownload.novel.response.NovelSeriesResponse;
import top.sywyar.pixivdownload.novel.response.UserNovelsResponse;
import top.sywyar.pixivdownload.novel.schedule.PixivNovelMetadata;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentityResolver;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.core.work.model.WorkVisibilityScope;
import top.sywyar.pixivdownload.core.work.service.WorkVisibilityService;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/pixiv")
@PluginManagedBean
@RequiredArgsConstructor
public class NovelPixivProxyController {

    static final String PIXIV_API_LANGUAGE = "zh";
    private static final Set<String> VALID_ORDERS = Set.of("date_d", "date", "popular_d");
    private static final Set<String> VALID_MODES = Set.of("all", "safe", "r18");
    private static final Set<String> VALID_S_MODES = Set.of("s_tag", "s_tc");
    private static final Set<String> VALID_REST = Set.of("show", "hide");

    private final ObjectMapper objectMapper;
    private final PixivAjaxClient pixivAjaxClient;
    private final PixivProxyAccessPolicy pixivProxyAccessPolicy;
    private final RequestOwnerIdentityResolver requestOwnerIdentityResolver;
    private final WorkVisibilityService workVisibilityService;
    private final NovelBrowserFetchTicketStore browserFetchTicketStore;
    private final MessageResolver messages;

    private String proxyGet(String url, String cookie) {
        return pixivAjaxClient.get(URI.create(url), cookie);
    }

    private String proxyGetUri(URI uri, String cookie) {
        return pixivAjaxClient.get(uri, cookie);
    }

    private static String acquisitionCredential(HttpServletRequest request, String legacyCredential) {
        return AcquisitionCredentialResolver.resolve(
                request == null ? null : request.getHeader(AcquisitionCredentialResolver.HEADER_NAME),
                legacyCredential);
    }

    private ResponseEntity<?> checkMultiModeAccess(HttpServletRequest request) {
        PixivProxyAccessDecision decision = pixivProxyAccessPolicy.evaluate(
                requestOwnerIdentityResolver.resolveExistingOwnerUuid(request).orElse(null),
                requestOwnerIdentityResolver.isAdminAuthenticated(request));
        return switch (decision.outcome()) {
            case ALLOWED -> null;
            case OWNER_REQUIRED -> ResponseEntity.status(401)
                    .body(new NovelErrorResponse(decision.errorMessage()));
            case RATE_LIMITED -> ResponseEntity.status(429)
                    .body(new NovelProxyRateLimitResponse(
                            decision.errorMessage(), decision.maxRequests(), decision.windowHours()));
        };
    }

    private int resolveSearchFillLimitPage(HttpServletRequest request) {
        return pixivProxyAccessPolicy.resolveSearchFillLimitPage(
                requestOwnerIdentityResolver.isAdminAuthenticated(request));
    }

    /**
     * 若请求来自访客邀请会话，校验小说是否在可见范围；越界 403。
     */
    private void guardNovelForGuest(WorkVisibilityScope visibilityScope, String novelId) {
        if (novelId == null || novelId.isBlank()) return;
        try {
            long id = Long.parseLong(novelId.trim());
            workVisibilityService.requireVisible(visibilityScope, WorkType.NOVEL, id);
        } catch (NumberFormatException ignored) {
        }
    }

    @GetMapping("/novel/{novelId}/meta")
    public ResponseEntity<?> getNovelMeta(
            @PathVariable String novelId,
            @RequestHeader(value = "X-Pixiv-Cookie", required = false) String cookie,
            HttpServletRequest request,
            WorkVisibilityScope visibilityScope) throws IOException {
        cookie = acquisitionCredential(request, cookie);
        guardNovelForGuest(visibilityScope, novelId);
        ResponseEntity<?> deny = checkMultiModeAccess(request);
        if (deny != null) return deny;
        long parsedId;
        try {
            parsedId = Long.parseLong(novelId);
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().body(new NovelErrorResponse(messages.get("pixiv.proxy.novel.id.invalid", novelId)));
        }
        URI uri = UriComponentsBuilder
                .fromUriString("https://www.pixiv.net/ajax/novel/{id}")
                .queryParam("lang", "zh")
                .buildAndExpand(Map.of("id", parsedId))
                .encode()
                .toUri();
        String body = proxyGetUri(uri, cookie);
        JsonNode root = objectMapper.readTree(body);
        if (root.path("error").asBoolean(false)) {
            return ResponseEntity.badRequest().body(new NovelErrorResponse(root.path("message").asText()));
        }
        JsonNode b = root.path("body");
        if (!b.isObject() || !matchesNovelId(b.path("id"), parsedId)) {
            return ResponseEntity.badRequest()
                    .body(new NovelErrorResponse(messages.get("pixiv.proxy.novel.response.invalid")));
        }
        PixivNovelMetadata metadata = PixivNovelMetadata.parse(parsedId, b);
        String fetchToken = browserFetchTicketStore.issuePreviewFetchTicket(
                parsedId, metadata, NovelDownloadRequestFactory.boundedRawMetadata(objectMapper, b),
                requestOwnerIdentityResolver.resolve(request), cookie);
        return ResponseEntity.ok(NovelPixivResponseMapper.novelMeta(parsedId, b, fetchToken));
    }

    private static boolean matchesNovelId(JsonNode value, long expected) {
        if (value.isIntegralNumber()) {
            return value.canConvertToLong() && value.longValue() == expected;
        }
        return value.isTextual() && Long.toString(expected).equals(value.textValue());
    }

    @GetMapping("/novel/{novelId}/bookmark-count")
    public ResponseEntity<?> getNovelBookmarkCount(
            @PathVariable String novelId,
            @RequestHeader(value = "X-Pixiv-Cookie", required = false) String cookie,
            HttpServletRequest request,
            WorkVisibilityScope visibilityScope) throws IOException {
        cookie = acquisitionCredential(request, cookie);
        guardNovelForGuest(visibilityScope, novelId);
        ResponseEntity<?> deny = checkMultiModeAccess(request);
        if (deny != null) return deny;
        long parsedId;
        try {
            parsedId = Long.parseLong(novelId);
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().body(new NovelErrorResponse(messages.get("pixiv.proxy.novel.id.invalid", novelId)));
        }
        URI uri = UriComponentsBuilder
                .fromUriString("https://www.pixiv.net/ajax/novel/{id}")
                .queryParam("lang", "zh")
                .buildAndExpand(Map.of("id", parsedId))
                .encode()
                .toUri();
        String body = proxyGetUri(uri, cookie);
        JsonNode root = objectMapper.readTree(body);
        if (root.path("error").asBoolean(false)) {
            return ResponseEntity.badRequest().body(new NovelErrorResponse(root.path("message").asText()));
        }
        int bookmarkCount = root.path("body").path("bookmarkCount").asInt(-1);
        return ResponseEntity.ok(new NovelBookmarkCountResponse(bookmarkCount));
    }

    @GetMapping("/novel/series/{seriesId}")
    public ResponseEntity<?> getNovelSeries(
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
            return ResponseEntity.badRequest().body(new NovelErrorResponse(messages.get("pixiv.proxy.novel.series.id.invalid", seriesId)));
        }
        URI metaUri = UriComponentsBuilder
                .fromUriString("https://www.pixiv.net/ajax/novel/series/{id}")
                .queryParam("lang", "zh")
                .buildAndExpand(Map.of("id", parsedId))
                .encode()
                .toUri();
        String metaBody = proxyGetUri(metaUri, cookie);
        JsonNode metaRoot = objectMapper.readTree(metaBody);
        if (metaRoot.path("error").asBoolean(false)) {
            return ResponseEntity.badRequest().body(new NovelErrorResponse(metaRoot.path("message").asText()));
        }
        JsonNode mb = metaRoot.path("body");
        NovelSeriesResponse.NovelSeriesMeta meta = NovelPixivResponseMapper.seriesMeta(mb, parsedId);

        int safePage = Math.max(1, page);
        int limit = 30;
        URI contentUri = UriComponentsBuilder
                .fromUriString("https://www.pixiv.net/ajax/novel/series_content/{id}")
                .queryParam("limit", limit)
                .queryParam("last_order", (safePage - 1) * limit)
                .queryParam("order_by", "asc")
                .queryParam("lang", "zh")
                .buildAndExpand(Map.of("id", parsedId))
                .encode()
                .toUri();
        String contentBody = proxyGetUri(contentUri, cookie);
        JsonNode contentRoot = objectMapper.readTree(contentBody);
        List<NovelSeriesResponse.NovelSeriesItem> items = contentRoot.path("error").asBoolean(false)
                ? List.of()
                : NovelPixivResponseMapper.seriesItems(contentRoot, meta.authorId(), meta.authorName());
        boolean isLastPage = items.size() < limit || (meta.total() > 0 && safePage * limit >= meta.total());
        return ResponseEntity.ok(new NovelSeriesResponse(
                meta,
                items,
                safePage,
                isLastPage
        ));
    }

    @GetMapping("/novel-search")
    public ResponseEntity<?> searchNovels(
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
            return ResponseEntity.badRequest().body(new NovelErrorResponse(validationError));
        }
        try {
            return ResponseEntity.ok(fetchNovelSearchPage(word, order, mode, sMode, page, cookie));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new NovelErrorResponse(e.getMessage()));
        }
    }

    @GetMapping("/novel-search/range")
    public ResponseEntity<?> rangeSearchNovels(
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
            return ResponseEntity.badRequest().body(new NovelErrorResponse(validationError));
        }
        if (startPage < 1 || endPage < 1) {
            return ResponseEntity.badRequest()
                    .body(new NovelErrorResponse(messages.get("pixiv.proxy.search-range.invalid")));
        }
        try {
            int limitPage = resolveSearchFillLimitPage(request);
            return ResponseEntity.ok(buildSearchRange(startPage, endPage, 24, limitPage, p -> {
                NovelSearchResponse r = fetchNovelSearchPage(word, order, mode, sMode, p, resolvedCredential);
                return new RangePage(r.getItems(), r.getTotal(),
                        o -> ((NovelSearchResponse.NovelSearchItem) o).id());
            }));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new NovelErrorResponse(e.getMessage()));
        }
    }

    @GetMapping("/user/{userId}/novels")
    public ResponseEntity<?> getUserNovels(
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
            return ResponseEntity.badRequest().body(new NovelErrorResponse(root.path("message").asText()));
        }
        JsonNode b = root.path("body");
        List<String> ids = new ArrayList<>();
        b.path("novels").fieldNames().forEachRemaining(ids::add);
        ids.sort((a, c2) -> Long.compare(Long.parseLong(c2), Long.parseLong(a)));
        return ResponseEntity.ok(new UserNovelsResponse(ids));
    }

    /**
     * 批量获取画师小说的卡片元数据（供 User 模式小说预览渲染与客户端附加筛选）。
     * 经 {@code /ajax/user/{id}/novels?ids[]=...} 拉取，返回与小说搜索结果同形的 {@link NovelSearchResponse}，
     * 并按请求传入的 ids 顺序保序。
     */
    @GetMapping("/user/{userId}/novel-cards")
    public ResponseEntity<?> getUserNovelCards(
            @PathVariable String userId,
            @RequestParam List<String> ids,
            @RequestHeader(value = "X-Pixiv-Cookie", required = false) String cookie,
            HttpServletRequest request) throws IOException {
        cookie = acquisitionCredential(request, cookie);
        ResponseEntity<?> deny = checkMultiModeAccess(request);
        if (deny != null) return deny;
        if (ids == null || ids.isEmpty()) {
            return ResponseEntity.ok(new NovelSearchResponse(List.of(), 0, 1));
        }
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromUriString("https://www.pixiv.net/ajax/user/{userId}/novels");
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
            return ResponseEntity.badRequest().body(new NovelErrorResponse(root.path("message").asText()));
        }
        List<NovelSearchResponse.NovelSearchItem> items =
                NovelPixivResponseMapper.userNovelCards(root.path("body"), ids);
        return ResponseEntity.ok(new NovelSearchResponse(items, items.size(), 1));
    }

    @GetMapping("/me/novel-bookmarks")
    public ResponseEntity<?> getMyNovelBookmarks(
            @RequestParam(defaultValue = "show") String rest,
            @RequestParam(required = false) String tag,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "24") int limit,
            @RequestHeader(value = "X-Pixiv-Cookie", required = false) String cookie,
            HttpServletRequest request) throws IOException {
        cookie = acquisitionCredential(request, cookie);
        ResponseEntity<?> deny = checkMultiModeAccess(request);
        if (deny != null) return deny;
        if (!VALID_REST.contains(rest)) {
            return ResponseEntity.badRequest().body(new NovelErrorResponse(messages.get("pixiv.proxy.me.rest.invalid", rest)));
        }
        int safeOffset = Math.max(0, offset);
        int safeLimit = Math.max(1, Math.min(100, limit));
        String uid = PixivCookieUserResolver.extractUidFromCookie(cookie);
        if (uid == null) {
            return ResponseEntity.status(401).body(new NovelErrorResponse(messages.get("pixiv.proxy.me.cookie.missing")));
        }
        URI uri = UriComponentsBuilder
                .fromUriString("https://www.pixiv.net/ajax/user/{uid}/novels/bookmarks")
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
            return ResponseEntity.badRequest().body(new NovelErrorResponse(root.path("message").asText()));
        }
        return ResponseEntity.ok(NovelPixivResponseMapper.bookmarks(
                root.path("body"), safeOffset / safeLimit + 1));
    }

    private NovelSearchResponse fetchNovelSearchPage(
            String word,
            String order,
            String mode,
            String sMode,
            int page,
            String cookie) throws IOException {
        int safePage = Math.max(page, 1);
        URI searchUri = UriComponentsBuilder
                .fromUriString("https://www.pixiv.net/ajax/search/novels/{word}")
                .queryParam("word", "{word}")
                .queryParam("order", order)
                .queryParam("mode", mode)
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
        return NovelPixivResponseMapper.search(root.path("body").path("novel"), safePage);
    }

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

    @FunctionalInterface
    private interface RangePageFetcher {
        RangePage fetch(int page) throws IOException;
    }

    private record RangePage(List<?> items, int total, java.util.function.Function<Object, String> idOf) {
    }

    public record SearchRangeResponse(
            List<Object> items,
            int total,
            int startPage,
            int endPage,
            int requestedPages,
            int acceptedPages,
            int fetchedPages,
            int limitPage
    ) {
    }

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

}

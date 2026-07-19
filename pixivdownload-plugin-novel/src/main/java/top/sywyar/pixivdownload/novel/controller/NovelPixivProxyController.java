package top.sywyar.pixivdownload.novel.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;
import top.sywyar.pixivdownload.core.pixiv.PixivAjaxClient;
import top.sywyar.pixivdownload.core.pixiv.PixivDescriptionHtml;
import top.sywyar.pixivdownload.core.pixiv.PixivProxyAccessDecision;
import top.sywyar.pixivdownload.core.pixiv.PixivProxyAccessPolicy;
import top.sywyar.pixivdownload.core.work.model.WorkTag;
import top.sywyar.pixivdownload.core.pixiv.PixivCookieUserResolver;
import top.sywyar.pixivdownload.core.pixiv.PixivCoverUrlResolver;
import top.sywyar.pixivdownload.core.web.AcquisitionCredentialResolver;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.novel.response.NovelBookmarkCountResponse;
import top.sywyar.pixivdownload.novel.response.NovelErrorResponse;
import top.sywyar.pixivdownload.novel.response.NovelMetaResponse;
import top.sywyar.pixivdownload.novel.response.NovelProxyRateLimitResponse;
import top.sywyar.pixivdownload.novel.response.NovelSearchResponse;
import top.sywyar.pixivdownload.novel.response.NovelSeriesResponse;
import top.sywyar.pixivdownload.novel.response.UserNovelsResponse;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentityResolver;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.core.work.model.WorkVisibilityScope;
import top.sywyar.pixivdownload.core.work.service.WorkVisibilityService;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/pixiv")
@PluginManagedBean
@Slf4j
@RequiredArgsConstructor
public class NovelPixivProxyController {

    private static final Set<String> VALID_ORDERS = Set.of("date_d", "date", "popular_d");
    private static final Set<String> VALID_MODES = Set.of("all", "safe", "r18");
    private static final Set<String> VALID_S_MODES = Set.of("s_tag", "s_tc");
    private static final Set<String> VALID_REST = Set.of("show", "hide");

    private final ObjectMapper objectMapper;
    private final PixivAjaxClient pixivAjaxClient;
    private final PixivProxyAccessPolicy pixivProxyAccessPolicy;
    private final RequestOwnerIdentityResolver requestOwnerIdentityResolver;
    private final WorkVisibilityService workVisibilityService;
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
        Long seriesId = null;
        Long seriesOrder = null;
        String seriesTitle = null;
        JsonNode nav = b.path("seriesNavData");
        if (nav.isObject()) {
            long sid = nav.path("seriesId").asLong(0);
            if (sid > 0) {
                seriesId = sid;
                seriesOrder = nav.path("order").asLong(0);
                seriesTitle = nav.path("title").asText("");
            }
        }
        Integer wordCount = b.has("wordCount") ? b.path("wordCount").asInt(0) : null;
        Integer textLength = b.has("characterCount") ? b.path("characterCount").asInt(0) : null;
        Integer readingTimeSeconds = extractReadingTimeSeconds(b);
        String content = b.path("content").asText("");
        Integer pageCount = countPages(content);
        Long uploadTimestamp = extractUploadTimestamp(b);
        return ResponseEntity.ok(new NovelMetaResponse(
                parsedId,
                b.path("title").asText(""),
                b.path("xRestrict").asInt(0),
                b.path("aiType").asInt(0) >= 2,
                b.path("bookmarkCount").asInt(-1),
                parsePositiveLong(b.path("userId").asText(null)),
                b.path("userName").asText(""),
                PixivDescriptionHtml.normalizeLinks(b.path("description").asText("")),
                extractTags(b),
                seriesId,
                seriesOrder,
                seriesTitle,
                content,
                wordCount,
                textLength,
                readingTimeSeconds,
                pageCount,
                b.path("isOriginal").asBoolean(false),
                b.path("language").asText(""),
                extractNovelCoverUrl(b),
                uploadTimestamp,
                extractTextEmbeddedImages(b)
        ));
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
        long sid = parsePositiveOrDefault(mb.path("id").asText(null), parsedId);
        String title = mb.path("title").asText("");
        Long authorId = parsePositiveLong(mb.path("userId").asText(null));
        String authorName = mb.path("userName").asText("");
        int total = mb.path("publishedContentCount").asInt(mb.path("total").asInt(0));
        String language = mb.path("language").asText("");
        boolean isOriginal = mb.path("isOriginal").asBoolean(false);
        int totalCharCount = mb.path("publishedTotalCharacterCount").asInt(0);
        int totalWordCount = mb.path("publishedTotalWordCount").asInt(0);
        String caption = mb.path("caption").asText("");
        String coverUrl = extractSeriesCoverUrl(mb);
        List<WorkTag> seriesTags = extractTags(mb);

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
        List<NovelSeriesResponse.NovelSeriesItem> items = new ArrayList<>();
        if (!contentRoot.path("error").asBoolean(false)) {
            JsonNode arr = contentRoot.path("body").path("page").path("seriesContents");
            if (!arr.isArray() || arr.isEmpty()) {
                arr = contentRoot.path("body").path("seriesContents");
            }
            if (arr.isArray()) {
                for (JsonNode it : arr) {
                    items.add(new NovelSeriesResponse.NovelSeriesItem(
                            it.path("id").asText(""),
                            it.path("title").asText(""),
                            it.path("xRestrict").asInt(0),
                            it.path("aiType").asInt(0),
                            it.path("wordCount").asInt(0),
                            it.path("textLength").asInt(it.path("characterCount").asInt(0)),
                            extractReadingTimeSeconds(it),
                            it.path("userId").asText(String.valueOf(authorId == null ? "" : authorId)),
                            it.path("userName").asText(authorName),
                            it.path("seriesOrder").asInt(it.path("order").asInt(0)),
                            extractNovelCoverUrl(it),
                            extractUploadTimestamp(it),
                            extractTags(it)
                    ));
                }
            }
        }
        boolean isLastPage = items.size() < limit || (total > 0 && safePage * limit >= total);
        return ResponseEntity.ok(new NovelSeriesResponse(
                new NovelSeriesResponse.NovelSeriesMeta(sid, title, authorId, authorName, total,
                        language, isOriginal, totalCharCount, totalWordCount,
                        caption, coverUrl, seriesTags),
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
        List<NovelSearchResponse.NovelSearchItem> items = parseUserNovelCards(root.path("body"), ids);
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
        JsonNode b = root.path("body");
        int total = b.path("total").asInt(0);
        List<NovelSearchResponse.NovelSearchItem> items = new ArrayList<>();
        for (JsonNode item : b.path("works")) {
            items.add(new NovelSearchResponse.NovelSearchItem(
                    item.path("id").asText(""),
                    item.path("title").asText(""),
                    item.path("xRestrict").asInt(0),
                    item.path("aiType").asInt(0),
                    item.path("bookmarkCount").asInt(-1),
                    item.path("wordCount").asInt(0),
                    item.path("textLength").asInt(item.path("characterCount").asInt(0)),
                    item.path("userId").asText(""),
                    item.path("userName").asText(""),
                    extractNovelCoverUrl(item),
                    item.path("isOriginal").asBoolean(false),
                    parseStringTags(item.path("tags"))
            ));
        }
        return ResponseEntity.ok(new NovelSearchResponse(items, total, safeOffset / safeLimit + 1));
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
        JsonNode novel = root.path("body").path("novel");
        int total = novel.path("total").asInt(0);
        List<NovelSearchResponse.NovelSearchItem> items = new ArrayList<>();
        for (JsonNode item : novel.path("data")) {
            items.add(new NovelSearchResponse.NovelSearchItem(
                    item.path("id").asText(""),
                    item.path("title").asText(""),
                    item.path("xRestrict").asInt(0),
                    item.path("aiType").asInt(0),
                    item.path("bookmarkCount").asInt(-1),
                    item.path("wordCount").asInt(0),
                    item.path("textLength").asInt(item.path("characterCount").asInt(0)),
                    item.path("userId").asText(""),
                    item.path("userName").asText(""),
                    item.path("url").asText(""),
                    item.path("isOriginal").asBoolean(false),
                    parseStringTags(item.path("tags"))
            ));
        }
        return new NovelSearchResponse(items, total, safePage);
    }

    static List<NovelSearchResponse.NovelSearchItem> parseUserNovelCards(JsonNode body, List<String> ids) {
        List<NovelSearchResponse.NovelSearchItem> items = new ArrayList<>();
        if (body == null || ids == null) return items;
        for (String id : ids) {
            JsonNode item = body.path(id);
            if (item.isMissingNode() || item.isNull() || !item.isObject()) continue;
            items.add(new NovelSearchResponse.NovelSearchItem(
                    item.path("id").asText(id),
                    item.path("title").asText(""),
                    item.path("xRestrict").asInt(0),
                    item.path("aiType").asInt(0),
                    item.path("bookmarkCount").asInt(-1),
                    item.path("wordCount").asInt(0),
                    item.path("textLength").asInt(item.path("characterCount").asInt(0)),
                    item.path("userId").asText(""),
                    item.path("userName").asText(""),
                    extractNovelCoverUrl(item),
                    item.path("isOriginal").asBoolean(false),
                    parseStringTags(item.path("tags"))
            ));
        }
        return items;
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

    private static Long parsePositiveLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static long parsePositiveOrDefault(String value, long fallback) {
        Long parsed = parsePositiveLong(value);
        return parsed == null ? fallback : parsed;
    }

    private static List<WorkTag> extractTags(JsonNode body) {
        JsonNode tagsArr = body.path("tags").path("tags");
        if (!tagsArr.isArray() || tagsArr.isEmpty()) {
            tagsArr = body.path("tags");
        }
        if (!tagsArr.isArray() || tagsArr.isEmpty()) {
            return List.of();
        }
        List<WorkTag> out = new ArrayList<>();
        for (JsonNode t : tagsArr) {
            String name = t.isTextual() ? t.asText("") : t.path("tag").asText(t.path("name").asText(""));
            if (name.isEmpty()) continue;
            String translated = null;
            JsonNode translation = t.path("translation");
            if (translation.isObject()) {
                String en = translation.path("en").asText("");
                if (!en.isEmpty()) translated = en;
            }
            out.add(new WorkTag(null, name, translated));
        }
        return out;
    }

    static List<String> parseStringTags(JsonNode tagsNode) {
        if (tagsNode == null || !tagsNode.isArray() || tagsNode.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        for (JsonNode tag : tagsNode) {
            String value = tag.isTextual() ? tag.asText("") : tag.path("tag").asText("");
            value = value.trim();
            if (!value.isEmpty()) {
                tags.add(value);
            }
        }
        return new ArrayList<>(tags);
    }

    private static String extractNovelCoverUrl(JsonNode node) {
        for (String parent : List.of("imageUrls", "urls")) {
            JsonNode urls = node.path(parent);
            if (urls.isObject()) {
                for (String key : List.of("original", "large", "regular", "medium", "squareMedium")) {
                    String cover = urls.path(key).asText("");
                    if (!cover.isBlank()) {
                        return PixivCoverUrlResolver.preferHighResolution(cover);
                    }
                }
            }
        }
        for (String key : List.of("coverUrl", "url", "thumbnailUrl")) {
            String cover = node.path(key).asText("");
            if (!cover.isBlank()) {
                return PixivCoverUrlResolver.preferHighResolution(cover);
            }
        }
        return "";
    }

    private static String extractSeriesCoverUrl(JsonNode meta) {
        JsonNode urls = meta.path("cover").path("urls");
        if (urls.isObject()) {
            for (String key : List.of("original", "1200x1200", "720x720", "480mw", "240mw")) {
                String value = urls.path(key).asText("");
                if (!value.isBlank()) return value;
            }
        }
        for (String key : List.of("coverImageUrl", "coverImage", "thumbnailUrl")) {
            String value = meta.path(key).asText("");
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private static Integer extractReadingTimeSeconds(JsonNode node) {
        String[] fieldNames = {"readingTimeSeconds", "readingTime", "readTime", "estimatedReadingTime"};
        for (String fieldName : fieldNames) {
            JsonNode value = node.path(fieldName);
            if (value.isMissingNode() || value.isNull()) continue;
            if (value.isNumber()) {
                int seconds = value.asInt(0);
                return seconds > 0 ? seconds : null;
            }
            String raw = value.asText("").trim();
            if (raw.isEmpty()) continue;
            String digits = raw.replaceAll("[^0-9]", "");
            if (digits.isEmpty()) continue;
            try {
                int seconds = Integer.parseInt(digits);
                return seconds > 0 ? seconds : null;
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private static Long extractUploadTimestamp(JsonNode node) {
        for (String fieldName : List.of("uploadDate", "createDate", "updateDate")) {
            Long parsed = parsePixivIsoToEpochMillis(node.path(fieldName).asText(null));
            if (parsed != null) return parsed;
        }
        return null;
    }

    private static Integer countPages(String content) {
        if (content == null || content.isEmpty()) return 1;
        int pages = 1;
        int idx = 0;
        while ((idx = content.indexOf("[newpage]", idx)) >= 0) {
            pages++;
            idx += "[newpage]".length();
        }
        return pages;
    }

    private static Long parsePixivIsoToEpochMillis(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli();
        } catch (Exception e) {
            log.debug("Failed to parse Pixiv ISO date: {}", iso, e);
            return null;
        }
    }

    /**
     * 抽取 Pixiv 小说 AJAX 响应中的 {@code body.textEmbeddedImages}。
     * 结构示例：{@code "1234": { "novelImageId": "1234", "urls": { "original": "https://i.pximg.net/.../1234.jpg" } }}。
     * 仅保留 {@code original} URL，且只接受 pximg.net 主机。
     */
    private static Map<String, String> extractTextEmbeddedImages(JsonNode body) {
        JsonNode node = body.path("textEmbeddedImages");
        if (!node.isObject() || node.isEmpty()) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        node.fields().forEachRemaining(e -> {
            String url = e.getValue().path("urls").path("original").asText("");
            if (url.isBlank()) return;
            try {
                URI uri = URI.create(url);
                String host = uri.getHost();
                if (host == null || !host.endsWith(".pximg.net")) return;
            } catch (IllegalArgumentException ignored) {
                return;
            }
            out.put(e.getKey(), url);
        });
        return out;
    }
}

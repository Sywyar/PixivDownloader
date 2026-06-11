package top.sywyar.pixivdownload.download.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.common.PixivDescriptionHtml;
import top.sywyar.pixivdownload.common.PixivRequestHeaders;
import top.sywyar.pixivdownload.common.UuidUtils;
import top.sywyar.pixivdownload.download.PixivFetchService;
import top.sywyar.pixivdownload.download.db.TagDto;
import top.sywyar.pixivdownload.download.response.*;
import top.sywyar.pixivdownload.novel.NovelCoverUrlResolver;
import top.sywyar.pixivdownload.novel.response.NovelBookmarkCountResponse;
import top.sywyar.pixivdownload.novel.response.NovelMetaResponse;
import top.sywyar.pixivdownload.novel.response.NovelSearchResponse;
import top.sywyar.pixivdownload.novel.response.NovelSeriesResponse;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.quota.MultiModeConfig;
import top.sywyar.pixivdownload.quota.UserQuotaService;
import top.sywyar.pixivdownload.quota.response.ProxyRateLimitResponse;
import top.sywyar.pixivdownload.setup.SetupService;
import top.sywyar.pixivdownload.setup.guest.GuestAccessGuard;

import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 代理 Pixiv AJAX API，供 pixiv-batch.html 使用。
 * 前端通过 X-Pixiv-Cookie 请求头传入 Cookie，后端附带 Cookie 访问 Pixiv。
 */
@RestController
@RequestMapping("/api/pixiv")
@Slf4j
@RequiredArgsConstructor
public class PixivProxyController {

    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
    private final PixivFetchService pixivFetchService;
    private final SetupService setupService;
    private final UserQuotaService userQuotaService;
    private final MultiModeConfig multiModeConfig;
    private final GuestAccessGuard guestAccessGuard;
    private final AppMessages messages;

    /**
     * 多人模式访问控制：
     * - 要求 UUID 已存在（cookie 或 X-User-UUID 请求头），不接受自动生成的匿名访问
     * - 在 resetPeriodHours 窗口内最多 maxArtworks 次代理请求
     * 返回 null 表示校验通过；返回 ResponseEntity 表示应直接返回该错误。
     * solo 模式已由 AuthFilter 完成认证，直接返回 null。
     */
    private ResponseEntity<?> checkMultiModeAccess(HttpServletRequest request) {
        if (!"multi".equals(setupService.getMode())) {
            return null; // solo 模式，AuthFilter 已验证 session
        }
        if (setupService.isAdminLoggedIn(request)) {
            return null; // multi 模式下管理员不受代理请求限制
        }
        String uuid = UuidUtils.extractExistingUuid(request);
        if (uuid == null) {
            return ResponseEntity.status(401).body(new ErrorResponse(messages.get("pixiv.proxy.user-uuid.missing")));
        }
        if (!userQuotaService.checkAndReserveProxy(uuid)) {
            int max = multiModeConfig.getQuota().getMaxProxyRequests();
            int hours = multiModeConfig.getQuota().getResetPeriodHours();
            return ResponseEntity.status(429).body(new ProxyRateLimitResponse(
                    messages.get("pixiv.proxy.rate-limit.exceeded", hours, max),
                    max, hours));
        }
        return null;
    }

    /**
     * 若请求来自访客邀请会话，校验作品是否在可见范围；越界 403。
     * 非访客请求直接放行（管理员/普通访问由 AuthFilter 决定）。
     */
    private void guardArtworkForGuest(HttpServletRequest request, String artworkId) {
        if (artworkId == null || artworkId.isBlank()) return;
        try {
            long id = Long.parseLong(artworkId.trim());
            guestAccessGuard.requireVisible(request, id);
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

    @GetMapping("/user/{userId}/artworks")
    public ResponseEntity<?> getUserArtworks(
            @PathVariable String userId,
            @RequestHeader(value = "X-Pixiv-Cookie", required = false) String cookie,
            HttpServletRequest request) throws IOException {
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
            HttpServletRequest request) throws IOException {
        guardArtworkForGuest(request, artworkId);
        ResponseEntity<?> deny = checkMultiModeAccess(request);
        if (deny != null) return deny;
        String body = proxyGet(
                "https://www.pixiv.net/ajax/illust/" + artworkId, cookie);
        JsonNode root = objectMapper.readTree(body);
        if (root.path("error").asBoolean(false)) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse(root.path("message").asText()));
        }
        JsonNode b = root.path("body");
        JsonNode nav = b.path("seriesNavData");
        Long seriesId = null;
        Long seriesOrder = null;
        String seriesTitle = null;
        if (nav.isObject()) {
            long sid = nav.path("seriesId").asLong(0);
            if (sid > 0) {
                seriesId = sid;
                seriesOrder = nav.path("order").asLong(0);
                seriesTitle = nav.path("title").asText("");
            }
        }
        return ResponseEntity.ok(new ArtworkMetaResponse(
                b.path("illustType").asInt(0),
                b.path("illustTitle").asText(""),
                b.path("xRestrict").asInt(0),
                b.path("aiType").asInt(0) >= 2,
                b.path("bookmarkCount").asInt(-1),
                b.path("pageCount").asInt(0),
                parsePositiveLong(b.path("userId").asText(null)),
                b.path("userName").asText(""),
                PixivDescriptionHtml.normalizeLinks(b.path("description").asText("")),
                extractTags(b),
                seriesId,
                seriesOrder,
                seriesTitle
        ));
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

    private static List<TagDto> extractTags(JsonNode body) {
        JsonNode tagsArr = body.path("tags").path("tags");
        if (!tagsArr.isArray() || tagsArr.isEmpty()) {
            tagsArr = body.path("tags");
        }
        if (!tagsArr.isArray() || tagsArr.isEmpty()) {
            return List.of();
        }
        List<TagDto> out = new ArrayList<>();
        for (JsonNode t : tagsArr) {
            String name = t.isTextual() ? t.asText("") : t.path("tag").asText(t.path("name").asText(""));
            if (name.isEmpty()) continue;
            String translated = null;
            JsonNode translation = t.path("translation");
            if (translation.isObject()) {
                String en = translation.path("en").asText("");
                if (!en.isEmpty()) translated = en;
            }
            out.add(new TagDto(name, translated));
        }
        return out;
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

    private static String extractNovelCoverUrl(JsonNode node) {
        for (String parent : List.of("imageUrls", "urls")) {
            JsonNode urls = node.path(parent);
            if (urls.isObject()) {
                for (String key : List.of("original", "large", "regular", "medium", "squareMedium")) {
                    String cover = urls.path(key).asText("");
                    if (!cover.isBlank()) {
                        return NovelCoverUrlResolver.preferHighResolution(cover);
                    }
                }
            }
        }
        for (String key : List.of("coverUrl", "url", "thumbnailUrl")) {
            String cover = node.path(key).asText("");
            if (!cover.isBlank()) {
                return NovelCoverUrlResolver.preferHighResolution(cover);
            }
        }
        return "";
    }

    private static Long extractUploadTimestamp(JsonNode node) {
        for (String fieldName : List.of("uploadDate", "createDate", "updateDate")) {
            Long parsed = parsePixivIsoToEpochMillis(node.path(fieldName).asText(null));
            if (parsed != null) return parsed;
        }
        return null;
    }

    @GetMapping("/artwork/{artworkId}/pages")
    public ResponseEntity<?> getArtworkPages(
            @PathVariable String artworkId,
            @RequestHeader(value = "X-Pixiv-Cookie", required = false) String cookie,
            HttpServletRequest request) throws IOException {
        guardArtworkForGuest(request, artworkId);
        ResponseEntity<?> deny = checkMultiModeAccess(request);
        if (deny != null) return deny;
        String body = proxyGet(
                "https://www.pixiv.net/ajax/illust/" + artworkId + "/pages", cookie);
        JsonNode root = objectMapper.readTree(body);
        if (root.path("error").asBoolean(false)) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse(root.path("message").asText()));
        }
        List<String> urls = new ArrayList<>();
        for (JsonNode page : root.path("body")) {
            String origUrl = page.path("urls").path("original").asText("");
            if (!origUrl.isEmpty()) urls.add(origUrl);
        }
        return ResponseEntity.ok(new ArtworkPagesResponse(urls));
    }

    @GetMapping("/artwork/{artworkId}/ugoira")
    public ResponseEntity<?> getUgoiraMeta(
            @PathVariable String artworkId,
            @RequestHeader(value = "X-Pixiv-Cookie", required = false) String cookie,
            HttpServletRequest request) throws IOException {
        guardArtworkForGuest(request, artworkId);
        ResponseEntity<?> deny = checkMultiModeAccess(request);
        if (deny != null) return deny;
        String body = proxyGet(
                "https://www.pixiv.net/ajax/illust/" + artworkId + "/ugoira_meta", cookie);
        JsonNode root = objectMapper.readTree(body);
        if (root.path("error").asBoolean(false)) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse(root.path("message").asText()));
        }
        JsonNode b = root.path("body");
        String zipUrl = b.path("originalSrc").asText("");
        if (zipUrl.isEmpty()) zipUrl = b.path("src").asText("");
        List<Integer> delays = new ArrayList<>();
        for (JsonNode frame : b.path("frames")) {
            delays.add(frame.path("delay").asInt(100));
        }
        return ResponseEntity.ok(new UgoiraMetaResponse(zipUrl, delays));
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
        if (!"multi".equals(setupService.getMode())) {
            return 0;
        }
        if (setupService.isAdminLoggedIn(request)) {
            return 0;
        }
        return Math.max(0, multiModeConfig.getLimitPage());
    }

    /**
     * Pixiv 搜索结果 item 的 tags 为字符串数组（如 ["tag1","tag2"]）。
     * 解析为去空白、去重、保序的字符串列表，供前端做客户端标签精确/模糊筛选。
     */
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

    /**
     * 把 {@code /ajax/user/{id}/illusts?ids[]=...} 的 {@code body}（按作品 id 键控的对象）解析为
     * 与搜索结果同形的卡片列表，并按 {@code ids} 请求顺序保序、跳过已删除（null/缺失）的作品。
     * 纯函数：不触网、不依赖实例状态，便于单测。
     */
    static List<SearchResponse.SearchItem> parseUserIllustCards(JsonNode body, List<String> ids) {
        List<SearchResponse.SearchItem> items = new ArrayList<>();
        if (body == null || ids == null) return items;
        for (String id : ids) {
            JsonNode item = body.path(id);
            if (item.isMissingNode() || item.isNull() || !item.isObject()) continue;
            items.add(new SearchResponse.SearchItem(
                    item.path("id").asText(id),
                    item.path("title").asText(""),
                    item.path("illustType").asInt(0),
                    item.path("xRestrict").asInt(0),
                    item.path("aiType").asInt(0),
                    item.path("url").asText(""),
                    item.path("pageCount").asInt(1),
                    item.path("userId").asText(""),
                    item.path("userName").asText(""),
                    parseStringTags(item.path("tags"))
            ));
        }
        return items;
    }

    /**
     * 把 {@code /ajax/user/{id}/novels?ids[]=...} 的 {@code body} 解析为与小说搜索结果同形的卡片列表，
     * 同样按 {@code ids} 请求顺序保序、跳过已删除的作品。纯函数。
     */
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
        int total = illustManga.path("total").asInt(0);
        List<SearchResponse.SearchItem> items = new ArrayList<>();
        for (JsonNode item : illustManga.path("data")) {
            items.add(new SearchResponse.SearchItem(
                    item.path("id").asText(""),
                    item.path("title").asText(""),
                    item.path("illustType").asInt(0),
                    item.path("xRestrict").asInt(0),
                    item.path("aiType").asInt(0),
                    item.path("url").asText(""),
                    item.path("pageCount").asInt(1),
                    item.path("userId").asText(""),
                    item.path("userName").asText(""),
                    parseStringTags(item.path("tags"))
            ));
        }
        return new SearchResponse(items, total, safePage);
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

    @GetMapping("/search")
    public ResponseEntity<?> searchArtworks(
            @RequestParam String word,
            @RequestParam(defaultValue = "date_d") String order,
            @RequestParam(defaultValue = "all") String mode,
            @RequestParam(defaultValue = "s_tag") String sMode,
            @RequestParam(defaultValue = "1") int page,
            @RequestHeader(value = "X-Pixiv-Cookie", required = false) String cookie,
            HttpServletRequest request) throws IOException {
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
                SearchResponse r = fetchSearchPage(word, order, mode, sMode, p, cookie);
                return new RangePage(r.getItems(), r.getTotal(),
                        o -> ((SearchResponse.SearchItem) o).getId());
            }));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
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
            return ResponseEntity.ok(buildSearchRange(startPage, endPage, 24, limitPage, p -> {
                NovelSearchResponse r = fetchNovelSearchPage(word, order, mode, sMode, p, cookie);
                return new RangePage(r.getItems(), r.getTotal(),
                        o -> ((NovelSearchResponse.NovelSearchItem) o).getId());
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
        JsonNode b = root.path("body");
        // illustSeries[0] holds the series record
        JsonNode seriesArr = b.path("illustSeries");
        long sid = parsedId;
        String title = "";
        Long authorId = null;
        String authorName = "";
        int total = 0;
        String caption = "";
        String coverUrl = "";
        if (seriesArr.isArray() && !seriesArr.isEmpty()) {
            JsonNode s = seriesArr.get(0);
            sid = parsePositiveOrDefault(s.path("id").asText(null), parsedId);
            title = s.path("title").asText("");
            authorId = parsePositiveLong(s.path("userId").asText(null));
            total = s.path("total").asInt(0);
            caption = s.path("caption").asText("");
            coverUrl = extractSeriesCoverUrl(s);
        }
        // Author name from users object
        JsonNode usersArr = b.path("users");
        if (authorId != null && usersArr.isArray()) {
            for (JsonNode u : usersArr) {
                if (u.path("userId").asText("").equals(String.valueOf(authorId))) {
                    authorName = u.path("name").asText("");
                    break;
                }
            }
        }
        // Items: thumbnails.illust[]
        JsonNode thumbsIllust = b.path("thumbnails").path("illust");
        // Order map: page[].works[] gives series_order via {id, order}
        Map<String, Integer> orderMap = new LinkedHashMap<>();
        JsonNode pageArr = b.path("page").path("series");
        if (pageArr.isArray()) {
            for (JsonNode entry : pageArr) {
                String id = entry.path("workId").asText("");
                int order = entry.path("order").asInt(0);
                if (!id.isEmpty()) orderMap.put(id, order);
            }
        }
        List<SeriesResponse.SeriesItem> items = new ArrayList<>();
        if (thumbsIllust.isArray()) {
            int fallbackOrder = (safePage - 1) * 12;
            for (JsonNode t : thumbsIllust) {
                String id = t.path("id").asText("");
                if (id.isEmpty()) continue;
                int seriesOrder = orderMap.getOrDefault(id, ++fallbackOrder);
                items.add(new SeriesResponse.SeriesItem(
                        id,
                        t.path("title").asText(""),
                        t.path("illustType").asInt(0),
                        t.path("xRestrict").asInt(0),
                        t.path("aiType").asInt(0),
                        t.path("url").asText(""),
                        t.path("pageCount").asInt(1),
                        t.path("userId").asText(""),
                        t.path("userName").asText(""),
                        seriesOrder,
                        parseStringTags(t.path("tags"))
                ));
            }
            // Filter to only series members and sort by series order ascending
            List<SeriesResponse.SeriesItem> filtered = new ArrayList<>();
            for (SeriesResponse.SeriesItem item : items) {
                if (orderMap.containsKey(item.id())) filtered.add(item);
            }
            if (!filtered.isEmpty()) {
                filtered.sort(Comparator.comparingInt(SeriesResponse.SeriesItem::seriesOrder));
                items = filtered;
            }
        }
        boolean isLastPage = items.size() < 12 || (total > 0 && safePage * 12 >= total);
        return ResponseEntity.ok(new SeriesResponse(
                new SeriesResponse.SeriesMeta(sid, title, authorId, authorName, total, caption, coverUrl),
                items,
                safePage,
                isLastPage
        ));
    }

    /**
     * 从 {@code /ajax/series/{id}} 的 {@code illustSeries[0]} 中抽取封面 URL；优先取最高分辨率。
     * 失败返回空串（与该字段在 DTO 中的"未知"语义一致）。
     */
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

    private static long parsePositiveOrDefault(String value, long fallback) {
        Long parsed = parsePositiveLong(value);
        return parsed == null ? fallback : parsed;
    }

    // ── Novel endpoints ─────────────────────────────────────────────────────────

    /**
     * 若请求来自访客邀请会话，校验小说是否在可见范围；越界 403。
     */
    private void guardNovelForGuest(HttpServletRequest request, String novelId) {
        if (novelId == null || novelId.isBlank()) return;
        try {
            long id = Long.parseLong(novelId.trim());
            guestAccessGuard.requireNovelVisible(request, id);
        } catch (NumberFormatException ignored) {
        }
    }

    @GetMapping("/novel/{novelId}/meta")
    public ResponseEntity<?> getNovelMeta(
            @PathVariable String novelId,
            @RequestHeader(value = "X-Pixiv-Cookie", required = false) String cookie,
            HttpServletRequest request) throws IOException {
        guardNovelForGuest(request, novelId);
        ResponseEntity<?> deny = checkMultiModeAccess(request);
        if (deny != null) return deny;
        long parsedId;
        try {
            parsedId = Long.parseLong(novelId);
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(messages.get("pixiv.proxy.novel.id.invalid", novelId)));
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
            return ResponseEntity.badRequest().body(new ErrorResponse(root.path("message").asText()));
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
            HttpServletRequest request) throws IOException {
        guardNovelForGuest(request, novelId);
        ResponseEntity<?> deny = checkMultiModeAccess(request);
        if (deny != null) return deny;
        long parsedId;
        try {
            parsedId = Long.parseLong(novelId);
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(messages.get("pixiv.proxy.novel.id.invalid", novelId)));
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
            return ResponseEntity.badRequest().body(new ErrorResponse(root.path("message").asText()));
        }
        int bookmarkCount = root.path("body").path("bookmarkCount").asInt(-1);
        return ResponseEntity.ok(new NovelBookmarkCountResponse(bookmarkCount));
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

    @GetMapping("/novel/series/{seriesId}")
    public ResponseEntity<?> getNovelSeries(
            @PathVariable String seriesId,
            @RequestParam(defaultValue = "1") int page,
            @RequestHeader(value = "X-Pixiv-Cookie", required = false) String cookie,
            HttpServletRequest request) throws IOException {
        ResponseEntity<?> deny = checkMultiModeAccess(request);
        if (deny != null) return deny;
        long parsedId;
        try {
            parsedId = Long.parseLong(seriesId);
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(messages.get("pixiv.proxy.novel.series.id.invalid", seriesId)));
        }
        // 1) Series meta
        URI metaUri = UriComponentsBuilder
                .fromUriString("https://www.pixiv.net/ajax/novel/series/{id}")
                .queryParam("lang", "zh")
                .buildAndExpand(Map.of("id", parsedId))
                .encode()
                .toUri();
        String metaBody = proxyGetUri(metaUri, cookie);
        JsonNode metaRoot = objectMapper.readTree(metaBody);
        if (metaRoot.path("error").asBoolean(false)) {
            return ResponseEntity.badRequest().body(new ErrorResponse(metaRoot.path("message").asText()));
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
        List<TagDto> seriesTags = extractTags(mb);

        // 2) Series content (paginated 30/page)
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
        ResponseEntity<?> deny = checkMultiModeAccess(request);
        if (deny != null) return deny;
        String validationError = validateSearchParams(order, mode, sMode);
        if (validationError != null) {
            return ResponseEntity.badRequest().body(new ErrorResponse(validationError));
        }
        try {
            return ResponseEntity.ok(fetchNovelSearchPage(word, order, mode, sMode, page, cookie));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @GetMapping("/user/{userId}/novels")
    public ResponseEntity<?> getUserNovels(
            @PathVariable String userId,
            @RequestHeader(value = "X-Pixiv-Cookie", required = false) String cookie,
            HttpServletRequest request) throws IOException {
        ResponseEntity<?> deny = checkMultiModeAccess(request);
        if (deny != null) return deny;
        String body = proxyGet(
                "https://www.pixiv.net/ajax/user/" + userId + "/profile/all", cookie);
        JsonNode root = objectMapper.readTree(body);
        if (root.path("error").asBoolean(false)) {
            return ResponseEntity.badRequest().body(new ErrorResponse(root.path("message").asText()));
        }
        JsonNode b = root.path("body");
        List<String> ids = new ArrayList<>();
        b.path("novels").fieldNames().forEachRemaining(ids::add);
        ids.sort((a, c2) -> Long.compare(Long.parseLong(c2), Long.parseLong(a)));
        return ResponseEntity.ok(new UserArtworksResponse(ids));
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
        List<SearchResponse.SearchItem> items = parseUserIllustCards(root.path("body"), ids);
        return ResponseEntity.ok(new SearchResponse(items, items.size(), 1));
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
            return ResponseEntity.badRequest().body(new ErrorResponse(root.path("message").asText()));
        }
        List<NovelSearchResponse.NovelSearchItem> items = parseUserNovelCards(root.path("body"), ids);
        return ResponseEntity.ok(new NovelSearchResponse(items, items.size(), 1));
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

    @GetMapping("/thumbnail-proxy")
    public ResponseEntity<byte[]> proxyThumbnail(@RequestParam String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new SecurityException(messages.get("pixiv.proxy.thumbnail-url.invalid"));
        }
        // pximg.net 子域服务作品缩略图；embed.pixiv.net 服务珍藏集封面缩略图。两者均为 Pixiv 固定 CDN。
        // 仅允许 https，避免被诱导明文出站；缩略图只需 Pixiv Referer，绝不携带任何用户 Cookie（PHPSESSID 不应外泄到 CDN）。
        String scheme = uri.getScheme();
        String host = uri.getHost();
        boolean allowed = "https".equalsIgnoreCase(scheme)
                && host != null
                && (host.endsWith(".pximg.net") || host.equals("embed.pixiv.net"));
        if (!allowed) {
            throw new SecurityException(messages.get("pixiv.proxy.thumbnail-url.host.invalid"));
        }
        byte[] bytes = proxyGetBytes(url, null);
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.setCacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePublic());
        return ResponseEntity.ok().headers(responseHeaders).body(bytes);
    }

    private byte[] proxyGetBytes(String url, String cookie) {
        HttpEntity<Void> entity = new HttpEntity<>(PixivRequestHeaders.image(cookie));
        ResponseEntity<byte[]> response = restTemplate.exchange(url, HttpMethod.GET, entity, byte[].class);
        return response.getBody() != null ? response.getBody() : new byte[0];
    }

    // ── /me 端点：基于 cookie 解析当前用户 uid，代理「我的」书签 / 关注 / 珍藏集 ─────────────

    private static final Set<String> VALID_REST = Set.of("show", "hide");

    /**
     * 从 Pixiv cookie 串里抽出登录用户的 userId。
     * <p>PHPSESSID 格式为 {@code {userId}_{随机后缀}}，下划线前缀即 userId。返回 null 表示
     * cookie 缺失或不含合法 PHPSESSID（未登录 / 已过期 / 拼装错误）。
     */
    static String extractUidFromCookie(String cookie) {
        if (cookie == null) return null;
        for (String part : cookie.split(";")) {
            String trimmed = part.trim();
            if (!trimmed.regionMatches(true, 0, "PHPSESSID=", 0, "PHPSESSID=".length())) continue;
            String value = trimmed.substring("PHPSESSID=".length());
            int us = value.indexOf('_');
            if (us <= 0) continue;
            String uid = value.substring(0, us);
            if (!uid.isEmpty() && uid.chars().allMatch(Character::isDigit)) return uid;
        }
        return null;
    }

    @GetMapping("/me/uid")
    public ResponseEntity<?> getMeUid(
            @RequestHeader(value = "X-Pixiv-Cookie", required = false) String cookie,
            HttpServletRequest request) {
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
        JsonNode b = root.path("body");
        int total = b.path("total").asInt(0);
        List<SearchResponse.SearchItem> items = new ArrayList<>();
        for (JsonNode item : b.path("works")) {
            // 私密 / 受限作品在 Pixiv 上以占位形式返回：isMasked=true，没有 title / illustType；前端按 xRestrict 高亮提示但仍可点入队列重试
            items.add(new SearchResponse.SearchItem(
                    item.path("id").asText(""),
                    item.path("title").asText(""),
                    item.path("illustType").asInt(0),
                    item.path("xRestrict").asInt(0),
                    item.path("aiType").asInt(0),
                    item.path("url").asText(""),
                    item.path("pageCount").asInt(1),
                    item.path("userId").asText(""),
                    item.path("userName").asText(""),
                    parseStringTags(item.path("tags"))
            ));
        }
        return ResponseEntity.ok(new SearchResponse(items, total, safeOffset / safeLimit + 1));
    }

    @GetMapping("/me/novel-bookmarks")
    public ResponseEntity<?> getMyNovelBookmarks(
            @RequestParam(defaultValue = "show") String rest,
            @RequestParam(required = false) String tag,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "24") int limit,
            @RequestHeader(value = "X-Pixiv-Cookie", required = false) String cookie,
            HttpServletRequest request) throws IOException {
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
            return ResponseEntity.badRequest().body(new ErrorResponse(root.path("message").asText()));
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

    @GetMapping("/me/following")
    public ResponseEntity<?> getMyFollowing(
            @RequestParam(defaultValue = "show") String rest,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "24") int limit,
            @RequestHeader(value = "X-Pixiv-Cookie", required = false) String cookie,
            HttpServletRequest request) throws IOException {
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
        JsonNode b = root.path("body");
        int total = b.path("total").asInt(0);
        List<FollowingPageResponse.FollowingUser> users = new ArrayList<>();
        for (JsonNode u : b.path("users")) {
            users.add(new FollowingPageResponse.FollowingUser(
                    u.path("userId").asText(""),
                    u.path("userName").asText(""),
                    u.path("profileImageUrl").asText(""),
                    u.path("userComment").asText(u.path("comment").asText(""))
            ));
        }
        return ResponseEntity.ok(new FollowingPageResponse(users, total, safeOffset, safeLimit));
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
        List<SearchResponse.SearchItem> items = parseFollowLatestIllusts(b);
        boolean hasNext = followLatestHasNext(b, items.size());
        return ResponseEntity.ok(new FollowLatestResponse(items, safePage, hasNext));
    }

    /**
     * 把 {@code /ajax/follow_latest/illust} 的 {@code body} 解析为按 {@code page.ids} 顺序排列的插画卡片列表。
     * 卡片详情取自 {@code thumbnails.illust[]}（按 id 建索引）；{@code page.ids} 缺失时回退为 thumbnails 自身顺序，
     * 命中不到卡片的 id（被屏蔽/已删除等）跳过。纯函数：不触网、不依赖实例状态，便于单测。
     */
    static List<SearchResponse.SearchItem> parseFollowLatestIllusts(JsonNode body) {
        List<SearchResponse.SearchItem> items = new ArrayList<>();
        if (body == null) return items;
        Map<String, JsonNode> illustById = new LinkedHashMap<>();
        for (JsonNode it : body.path("thumbnails").path("illust")) {
            String id = it.path("id").asText("");
            if (!id.isBlank()) illustById.put(id, it);
        }
        JsonNode ids = body.path("page").path("ids");
        if (ids.isArray() && !ids.isEmpty()) {
            for (JsonNode idNode : ids) {
                JsonNode it = illustById.get(idNode.asText(""));
                if (it != null) items.add(followLatestCardOf(it));
            }
        } else {
            for (JsonNode it : illustById.values()) {
                items.add(followLatestCardOf(it));
            }
        }
        return items;
    }

    private static SearchResponse.SearchItem followLatestCardOf(JsonNode item) {
        return new SearchResponse.SearchItem(
                item.path("id").asText(""),
                item.path("title").asText(""),
                item.path("illustType").asInt(0),
                item.path("xRestrict").asInt(0),
                item.path("aiType").asInt(0),
                item.path("url").asText(""),
                item.path("pageCount").asInt(1),
                item.path("userId").asText(""),
                item.path("userName").asText(""),
                parseStringTags(item.path("tags"))
        );
    }

    /**
     * 判断 follow_latest 是否还有下一页：优先用 Pixiv 自身的 {@code page.isLastPage}；缺失时退化为
     * 「当前页解析出非空作品即可能还有下一页」（请求越界时 Pixiv 返回空页，从而停止）。纯函数。
     */
    static boolean followLatestHasNext(JsonNode body, int pageItemCount) {
        if (body != null) {
            JsonNode isLast = body.path("page").path("isLastPage");
            if (isLast.isBoolean()) return !isLast.asBoolean();
        }
        return pageItemCount > 0;
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
        List<String> ids = new ArrayList<>();
        JsonNode idsNode = allRoot.path("body").path("collectionIds");
        if (idsNode.isArray()) {
            for (JsonNode n : idsNode) {
                String id = n.asText("");
                if (!id.isBlank()) ids.add(id);
            }
        }
        // collectionIds 缺失时回退到 body.collections 对象的键集
        if (ids.isEmpty()) {
            JsonNode collMap = allRoot.path("body").path("collections");
            if (collMap.isObject()) collMap.fieldNames().forEachRemaining(ids::add);
        }
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
            JsonNode works = root.path("body").path("works");
            if (works.isArray()) {
                for (JsonNode c : works) {
                    collections.add(new CollectionPageResponse.CollectionItem(
                            c.path("id").asText(""),
                            c.path("title").asText(""),
                            c.path("caption").asText(""),
                            c.path("thumbnailImageUrl").asText(""),
                            c.path("bookmarkCount").asInt(0),
                            c.path("xRestrict").asInt(0),
                            parseStringTags(c.path("tags"))
                    ));
                }
            }
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
        List<CollectionWorksResponse.Work> works = parseCollectionWorks(root.path("body"));
        return ResponseEntity.ok(new CollectionWorksResponse(works, works.size()));
    }

    /**
     * 把 {@code /ajax/collection/{id}} 的 {@code body} 解析为按珍藏集布局顺序排列的混合作品列表。
     * 顺序与 workType/workId 取自 {@code data.detail.tiles[]}，卡片详情取自 {@code thumbnails.illust[]} /
     * {@code thumbnails.novel[]}（按 id 建索引）。仅保留 {@code type=Work} 且 {@code status=Active} 的 tile，
     * 卡片缺失（已删除等）跳过。纯函数：不触网、不依赖实例状态，便于单测。
     */
    static List<CollectionWorksResponse.Work> parseCollectionWorks(JsonNode body) {
        List<CollectionWorksResponse.Work> works = new ArrayList<>();
        if (body == null) return works;
        Map<String, JsonNode> illustById = new LinkedHashMap<>();
        for (JsonNode it : body.path("thumbnails").path("illust")) {
            illustById.put(it.path("id").asText(""), it);
        }
        Map<String, JsonNode> novelById = new LinkedHashMap<>();
        for (JsonNode it : body.path("thumbnails").path("novel")) {
            novelById.put(it.path("id").asText(""), it);
        }
        JsonNode tiles = body.path("data").path("detail").path("tiles");
        if (!tiles.isArray()) return works;
        for (JsonNode tile : tiles) {
            if (!"Work".equals(tile.path("type").asText(""))) continue;
            if (!"Active".equals(tile.path("status").asText("Active"))) continue;
            String workType = tile.path("workType").asText("");
            String workId = tile.path("workId").asText("");
            if (workId.isBlank()) continue;
            if ("novel".equals(workType)) {
                JsonNode it = novelById.get(workId);
                if (it == null) continue;
                works.add(new CollectionWorksResponse.Work(
                        "novel",
                        workId,
                        it.path("title").asText(""),
                        0,
                        it.path("xRestrict").asInt(0),
                        it.path("aiType").asInt(0),
                        extractNovelCoverUrl(it),
                        1,
                        it.path("userId").asText(""),
                        it.path("userName").asText(""),
                        parseStringTags(it.path("tags")),
                        it.path("bookmarkCount").asInt(-1),
                        it.path("wordCount").asInt(0),
                        it.path("textLength").asInt(it.path("characterCount").asInt(0)),
                        it.path("isOriginal").asBoolean(false)
                ));
            } else {
                JsonNode it = illustById.get(workId);
                if (it == null) continue;
                works.add(new CollectionWorksResponse.Work(
                        "illust",
                        workId,
                        it.path("title").asText(""),
                        it.path("illustType").asInt(0),
                        it.path("xRestrict").asInt(0),
                        it.path("aiType").asInt(0),
                        it.path("url").asText(""),
                        it.path("pageCount").asInt(1),
                        it.path("userId").asText(""),
                        it.path("userName").asText(""),
                        parseStringTags(it.path("tags")),
                        it.path("bookmarkCount").asInt(-1),
                        0,
                        0,
                        false
                ));
            }
        }
        return works;
    }
}

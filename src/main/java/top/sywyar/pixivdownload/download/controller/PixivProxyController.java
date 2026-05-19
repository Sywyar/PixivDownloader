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
import top.sywyar.pixivdownload.common.UuidUtils;
import top.sywyar.pixivdownload.download.db.TagDto;
import top.sywyar.pixivdownload.download.response.*;
import top.sywyar.pixivdownload.novel.NovelCoverUrlResolver;
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

    private static final String PIXIV_REFERER = "https://www.pixiv.net/";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";

    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
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
        HttpHeaders headers = new HttpHeaders();
        headers.set("Referer", PIXIV_REFERER);
        headers.set("User-Agent", USER_AGENT);
        if (cookie != null && !cookie.trim().isEmpty()) {
            headers.set("Cookie", cookie);
        }

        HttpEntity<Void> entity = new HttpEntity<>(headers);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
        return response.getBody();
    }

    private String proxyGetUri(URI uri, String cookie) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Referer", PIXIV_REFERER);
        headers.set("User-Agent", USER_AGENT);
        if (cookie != null && !cookie.trim().isEmpty()) {
            headers.set("Cookie", cookie);
        }
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        ResponseEntity<String> response = restTemplate.exchange(uri, HttpMethod.GET, entity, String.class);
        return response.getBody();
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

    private int resolveSearchFillLimitPage() {
        if (!"multi".equals(setupService.getMode())) {
            return 0;
        }
        return Math.max(0, multiModeConfig.getLimitPage());
    }

    /**
     * Pixiv 搜索结果 item 的 tags 为字符串数组（如 ["tag1","tag2"]）。
     * 解析为去空白、去重、保序的字符串列表，供前端做客户端标签精确/模糊筛选。
     */
    private List<String> parseStringTags(JsonNode tagsNode) {
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
            int startParam, int endParam, int perPage, RangePageFetcher fetcher) throws IOException {
        int startPage = Math.max(1, Math.min(startParam, endParam));
        int endRequested = Math.max(startPage, Math.max(startParam, endParam));
        int requestedPages = endRequested - startPage + 1;
        int limitPage = resolveSearchFillLimitPage();
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
            return ResponseEntity.ok(buildSearchRange(startPage, endPage, 60, p -> {
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
            return ResponseEntity.ok(buildSearchRange(startPage, endPage, 24, p -> {
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
                        seriesOrder
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
            return null;
        }
    }

    @GetMapping("/thumbnail-proxy")
    public ResponseEntity<byte[]> proxyThumbnail(
            @RequestParam String url,
            @RequestHeader(value = "X-Pixiv-Cookie", required = false) String cookie) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new SecurityException(messages.get("pixiv.proxy.thumbnail-url.invalid"));
        }
        String host = uri.getHost();
        if (host == null || !host.endsWith(".pximg.net")) {
            throw new SecurityException(messages.get("pixiv.proxy.thumbnail-url.host.invalid"));
        }
        byte[] bytes = proxyGetBytes(url, cookie);
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.setCacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePublic());
        return ResponseEntity.ok().headers(responseHeaders).body(bytes);
    }

    private byte[] proxyGetBytes(String url, String cookie) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Referer", PIXIV_REFERER);
        headers.set("User-Agent", USER_AGENT);
        if (cookie != null && !cookie.trim().isEmpty()) {
            headers.set("Cookie", cookie);
        }
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        ResponseEntity<byte[]> response = restTemplate.exchange(url, HttpMethod.GET, entity, byte[].class);
        return response.getBody() != null ? response.getBody() : new byte[0];
    }
}

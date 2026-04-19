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
import top.sywyar.pixivdownload.common.UuidUtils;
import top.sywyar.pixivdownload.download.db.TagDto;
import top.sywyar.pixivdownload.download.response.*;
import top.sywyar.pixivdownload.quota.MultiModeConfig;
import top.sywyar.pixivdownload.quota.UserQuotaService;
import top.sywyar.pixivdownload.quota.response.ProxyRateLimitResponse;
import top.sywyar.pixivdownload.setup.SetupService;

import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        String uuid = UuidUtils.extractExistingUuid(request);
        if (uuid == null) {
            return ResponseEntity.status(401).body(new ErrorResponse("missing user UUID"));
        }
        if (multiModeConfig.getQuota().isEnabled()
                && !userQuotaService.checkAndReserveProxy(uuid)) {
            int max = multiModeConfig.getQuota().getMaxArtworks();
            int hours = multiModeConfig.getQuota().getResetPeriodHours();
            return ResponseEntity.status(429).body(new ProxyRateLimitResponse(
                    "proxy rate limit exceeded", max, hours));
        }
        return null;
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
        return ResponseEntity.ok(new ArtworkMetaResponse(
                b.path("illustType").asInt(0),
                b.path("illustTitle").asText(""),
                b.path("xRestrict").asInt(0),
                b.path("aiType").asInt(0) >= 2,
                b.path("description").asText(""),
                extractTags(b)
        ));
    }

    private static List<TagDto> extractTags(JsonNode body) {
        JsonNode tagsArr = body.path("tags").path("tags");
        if (!tagsArr.isArray() || tagsArr.isEmpty()) {
            return List.of();
        }
        List<TagDto> out = new ArrayList<>();
        for (JsonNode t : tagsArr) {
            String name = t.path("tag").asText("");
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

    @GetMapping("/artwork/{artworkId}/pages")
    public ResponseEntity<?> getArtworkPages(
            @PathVariable String artworkId,
            @RequestHeader(value = "X-Pixiv-Cookie", required = false) String cookie,
            HttpServletRequest request) throws IOException {
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
        if (!VALID_ORDERS.contains(order)) {
            return ResponseEntity.badRequest().body(new ErrorResponse("invalid order parameter: " + order));
        }
        if (!VALID_MODES.contains(mode)) {
            return ResponseEntity.badRequest().body(new ErrorResponse("invalid mode parameter: " + mode));
        }
        if (!VALID_S_MODES.contains(sMode)) {
            return ResponseEntity.badRequest().body(new ErrorResponse("invalid sMode parameter: " + sMode));
        }
        if (page < 1) page = 1;
        URI searchUri = UriComponentsBuilder
                .fromUriString("https://www.pixiv.net/ajax/search/artworks/{word}")
                .queryParam("word", "{word}")
                .queryParam("order", order)
                .queryParam("mode", mode)
                .queryParam("type", "illust_and_ugoira")
                .queryParam("s_mode", sMode)
                .queryParam("p", page)
                .queryParam("lang", "zh")
                .buildAndExpand(Map.of("word", word))
                .encode()
                .toUri();
        String body = proxyGetUri(searchUri, cookie);
        JsonNode root = objectMapper.readTree(body);
        if (root.path("error").asBoolean(false)) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse(root.path("message").asText()));
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
                    item.path("userName").asText("")
            ));
        }
        return ResponseEntity.ok(new SearchResponse(items, total, page));
    }

    @GetMapping("/thumbnail-proxy")
    public ResponseEntity<byte[]> proxyThumbnail(
            @RequestParam String url,
            @RequestHeader(value = "X-Pixiv-Cookie", required = false) String cookie) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new SecurityException("malformed thumbnail URL");
        }
        String host = uri.getHost();
        if (host == null || !host.endsWith(".pximg.net")) {
            throw new SecurityException("thumbnail URL host must be a pximg.net subdomain");
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

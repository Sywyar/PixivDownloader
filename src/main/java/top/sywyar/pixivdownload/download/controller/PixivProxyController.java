package top.sywyar.pixivdownload.download.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHost;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.sywyar.pixivdownload.config.ProxyConfig;
import top.sywyar.pixivdownload.quota.MultiModeConfig;
import top.sywyar.pixivdownload.quota.UserQuotaService;
import top.sywyar.pixivdownload.setup.SetupService;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 代理 Pixiv AJAX API，供 pixiv-batch.html 使用。
 * 前端通过 X-Pixiv-Cookie 请求头传入 Cookie，后端附带 Cookie 访问 Pixiv。
 */
@RestController
@RequestMapping("/api/pixiv")
@Slf4j
public class PixivProxyController {

    private static final String PIXIV_REFERER = "https://www.pixiv.net/";
    private static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private ProxyConfig proxyConfig;

    @Autowired
    private SetupService setupService;

    @Autowired
    private UserQuotaService userQuotaService;

    @Autowired
    private MultiModeConfig multiModeConfig;

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
        String uuid = extractExistingUuid(request);
        if (uuid == null) {
            return ResponseEntity.status(401).body(Map.of("error", "missing user UUID"));
        }
        if (multiModeConfig.getQuota().isEnabled()
                && !userQuotaService.checkAndReserveProxy(uuid)) {
            int max = multiModeConfig.getQuota().getMaxArtworks();
            int hours = multiModeConfig.getQuota().getResetPeriodHours();
            return ResponseEntity.status(429).body(Map.of(
                    "error", "proxy rate limit exceeded",
                    "maxRequests", max,
                    "windowHours", hours
            ));
        }
        return null;
    }

    /** 仅读取已存在的 UUID，不自动生成 */
    private String extractExistingUuid(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if ("pixiv_user_id".equals(c.getName()) && c.getValue() != null
                        && !c.getValue().isBlank()) {
                    return c.getValue();
                }
            }
        }
        String headerUuid = request.getHeader("X-User-UUID");
        if (headerUuid != null && !headerUuid.isBlank() && UUID_PATTERN.matcher(headerUuid).matches()) {
            return headerUuid;
        }
        return null;
    }

    private String proxyGet(String url, String cookie) throws Exception {
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(15000)
                .setSocketTimeout(30000)
                .build();
        var clientBuilder = HttpClients.custom().setDefaultRequestConfig(requestConfig);
        if (proxyConfig.isEnabled()) {
            clientBuilder.setProxy(new HttpHost(proxyConfig.getHost(), proxyConfig.getPort()));
        }
        try (CloseableHttpClient client = clientBuilder.build()) {
            HttpGet request = new HttpGet(url);
            request.setHeader("Referer", PIXIV_REFERER);
            request.setHeader("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            if (cookie != null && !cookie.trim().isEmpty()) {
                request.setHeader("Cookie", cookie);
            }
            try (CloseableHttpResponse response = client.execute(request)) {
                return new String(response.getEntity().getContent().readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }

    @GetMapping("/user/{userId}/artworks")
    public ResponseEntity<?> getUserArtworks(
            @PathVariable String userId,
            @RequestHeader(value = "X-Pixiv-Cookie", required = false) String cookie,
            HttpServletRequest request) {
        ResponseEntity<?> deny = checkMultiModeAccess(request);
        if (deny != null) return deny;
        try {
            String body = proxyGet(
                    "https://www.pixiv.net/ajax/user/" + userId + "/profile/all", cookie);
            JsonNode root = objectMapper.readTree(body);
            if (root.path("error").asBoolean(false)) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", root.path("message").asText()));
            }
            JsonNode b = root.path("body");
            List<String> ids = new ArrayList<>();
            b.path("illusts").fieldNames().forEachRemaining(ids::add);
            b.path("manga").fieldNames().forEachRemaining(ids::add);
            ids.sort((a, c2) -> Long.compare(Long.parseLong(c2), Long.parseLong(a)));
            return ResponseEntity.ok(Map.of("ids", ids));
        } catch (Exception e) {
            log.error("获取用户作品列表失败: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/user/{userId}/meta")
    public ResponseEntity<?> getUserMeta(
            @PathVariable String userId,
            @RequestHeader(value = "X-Pixiv-Cookie", required = false) String cookie,
            HttpServletRequest request) {
        ResponseEntity<?> deny = checkMultiModeAccess(request);
        if (deny != null) return deny;
        try {
            String body = proxyGet(
                    "https://www.pixiv.net/ajax/user/" + userId + "?lang=zh", cookie);
            JsonNode root = objectMapper.readTree(body);
            if (root.path("error").asBoolean(false)) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", root.path("message").asText()));
            }
            String name = root.path("body").path("name").asText();
            return ResponseEntity.ok(Map.of("name", name, "userId", userId));
        } catch (Exception e) {
            log.error("获取用户信息失败: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/artwork/{artworkId}/meta")
    public ResponseEntity<?> getArtworkMeta(
            @PathVariable String artworkId,
            @RequestHeader(value = "X-Pixiv-Cookie", required = false) String cookie,
            HttpServletRequest request) {
        ResponseEntity<?> deny = checkMultiModeAccess(request);
        if (deny != null) return deny;
        try {
            String body = proxyGet(
                    "https://www.pixiv.net/ajax/illust/" + artworkId, cookie);
            JsonNode root = objectMapper.readTree(body);
            if (root.path("error").asBoolean(false)) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", root.path("message").asText()));
            }
            JsonNode b = root.path("body");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("illustType", b.path("illustType").asInt(0));
            result.put("illustTitle", b.path("illustTitle").asText(""));
            result.put("xRestrict", b.path("xRestrict").asInt(0));
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("获取作品信息失败: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/artwork/{artworkId}/pages")
    public ResponseEntity<?> getArtworkPages(
            @PathVariable String artworkId,
            @RequestHeader(value = "X-Pixiv-Cookie", required = false) String cookie,
            HttpServletRequest request) {
        ResponseEntity<?> deny = checkMultiModeAccess(request);
        if (deny != null) return deny;
        try {
            String body = proxyGet(
                    "https://www.pixiv.net/ajax/illust/" + artworkId + "/pages", cookie);
            JsonNode root = objectMapper.readTree(body);
            if (root.path("error").asBoolean(false)) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", root.path("message").asText()));
            }
            List<String> urls = new ArrayList<>();
            for (JsonNode page : root.path("body")) {
                String origUrl = page.path("urls").path("original").asText("");
                if (!origUrl.isEmpty()) urls.add(origUrl);
            }
            return ResponseEntity.ok(Map.of("urls", urls));
        } catch (Exception e) {
            log.error("获取作品页面失败: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/artwork/{artworkId}/ugoira")
    public ResponseEntity<?> getUgoiraMeta(
            @PathVariable String artworkId,
            @RequestHeader(value = "X-Pixiv-Cookie", required = false) String cookie,
            HttpServletRequest request) {
        ResponseEntity<?> deny = checkMultiModeAccess(request);
        if (deny != null) return deny;
        try {
            String body = proxyGet(
                    "https://www.pixiv.net/ajax/illust/" + artworkId + "/ugoira_meta", cookie);
            JsonNode root = objectMapper.readTree(body);
            if (root.path("error").asBoolean(false)) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", root.path("message").asText()));
            }
            JsonNode b = root.path("body");
            String zipUrl = b.path("originalSrc").asText("");
            if (zipUrl.isEmpty()) zipUrl = b.path("src").asText("");
            List<Integer> delays = new ArrayList<>();
            for (JsonNode frame : b.path("frames")) {
                delays.add(frame.path("delay").asInt(100));
            }
            return ResponseEntity.ok(Map.of("zipUrl", zipUrl, "delays", delays));
        } catch (Exception e) {
            log.error("获取动图信息失败: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }
}

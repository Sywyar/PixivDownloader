package top.sywyar.pixivdownload.download;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class PixivBookmarkService {

    private static final String PIXIV_HOME   = "https://www.pixiv.net/";
    private static final String BOOKMARK_URL = "https://www.pixiv.net/ajax/illusts/bookmarks/add";
    private static final String USER_AGENT   =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";
    // 新版 Pixiv (Next.js)：JSON 内嵌于 JS 字符串，引号被转义为 \"  →  token\":\"<value>\"
    private static final Pattern CSRF_PATTERN_ESCAPED   = Pattern.compile("token\\\\\":\\\\\"([^\\\\\"]+)\\\\\"");
    // 旧版 / 直接 JSON 响应：标准格式  →  "token":"<value>"
    private static final Pattern CSRF_PATTERN_PLAIN     = Pattern.compile("\"token\":\"([^\"]+)\"");
    // HTML 实体转义格式  →  &quot;token&quot;:&quot;<value>&quot;
    private static final Pattern CSRF_PATTERN_HTML      = Pattern.compile("&quot;token&quot;\\s*:\\s*&quot;([^&]+)&quot;");

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public PixivBookmarkService(
            @Qualifier("restTemplate") RestTemplate restTemplate,
            ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 收藏作品（best-effort）。任何异常只记录日志，不向调用方抛出。
     *
     * @param artworkId 作品 ID
     * @param cookie    用户的 Pixiv Cookie 字符串
     */
    public void bookmarkArtwork(Long artworkId, String cookie) {
        if (cookie == null || cookie.isBlank()) {
            log.warn("收藏跳过：作品 {}，未提供 Cookie", artworkId);
            return;
        }
        try {
            String csrfToken = fetchCsrfToken(cookie);
            postBookmark(artworkId, cookie, csrfToken);
            log.info("收藏成功：作品 {}", artworkId);
        } catch (Exception e) {
            log.warn("收藏失败（不影响下载结果）：作品 {}，原因：{}", artworkId, e.getMessage());
        }
    }

    private String fetchCsrfToken(String cookie) {
        HttpHeaders headers = buildBaseHeaders(cookie);
        ResponseEntity<String> response =
                restTemplate.exchange(PIXIV_HOME, HttpMethod.GET, new HttpEntity<>(headers), String.class);
        String body = response.getBody();
        if (body == null) {
            throw new IllegalStateException("Pixiv 主页响应为空，无法提取 CSRF token");
        }

        // 1. 新版 Pixiv Next.js：引号转义格式  token\":\"abc...\"
        Matcher m = CSRF_PATTERN_ESCAPED.matcher(body);
        if (m.find()) {
            return m.group(1);
        }

        // 2. 标准 JSON 格式  "token":"abc..."
        Matcher mPlain = CSRF_PATTERN_PLAIN.matcher(body);
        if (mPlain.find()) {
            return mPlain.group(1);
        }

        // 3. HTML 实体转义格式  &quot;token&quot;:&quot;abc...&quot;
        Matcher mHtml = CSRF_PATTERN_HTML.matcher(body);
        if (mHtml.find()) {
            return mHtml.group(1);
        }

        // --- 诊断：帮助定位真正原因 ---

        // 诊断 A：Cloudflare 拦截
        if (body.contains("cf-browser-verification") || body.contains("cf-turnstile") || body.contains("cloudflare")) {
            throw new IllegalStateException("被 Cloudflare 人机验证拦截，请检查代理 IP 或更新伪装 Header");
        }

        // 诊断 B：未登录（新版 Pixiv Next.js 通过 isLoggedIn 字段判断）
        if (body.contains("\"isLoggedIn\":false") || body.contains("login:'no'")) {
            throw new IllegalStateException("Cookie 已过期或格式错误，请重新从浏览器获取");
        }

        // 诊断 C：页面结构未知变化
        log.warn("无法匹配 CSRF token，HTML 截取前 500 字符: \n{}", body.substring(0, Math.min(body.length(), 500)));
        throw new IllegalStateException("无法从 Pixiv 主页中提取 CSRF token（页面 DOM 结构可能已改变）");
    }

    private record BookmarkRequest(String illust_id, int restrict, String comment, List<String> tags) {}

    private void postBookmark(Long artworkId, String cookie, String csrfToken) throws Exception {
        HttpHeaders headers = buildBaseHeaders(cookie);
        headers.set("x-csrf-token", csrfToken);
        headers.setContentType(MediaType.APPLICATION_JSON);

        BookmarkRequest body = new BookmarkRequest(String.valueOf(artworkId), 0, "", List.of());
        String bodyJson = objectMapper.writeValueAsString(body);

        ResponseEntity<String> response = restTemplate.exchange(
                BOOKMARK_URL, HttpMethod.POST, new HttpEntity<>(bodyJson, headers), String.class);

        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("收藏 API 返回非 2xx: " + response.getStatusCode());
        }
        JsonNode root = objectMapper.readTree(response.getBody());
        if (root.path("error").asBoolean(false)) {
            throw new IllegalStateException("收藏 API 返回错误: " + root.path("message").asText());
        }
    }

    private HttpHeaders buildBaseHeaders(String cookie) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Cookie",     cookie);
        headers.set("Referer",    PIXIV_HOME);
        headers.set("User-Agent", USER_AGENT);

        // 模拟浏览器请求头，降低被 WAF 拦截的概率
        headers.set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8");
        headers.set("Accept-Language", "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7,ja;q=0.6");
        headers.set("Sec-Ch-Ua", "\"Chromium\";v=\"122\", \"Not(A:Brand\";v=\"24\", \"Google Chrome\";v=\"122\"");
        headers.set("Sec-Ch-Ua-Mobile", "?0");
        headers.set("Sec-Ch-Ua-Platform", "\"Windows\"");
        headers.set("Sec-Fetch-Dest", "document");
        headers.set("Sec-Fetch-Mode", "navigate");
        headers.set("Sec-Fetch-Site", "none");

        return headers;
    }
}

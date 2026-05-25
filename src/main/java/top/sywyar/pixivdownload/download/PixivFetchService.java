package top.sywyar.pixivdownload.download;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务端 Pixiv AJAX 抓取与作品发现服务。
 *
 * <p>封装两类能力：
 * <ol>
 *   <li>底层代理拉取（{@link #proxyGet} / {@link #proxyGetUri}）—— 经统一出站代理附 Cookie 访问 Pixiv，
 *       按 UTF-8 字节解析（遵循「禁止请求 String.class」约束）。</li>
 *   <li>高层作品发现 / 元数据解析（{@link #discoverUserArtworkIds} / {@link #fetchArtworkMeta} /
 *       {@link #resolveImageUrls} / {@link #resolveUgoira}）—— 把油猴脚本在浏览器里做的「ID 发现 + 原图 URL
 *       解析」搬到服务端，供没有浏览器在场的后台调度（{@code schedule/}）复用。</li>
 * </ol>
 *
 * <p>本服务不做鉴权 / 限流 / 访客可见性裁剪：那些是 {@code PixivProxyController}（web 链路）的职责。
 * 后台调度以管理员身份、用管理员快照 Cookie 调用本服务，不经过 controller。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PixivFetchService {

    private static final String PIXIV_REFERER = "https://www.pixiv.net/";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    // ---- 底层代理拉取 -------------------------------------------------------

    /** 经代理 GET 一个 Pixiv URL，按 UTF-8 解析为字符串。 */
    public String proxyGet(String url, String cookie) {
        return exchange(restTemplate.exchange(url, HttpMethod.GET, buildEntity(cookie), byte[].class));
    }

    /** 与 {@link #proxyGet} 相同，但接受已构造的 {@link URI}（调用方负责唯一一次编码）。 */
    public String proxyGetUri(URI uri, String cookie) {
        return exchange(restTemplate.exchange(uri, HttpMethod.GET, buildEntity(cookie), byte[].class));
    }

    private HttpEntity<Void> buildEntity(String cookie) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Referer", PIXIV_REFERER);
        headers.set("User-Agent", USER_AGENT);
        if (cookie != null && !cookie.trim().isEmpty()) {
            headers.set("Cookie", cookie);
        }
        return new HttpEntity<>(headers);
    }

    private static String exchange(ResponseEntity<byte[]> response) {
        byte[] body = response.getBody();
        return body != null ? new String(body, StandardCharsets.UTF_8) : null;
    }

    // ---- 高层作品发现 / 元数据解析（供后台调度复用） ------------------------

    /**
     * 发现某画师的全部插画 + 漫画作品 ID，按 ID 倒序（新作在前）。
     *
     * @throws PixivFetchException Pixiv 返回 error（含 Cookie 失效 / 被限制）时抛出，供调度区分鉴权失效
     */
    public List<String> discoverUserArtworkIds(String userId, String cookie) throws IOException {
        JsonNode body = requireBody(proxyGet(
                "https://www.pixiv.net/ajax/user/" + userId + "/profile/all", cookie));
        List<String> ids = new ArrayList<>();
        body.path("illusts").fieldNames().forEachRemaining(ids::add);
        body.path("manga").fieldNames().forEachRemaining(ids::add);
        ids.sort((a, b) -> Long.compare(Long.parseLong(b), Long.parseLong(a)));
        return ids;
    }

    /** 拉取单作品元数据（标题 / R18 / AI / 作者 / 系列），供落库与文件名模板使用。 */
    public ArtworkMeta fetchArtworkMeta(String artworkId, String cookie) throws IOException {
        JsonNode b = requireBody(proxyGet(
                "https://www.pixiv.net/ajax/illust/" + artworkId, cookie));
        Long seriesId = null;
        Long seriesOrder = null;
        JsonNode nav = b.path("seriesNavData");
        if (nav.isObject()) {
            long sid = nav.path("seriesId").asLong(0);
            if (sid > 0) {
                seriesId = sid;
                seriesOrder = nav.path("order").asLong(0);
            }
        }
        return new ArtworkMeta(
                b.path("illustType").asInt(0),
                b.path("illustTitle").asText(""),
                b.path("xRestrict").asInt(0),
                b.path("aiType").asInt(0) >= 2,
                parsePositiveLong(b.path("userId").asText(null)),
                b.path("userName").asText(""),
                seriesId,
                seriesOrder
        );
    }

    /** 解析单作品的原图 URL 列表（插画 / 漫画）。 */
    public List<String> resolveImageUrls(String artworkId, String cookie) throws IOException {
        JsonNode body = requireBody(proxyGet(
                "https://www.pixiv.net/ajax/illust/" + artworkId + "/pages", cookie));
        List<String> urls = new ArrayList<>();
        for (JsonNode page : body) {
            String orig = page.path("urls").path("original").asText("");
            if (!orig.isEmpty()) urls.add(orig);
        }
        return urls;
    }

    /** 解析动图（ugoira）的帧 ZIP URL 与每帧延迟。 */
    public UgoiraInfo resolveUgoira(String artworkId, String cookie) throws IOException {
        JsonNode b = requireBody(proxyGet(
                "https://www.pixiv.net/ajax/illust/" + artworkId + "/ugoira_meta", cookie));
        String zipUrl = b.path("originalSrc").asText("");
        if (zipUrl.isEmpty()) zipUrl = b.path("src").asText("");
        List<Integer> delays = new ArrayList<>();
        for (JsonNode frame : b.path("frames")) {
            delays.add(frame.path("delay").asInt(100));
        }
        return new UgoiraInfo(zipUrl, delays);
    }

    /**
     * 按关键词搜索发现作品 ID（插画 + 漫画 + 动图），翻 {@code maxPages} 页去重后返回（按出现顺序）。
     *
     * <p>遵循「URL 编码只能做一次」约束：{@code word} 以未编码原始形态交给
     * {@link UriComponentsBuilder} 统一编码。
     *
     * @param order Pixiv 排序（如 {@code date_d}）；{@code mode} 分级（如 {@code all}）；{@code sMode} 标签匹配（如 {@code s_tag}）
     */
    public List<String> discoverSearchArtworkIds(String word, String order, String mode,
                                                 String sMode, int maxPages, String cookie) throws IOException {
        LinkedHashMap<String, Boolean> ids = new LinkedHashMap<>();
        int pages = Math.max(1, maxPages);
        for (int p = 1; p <= pages; p++) {
            URI uri = UriComponentsBuilder
                    .fromUriString("https://www.pixiv.net/ajax/search/artworks/{word}")
                    .queryParam("word", "{word}")
                    .queryParam("order", order)
                    .queryParam("mode", mode)
                    .queryParam("type", "illust_and_ugoira")
                    .queryParam("s_mode", sMode)
                    .queryParam("p", p)
                    .queryParam("lang", "zh")
                    .buildAndExpand(Map.of("word", word))
                    .encode()
                    .toUri();
            JsonNode body = requireBody(proxyGetUri(uri, cookie));
            JsonNode data = body.path("illustManga").path("data");
            if (!data.isArray() || data.isEmpty()) break;
            int before = ids.size();
            for (JsonNode item : data) {
                String id = item.path("id").asText("");
                if (!id.isEmpty()) ids.put(id, Boolean.TRUE);
            }
            int total = body.path("illustManga").path("total").asInt(0);
            if (total > 0 && ids.size() >= total) break;
            if (ids.size() == before) break;
        }
        return new ArrayList<>(ids.keySet());
    }

    /**
     * 发现某漫画系列内的全部作品 ID，按系列内顺序（order 升序）。
     *
     * <p>逐页拉取 {@code /ajax/series/{id}?p=N}，从 {@code body.page.series[].workId} 收集成员
     * （这是系列成员的权威列表，带 order），直到某页无成员为止。
     */
    public List<String> discoverSeriesArtworkIds(String seriesId, String cookie) throws IOException {
        record Member(String id, int order) {}
        List<Member> members = new ArrayList<>();
        for (int p = 1; p <= 200; p++) {
            URI uri = UriComponentsBuilder
                    .fromUriString("https://www.pixiv.net/ajax/series/{seriesId}")
                    .queryParam("p", p)
                    .queryParam("lang", "zh")
                    .buildAndExpand(Map.of("seriesId", seriesId))
                    .encode()
                    .toUri();
            JsonNode body = requireBody(proxyGetUri(uri, cookie));
            JsonNode arr = body.path("page").path("series");
            if (!arr.isArray() || arr.isEmpty()) break;
            for (JsonNode entry : arr) {
                String id = entry.path("workId").asText("");
                if (!id.isEmpty()) members.add(new Member(id, entry.path("order").asInt(0)));
            }
        }
        LinkedHashMap<String, Boolean> ids = new LinkedHashMap<>();
        members.stream()
                .sorted((a, b) -> Integer.compare(a.order(), b.order()))
                .forEach(m -> ids.put(m.id(), Boolean.TRUE));
        return new ArrayList<>(ids.keySet());
    }

    /** 解析 Pixiv AJAX 响应、剥出 {@code body}；{@code error=true} 时抛 {@link PixivFetchException}。 */
    private JsonNode requireBody(String json) throws IOException {
        JsonNode root = objectMapper.readTree(json);
        if (root.path("error").asBoolean(false)) {
            throw new PixivFetchException(root.path("message").asText(""));
        }
        return root.path("body");
    }

    private static Long parsePositiveLong(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 单作品发现 / 解析结果的精简视图。 */
    public record ArtworkMeta(int illustType, String title, int xRestrict, boolean ai,
                              Long authorId, String authorName, Long seriesId, Long seriesOrder) {
        /** illustType==2 为动图（ugoira）。 */
        public boolean isUgoira() {
            return illustType == 2;
        }
    }

    /** 动图帧信息。 */
    public record UgoiraInfo(String zipUrl, List<Integer> delays) {
    }

    /** Pixiv AJAX 以 {@code error=true} 返回时抛出（常见于 Cookie 失效 / 受限作品需登录）。 */
    public static class PixivFetchException extends RuntimeException {
        public PixivFetchException(String message) {
            super(message);
        }
    }
}

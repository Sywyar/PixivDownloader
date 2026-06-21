package top.sywyar.pixivdownload.download;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import top.sywyar.pixivdownload.common.PixivRequestHeaders;
import top.sywyar.pixivdownload.core.db.TagDto;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /** PHPSESSID 形如 {@code {userId}_{session}}：下划线前缀即非敏感 Pixiv userId（账号私有来源发现需要它取 uid）。 */
    private static final Pattern PHPSESSID_PATTERN = Pattern.compile("PHPSESSID=([^;\\s]+)");
    /** 账号收藏分页每页条数（Pixiv 上限 100）。 */
    private static final int BOOKMARK_PAGE_LIMIT = 100;
    /** 「已关注用户的新作」逐页安全上限（feed 窗口本就有限，仅作兜底防御）。 */
    private static final int FOLLOW_LATEST_MAX_PAGES = 100;

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
        return new HttpEntity<>(PixivRequestHeaders.ajax(cookie));
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

    /**
     * 发现某画师已完成并公开的「约稿作品」（リクエスト 成品）ID，按 ID 倒序（新作在前）。
     * 约稿成品本质是普通插画，拿到 ID 后复用既有插画下载 / 判重管线。
     *
     * @throws PixivFetchException Pixiv 返回 error（含 Cookie 失效 / 被限制）时抛出，供调度区分鉴权失效
     */
    public List<String> discoverUserRequestArtworkIds(String userId, String cookie) throws IOException {
        JsonNode body = requireBody(proxyGet(
                "https://www.pixiv.net/ajax/commission/page/users/" + userId + "/request?lang=zh", cookie));
        return parseRequestArtworkIds(body);
    }

    /**
     * 把 {@code /ajax/commission/page/users/{id}/request/creator} 的 {@code body} 解析为约稿成品的插画 ID 列表。
     * 已完成约稿条目以 {@code postWork.postWorkId} 承载成品作品 ID（进行中的约稿无 postWork）；递归收集 {@code body}
     * 内全部 {@code postWork.postWorkId}（去重、过滤空 / 非正数），按 ID 倒序。纯函数，便于单测。
     */
    static List<String> parseRequestArtworkIds(JsonNode body) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        collectPostWorkIds(body, ids);
        List<String> result = new ArrayList<>(ids);
        result.sort((a, b) -> Long.compare(Long.parseLong(b), Long.parseLong(a)));
        return result;
    }

    private static void collectPostWorkIds(JsonNode node, Set<String> out) {
        if (node == null) return;
        if (node.isObject()) {
            JsonNode postWork = node.get("postWork");
            if (postWork != null && postWork.isObject() && parsePositiveLong(postWork.path("postWorkId").asText("")) != null) {
                out.add(postWork.path("postWorkId").asText(""));
            }
            node.fields().forEachRemaining(e -> collectPostWorkIds(e.getValue(), out));
        } else if (node.isArray()) {
            for (JsonNode child : node) collectPostWorkIds(child, out);
        }
    }

    /**
     * 拉取单作品元数据（标题 / R18 / AI / 作者 / 系列 / 收藏数 / 页数 / 标签），
     * 供落库、文件名模板与计划任务的服务端筛选使用。
     */
    public ArtworkMeta fetchArtworkMeta(String artworkId, String cookie) throws IOException {
        return fetchArtworkMetaCapture(artworkId, cookie).meta();
    }

    /**
     * 同 {@link #fetchArtworkMeta}，但<b>额外保留</b>原始 {@code /ajax/illust/{id}} body，
     * 供 meta 桥在下载已抓 body 上归一化 sidecar + 列投影（零额外 Pixiv 请求）。
     */
    public ArtworkMetaCapture fetchArtworkMetaCapture(String artworkId, String cookie) throws IOException {
        JsonNode b = requireBody(proxyGet(
                "https://www.pixiv.net/ajax/illust/" + artworkId, cookie));
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
        ArtworkMeta meta = new ArtworkMeta(
                b.path("illustType").asInt(0),
                b.path("illustTitle").asText(""),
                b.path("xRestrict").asInt(0),
                b.path("aiType").asInt(0) >= 2,
                parsePositiveLong(b.path("userId").asText(null)),
                b.path("userName").asText(""),
                seriesId,
                seriesOrder,
                b.path("bookmarkCount").asInt(-1),
                b.path("pageCount").asInt(0),
                extractTags(b),
                b.path("description").asText(""),
                seriesTitle
        );
        return new ArtworkMetaCapture(meta, b);
    }

    /**
     * 拉取插画 / 漫画系列的富信息（简介 + 封面 URL），供计划任务下载时与 web 链路一致地补齐系列元数据。
     * 简介取 {@code body.illustSeries[0].caption}、封面取 {@code cover.urls} 最优尺寸。
     */
    public IllustSeriesMeta fetchIllustSeriesMeta(long seriesId, String cookie) throws IOException {
        JsonNode b = requireBody(proxyGet(
                "https://www.pixiv.net/ajax/series/" + seriesId + "?p=1&lang=zh", cookie));
        JsonNode arr = b.path("illustSeries");
        if (arr.isArray() && !arr.isEmpty()) {
            JsonNode s = arr.get(0);
            return new IllustSeriesMeta(s.path("caption").asText(""), extractSeriesCoverUrl(s));
        }
        return new IllustSeriesMeta("", "");
    }

    /**
     * 拉取小说系列的富信息（简介 + 封面 URL + 系列标签），供计划任务下载时与 web 链路一致地补齐系列元数据。
     * 取 {@code /ajax/novel/series/{id}} 的 {@code body.caption} / 封面 / tags。
     */
    public NovelSeriesMeta fetchNovelSeriesMeta(long seriesId, String cookie) throws IOException {
        JsonNode b = requireBody(proxyGet(
                "https://www.pixiv.net/ajax/novel/series/" + seriesId + "?lang=zh", cookie));
        return new NovelSeriesMeta(b.path("caption").asText(""), extractSeriesCoverUrl(b), extractTags(b));
    }

    /** 解析单作品的原图 URL 列表（插画 / 漫画）。 */
    public List<String> resolveImageUrls(String artworkId, String cookie) throws IOException {
        return resolveArtworkPages(artworkId, cookie).urls();
    }

    /**
     * 同 {@link #resolveImageUrls}，但<b>额外保留</b> {@code /pages} 的原始 body（逐页尺寸 / 各页原图 URL），
     * 供 meta 桥在 sidecar 里记录逐页 {@code width}/{@code height}/{@code original}（零额外请求）。
     */
    public ArtworkPages resolveArtworkPages(String artworkId, String cookie) throws IOException {
        JsonNode body = requireBody(proxyGet(
                "https://www.pixiv.net/ajax/illust/" + artworkId + "/pages", cookie));
        List<String> urls = new ArrayList<>();
        for (JsonNode page : body) {
            String orig = page.path("urls").path("original").asText("");
            if (!orig.isEmpty()) urls.add(orig);
        }
        return new ArtworkPages(urls, body);
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
     * 发现搜索结果<b>单页</b>的作品 ID（插画 + 漫画 + 动图），按页内顺序返回（不跨页去重）。
     * 空列表表示该页无结果或越界。供计划任务「结束页 = -1（一直翻页直到命中已下载作品为止）」的
     * 增量逐页发现复用；遵循「URL 编码只能做一次」约束，{@code word} 以原始未编码形态交给 builder。
     */
    public List<String> discoverSearchArtworkIdsPage(String word, String order, String mode,
                                                     String sMode, int page, String cookie) throws IOException {
        URI uri = UriComponentsBuilder
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
        JsonNode data = requireBody(proxyGetUri(uri, cookie)).path("illustManga").path("data");
        List<String> out = new ArrayList<>();
        if (data.isArray()) {
            for (JsonNode item : data) {
                String id = item.path("id").asText("");
                if (!id.isEmpty()) out.add(id);
            }
        }
        return out;
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
                .sorted(Comparator.comparingInt(Member::order))
                .forEach(m -> ids.put(m.id(), Boolean.TRUE));
        return new ArrayList<>(ids.keySet());
    }

    // ---- 账号私有来源发现（收藏 / 已关注用户的新作 / 珍藏集，均需含 PHPSESSID 的 cookie） ----

    /** 从 cookie 的 PHPSESSID 下划线前缀解析本账号 uid；缺失 / 非法返回 {@code null}。 */
    static String resolveOwnUid(String cookie) {
        if (cookie == null) return null;
        Matcher m = PHPSESSID_PATTERN.matcher(cookie);
        if (!m.find()) return null;
        String value = m.group(1);
        int underscore = value.indexOf('_');
        if (underscore <= 0) return null;
        String prefix = value.substring(0, underscore);
        return prefix.chars().allMatch(Character::isDigit) ? prefix : null;
    }

    /** 发现账号收藏的全部插画/漫画 ID（{@code rest=show|hide}），按收藏顺序去重返回。 */
    public List<String> discoverMyIllustBookmarkIds(String rest, String cookie) throws IOException {
        return discoverMyBookmarkIds("illusts", rest, cookie);
    }

    /** 发现账号收藏的全部小说 ID（{@code rest=show|hide}），按收藏顺序去重返回。 */
    public List<String> discoverMyNovelBookmarkIds(String rest, String cookie) throws IOException {
        return discoverMyBookmarkIds("novels", rest, cookie);
    }

    private List<String> discoverMyBookmarkIds(String kind, String rest, String cookie) throws IOException {
        String uid = resolveOwnUid(cookie);
        if (uid == null) {
            throw new PixivFetchException("missing or invalid PHPSESSID for bookmarks discovery");
        }
        String safeRest = "hide".equals(rest) ? "hide" : "show";
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        int offset = 0;
        for (int guard = 0; guard < 1000; guard++) {
            URI uri = UriComponentsBuilder
                    .fromUriString("https://www.pixiv.net/ajax/user/{uid}/{kind}/bookmarks")
                    .queryParam("tag", "")
                    .queryParam("offset", offset)
                    .queryParam("limit", BOOKMARK_PAGE_LIMIT)
                    .queryParam("rest", safeRest)
                    .queryParam("lang", "zh")
                    .buildAndExpand(Map.of("uid", uid, "kind", kind))
                    .encode()
                    .toUri();
            JsonNode body = requireBody(proxyGetUri(uri, cookie));
            JsonNode works = body.path("works");
            if (!works.isArray() || works.isEmpty()) break;
            for (JsonNode w : works) {
                String id = w.path("id").asText("");
                if (!id.isBlank()) ids.add(id);
            }
            int total = body.path("total").asInt(0);
            offset += BOOKMARK_PAGE_LIMIT;
            if (total > 0 && offset >= total) break;
        }
        return new ArrayList<>(ids);
    }

    /**
     * 发现「已关注用户的新作」（フォロー新着作品，仅插画/漫画/动图）的全部作品 ID，按 feed 顺序去重返回。
     * 逐页拉 {@code /ajax/follow_latest/illust?mode=all&p=N}，{@code page.isLastPage} 或空页停（安全上限 100 页）。
     */
    public List<String> discoverFollowLatestIllustIds(String cookie) throws IOException {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (int p = 1; p <= FOLLOW_LATEST_MAX_PAGES; p++) {
            FollowLatestPage page = fetchFollowLatestPage(p, cookie);
            if (page.ids().isEmpty()) break;
            ids.addAll(page.ids());
            if (page.lastPage()) break;
        }
        return new ArrayList<>(ids);
    }

    /**
     * 拉取「已关注用户的新作」<b>单页</b>（フォロー新着作品）：返回该页作品 ID（feed 顺序，最新在前）与是否末页。
     * 供 FOLLOW_LATEST 计划任务的水位线增量发现逐页消费，同时被 {@link #discoverFollowLatestIllustIds} 复用。
     * 缺少 / 非法 PHPSESSID 时抛 {@link PixivFetchException}（账号私有来源、无法匿名）。
     */
    public FollowLatestPage fetchFollowLatestPage(int p, String cookie) throws IOException {
        if (resolveOwnUid(cookie) == null) {
            throw new PixivFetchException("missing or invalid PHPSESSID for follow-latest discovery");
        }
        URI uri = UriComponentsBuilder
                .fromUriString("https://www.pixiv.net/ajax/follow_latest/illust")
                .queryParam("mode", "all")
                .queryParam("p", p)
                .queryParam("lang", "zh")
                .build()
                .encode()
                .toUri();
        JsonNode body = requireBody(proxyGetUri(uri, cookie));
        JsonNode isLast = body.path("page").path("isLastPage");
        return new FollowLatestPage(followLatestPageIds(body), isLast.isBoolean() && isLast.asBoolean());
    }

    /** follow_latest 单页发现结果：当前页作品 ID（feed 顺序，最新在前）+ 是否末页。 */
    public record FollowLatestPage(List<String> ids, boolean lastPage) {
    }

    /** 从 follow_latest 的 {@code body} 抽出当前页作品 ID（优先 {@code page.ids}，回退 {@code thumbnails.illust}）。纯函数，便于单测。 */
    static List<String> followLatestPageIds(JsonNode body) {
        List<String> out = new ArrayList<>();
        if (body == null) return out;
        JsonNode ids = body.path("page").path("ids");
        if (ids.isArray() && !ids.isEmpty()) {
            for (JsonNode n : ids) {
                String s = n.asText("");
                if (!s.isBlank()) out.add(s);
            }
            return out;
        }
        for (JsonNode it : body.path("thumbnails").path("illust")) {
            String s = it.path("id").asText("");
            if (!s.isBlank()) out.add(s);
        }
        return out;
    }

    /** 发现某珍藏集内的插画与小说成员 ID（混合，按珍藏集布局顺序），分别返回。 */
    public CollectionWorkIds discoverCollectionWorkIds(String collectionId, String cookie) throws IOException {
        if (resolveOwnUid(cookie) == null) {
            throw new PixivFetchException("missing or invalid PHPSESSID for collection discovery");
        }
        URI uri = UriComponentsBuilder
                .fromUriString("https://www.pixiv.net/ajax/collection/{cid}")
                .queryParam("lang", "zh")
                .buildAndExpand(Map.of("cid", collectionId))
                .encode()
                .toUri();
        return parseCollectionWorkIds(requireBody(proxyGetUri(uri, cookie)));
    }

    /**
     * 把 {@code /ajax/collection/{id}} 的 {@code body} 解析为插画 / 小说成员 ID 两份列表：
     * 顺序与 workType/workId 取自 {@code data.detail.tiles[]}（仅 {@code type=Work} 且 {@code status=Active}）。纯函数，便于单测。
     */
    static CollectionWorkIds parseCollectionWorkIds(JsonNode body) {
        List<String> illustIds = new ArrayList<>();
        List<String> novelIds = new ArrayList<>();
        if (body == null) return new CollectionWorkIds(illustIds, novelIds);
        JsonNode tiles = body.path("data").path("detail").path("tiles");
        if (tiles.isArray()) {
            for (JsonNode tile : tiles) {
                if (!"Work".equals(tile.path("type").asText(""))) continue;
                if (!"Active".equals(tile.path("status").asText("Active"))) continue;
                String workId = tile.path("workId").asText("");
                if (workId.isBlank()) continue;
                if ("novel".equals(tile.path("workType").asText(""))) {
                    novelIds.add(workId);
                } else {
                    illustIds.add(workId);
                }
            }
        }
        return new CollectionWorkIds(illustIds, novelIds);
    }

    /** 珍藏集内拆分后的成员 ID（插画/漫画/动图 与 小说分开），供 COLLECTION 计划任务分别走对应下载管线。 */
    public record CollectionWorkIds(List<String> illustIds, List<String> novelIds) {
    }

    // ---- 小说发现 / 解析（供后台调度的小说计划复用） ----------------------------

    /** 发现某画师的全部小说 ID，按 ID 倒序（新作在前）。 */
    public List<String> discoverUserNovelIds(String userId, String cookie) throws IOException {
        JsonNode body = requireBody(proxyGet(
                "https://www.pixiv.net/ajax/user/" + userId + "/profile/all", cookie));
        List<String> ids = new ArrayList<>();
        body.path("novels").fieldNames().forEachRemaining(ids::add);
        ids.sort((a, b) -> Long.compare(Long.parseLong(b), Long.parseLong(a)));
        return ids;
    }

    /** 按关键词搜索发现小说 ID，翻 {@code maxPages} 页去重后返回（按出现顺序）。 */
    public List<String> discoverSearchNovelIds(String word, String order, String mode,
                                               String sMode, int maxPages, String cookie) throws IOException {
        LinkedHashMap<String, Boolean> ids = new LinkedHashMap<>();
        int pages = Math.max(1, maxPages);
        for (int p = 1; p <= pages; p++) {
            URI uri = UriComponentsBuilder
                    .fromUriString("https://www.pixiv.net/ajax/search/novels/{word}")
                    .queryParam("word", "{word}")
                    .queryParam("order", order)
                    .queryParam("mode", mode)
                    .queryParam("s_mode", sMode)
                    .queryParam("p", p)
                    .queryParam("lang", "zh")
                    .buildAndExpand(Map.of("word", word))
                    .encode()
                    .toUri();
            JsonNode data = requireBody(proxyGetUri(uri, cookie)).path("novel").path("data");
            if (!data.isArray() || data.isEmpty()) break;
            int before = ids.size();
            for (JsonNode item : data) {
                String id = item.path("id").asText("");
                if (!id.isEmpty()) ids.put(id, Boolean.TRUE);
            }
            if (ids.size() == before) break;
        }
        return new ArrayList<>(ids.keySet());
    }

    /**
     * 发现搜索结果<b>单页</b>的小说 ID，按页内顺序返回（不跨页去重）。空列表表示该页无结果或越界。
     * 供计划任务「结束页 = -1（一直翻页直到命中已下载作品为止）」的增量逐页发现复用。
     */
    public List<String> discoverSearchNovelIdsPage(String word, String order, String mode,
                                                   String sMode, int page, String cookie) throws IOException {
        URI uri = UriComponentsBuilder
                .fromUriString("https://www.pixiv.net/ajax/search/novels/{word}")
                .queryParam("word", "{word}")
                .queryParam("order", order)
                .queryParam("mode", mode)
                .queryParam("s_mode", sMode)
                .queryParam("p", page)
                .queryParam("lang", "zh")
                .buildAndExpand(Map.of("word", word))
                .encode()
                .toUri();
        JsonNode data = requireBody(proxyGetUri(uri, cookie)).path("novel").path("data");
        List<String> out = new ArrayList<>();
        if (data.isArray()) {
            for (JsonNode item : data) {
                String id = item.path("id").asText("");
                if (!id.isEmpty()) out.add(id);
            }
        }
        return out;
    }

    /**
     * 发现某小说系列内的全部小说 ID，按系列内顺序。
     *
     * <p>逐页拉取 {@code /ajax/novel/series_content/{id}}（30/页），从 {@code seriesContents[].id} 收集成员，
     * 直到某页不足一页为止。
     */
    public List<String> discoverNovelSeriesIds(String seriesId, String cookie) throws IOException {
        LinkedHashMap<String, Boolean> ids = new LinkedHashMap<>();
        int limit = 30;
        for (int p = 1; p <= 200; p++) {
            URI uri = UriComponentsBuilder
                    .fromUriString("https://www.pixiv.net/ajax/novel/series_content/{id}")
                    .queryParam("limit", limit)
                    .queryParam("last_order", (p - 1) * limit)
                    .queryParam("order_by", "asc")
                    .queryParam("lang", "zh")
                    .buildAndExpand(Map.of("id", seriesId))
                    .encode()
                    .toUri();
            JsonNode body = requireBody(proxyGetUri(uri, cookie));
            JsonNode arr = body.path("page").path("seriesContents");
            if (!arr.isArray() || arr.isEmpty()) arr = body.path("seriesContents");
            if (!arr.isArray() || arr.isEmpty()) break;
            int count = 0;
            for (JsonNode entry : arr) {
                String id = entry.path("id").asText("");
                if (!id.isEmpty()) {
                    ids.put(id, Boolean.TRUE);
                    count++;
                }
            }
            if (count < limit) break;
        }
        return new ArrayList<>(ids.keySet());
    }

    /**
     * 拉取单本小说的完整详情（正文 markup、标签、内嵌图 URL、封面、系列、字数/收藏），
     * 供调度的小说下载组装 {@code NovelDownloadRequest}。
     */
    public NovelDetail fetchNovelDetail(String novelId, String cookie) throws IOException {
        return fetchNovelDetailCapture(novelId, cookie).detail();
    }

    /**
     * 同 {@link #fetchNovelDetail}，但<b>额外保留</b>原始 {@code /ajax/novel/{id}} body，供
     * meta 桥归一化小说 sidecar（剪枝时额外剥正文 {@code content} 与内嵌图 {@code textEmbeddedImages}）。
     */
    public NovelDetailCapture fetchNovelDetailCapture(String novelId, String cookie) throws IOException {
        URI uri = UriComponentsBuilder
                .fromUriString("https://www.pixiv.net/ajax/novel/{id}")
                .queryParam("lang", "zh")
                .buildAndExpand(Map.of("id", novelId))
                .encode()
                .toUri();
        JsonNode b = requireBody(proxyGetUri(uri, cookie));
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
        String content = b.path("content").asText("");
        NovelDetail detail = new NovelDetail(
                Long.parseLong(novelId),
                b.path("title").asText(""),
                b.path("xRestrict").asInt(0),
                b.path("aiType").asInt(0) >= 2,
                b.path("bookmarkCount").asInt(-1),
                parsePositiveLong(b.path("userId").asText(null)),
                b.path("userName").asText(""),
                b.path("description").asText(""),
                extractTags(b),
                seriesId,
                seriesOrder,
                seriesTitle,
                content,
                b.has("wordCount") ? b.path("wordCount").asInt(0) : null,
                b.has("characterCount") ? b.path("characterCount").asInt(0) : null,
                extractReadingTimeSeconds(b),
                countPages(content),
                b.path("isOriginal").asBoolean(false),
                b.path("language").asText(""),
                extractNovelCoverUrl(b),
                extractUploadTimestamp(b),
                extractTextEmbeddedImages(b)
        );
        return new NovelDetailCapture(detail, b);
    }

    // ---- 解析辅助（与 PixivProxyController 同源，供服务端发现路径复用） ----------

    /* 标签词元（原名 + 英文翻译，已小写去重），供标签筛选的不区分大小写匹配。 */
    /** 从系列记录节点解析封面 URL：优先 {@code cover.urls} 最优尺寸，回退到常见单值字段。 */
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

    private static List<TagDto> extractTags(JsonNode body) {
        JsonNode tagsArr = body.path("tags").path("tags");
        if (!tagsArr.isArray() || tagsArr.isEmpty()) tagsArr = body.path("tags");
        if (!tagsArr.isArray() || tagsArr.isEmpty()) return List.of();
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
        for (String field : new String[]{"readingTimeSeconds", "readingTime", "readTime", "estimatedReadingTime"}) {
            JsonNode value = node.path(field);
            if (value.isMissingNode() || value.isNull()) continue;
            if (value.isNumber()) {
                int seconds = value.asInt(0);
                return seconds > 0 ? seconds : null;
            }
            String digits = value.asText("").replaceAll("[^0-9]", "");
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
                    if (!cover.isBlank()) return cover;
                }
            }
        }
        for (String key : List.of("coverUrl", "url", "thumbnailUrl")) {
            String cover = node.path(key).asText("");
            if (!cover.isBlank()) return cover;
        }
        return "";
    }

    /** epoch 秒 ↔ 毫秒消歧阈值：{@code >=} 此值视为毫秒，否则视为秒（× 1000）。 */
    private static final long EPOCH_MILLIS_THRESHOLD = 1_000_000_000_000L;

    /**
     * 解析小说上传时间为 epoch 毫秒：先按 ISO 日期串候选 {@code uploadDate}/{@code createDate}/{@code updateDate}，
     * 再兼容 {@code uploadTimestamp}（可能是 epoch 毫秒 / 秒数字，或 ISO 字符串）。类型安全，非法值不退化成 0。
     */
    static Long extractUploadTimestamp(JsonNode node) {
        for (String field : List.of("uploadDate", "createDate", "updateDate")) {
            String iso = node.path(field).asText(null);
            if (iso == null || iso.isBlank()) continue;
            try {
                return OffsetDateTime.parse(iso).toInstant().toEpochMilli();
            } catch (Exception ignored) {
            }
        }
        return parseFlexibleEpochMillis(node.path("uploadTimestamp"));
    }

    /**
     * 解析「可能是 epoch 毫秒 / epoch 秒数字，或 ISO 字符串」的时间值为 epoch 毫秒。
     * 数字按 {@link #EPOCH_MILLIS_THRESHOLD} 在毫秒/秒间消歧；非法字符串（既非 ISO 又非数字）返回
     * {@code null}，<b>绝不</b>退化成 0。
     */
    static Long parseFlexibleEpochMillis(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return normalizeEpochMillis(node.asLong());
        }
        if (node.isTextual()) {
            String text = node.asText("").trim();
            if (text.isEmpty()) {
                return null;
            }
            try {
                return OffsetDateTime.parse(text).toInstant().toEpochMilli();
            } catch (Exception ignored) {
                // 非 ISO，再试纯数字串
            }
            try {
                return normalizeEpochMillis(Long.parseLong(text));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /** epoch 秒 ↔ 毫秒消歧：{@code >= EPOCH_MILLIS_THRESHOLD} 视为毫秒，否则视为秒（× 1000）；{@code <= 0} 视为无效。 */
    private static Long normalizeEpochMillis(long value) {
        if (value <= 0) {
            return null;
        }
        return value >= EPOCH_MILLIS_THRESHOLD ? value : value * 1000L;
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

    /** 抽取 {@code body.textEmbeddedImages} 中的 {@code original} URL（仅 pximg.net）。 */
    private static Map<String, String> extractTextEmbeddedImages(JsonNode body) {
        JsonNode node = body.path("textEmbeddedImages");
        if (!node.isObject() || node.isEmpty()) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        node.fields().forEachRemaining(e -> {
            String url = e.getValue().path("urls").path("original").asText("");
            if (url.isBlank()) return;
            try {
                String host = URI.create(url).getHost();
                if (host == null || !host.endsWith(".pximg.net")) return;
            } catch (IllegalArgumentException ignored) {
                return;
            }
            out.put(e.getKey(), url);
        });
        return out;
    }

    /** 站内信线程 RPC：固定 URL，不接受用户传入参数。 */
    private static final String MESSAGE_THREADS_URL =
            "https://www.pixiv.net/rpc/index.php?mode=latest_message_threads2&num=3&offset=0";

    /**
     * 读取站内信线程（{@code latest_message_threads2}），供计划任务过度访问检测 + cookie 存活探测复用。
     * 沿用 byte[].class + UTF-8 解析（不请求 {@code String.class}）。返回 {@code body} 节点。
     *
     * <p>上游 4xx（含 401/403）、登录重定向导致的非 JSON 回包、或 {@code error=true} 都视为
     * 「cookie 已死」并上抛 {@link PixivFetchException}——调用方（{@code OveruseWarningService}）据此判定 COOKIE_DEAD。
     */
    public JsonNode fetchMessageThreads(String cookie) throws IOException {
        String json;
        try {
            json = proxyGet(MESSAGE_THREADS_URL, cookie);
        } catch (HttpClientErrorException e) {
            throw new PixivFetchException("message threads http " + e.getStatusCode().value());
        }
        if (json == null || json.isBlank()) {
            throw new PixivFetchException("empty message threads response");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (IOException e) {
            // 非 JSON（登录重定向 HTML 等）= cookie 已死
            throw new PixivFetchException("non-json message threads response");
        }
        if (root.path("error").asBoolean(false)) {
            throw new PixivFetchException(root.path("message").asText(""));
        }
        return root.path("body");
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

    /**
     * 单作品发现 / 解析结果的精简视图。
     *
     * @param bookmarkCount 收藏数（Pixiv 未返回时为 -1）
     * @param pageCount     页数（插画 / 漫画）
     * @param tags          标签（原名 + 英文翻译），同时供落库与计划任务服务端标签筛选
     * @param description   作品简介（原始 markup，落库前再统一规整链接）
     * @param seriesTitle   所属系列标题（无系列时为 {@code null}）
     */
    public record ArtworkMeta(int illustType, String title, int xRestrict, boolean ai,
                              Long authorId, String authorName, Long seriesId, Long seriesOrder,
                              int bookmarkCount, int pageCount, List<TagDto> tags,
                              String description, String seriesTitle) {
        /** illustType==2 为动图（ugoira）。 */
        public boolean isUgoira() {
            return illustType == 2;
        }
    }

    /**
     * 插画发现结果 + 原始 illust body（用于在已抓 body 上归一化 meta sidecar，零额外请求）。
     * {@code body} 是 {@code /ajax/illust/{id}} 的 body 节点。
     */
    public record ArtworkMetaCapture(ArtworkMeta meta, JsonNode body) {
    }

    /** 单作品逐页原图 URL + 原始 {@code /pages} body（逐页尺寸），供 meta sidecar 记录逐页信息。 */
    public record ArtworkPages(List<String> urls, JsonNode body) {
    }

    /** 小说详情 + 原始 {@code /ajax/novel/{id}} body（meta sidecar 归一化用）。 */
    public record NovelDetailCapture(NovelDetail detail, JsonNode body) {
    }

    /** 插画 / 漫画系列富信息（简介 + 封面 URL）。 */
    public record IllustSeriesMeta(String caption, String coverUrl) {
    }

    /** 小说系列富信息（简介 + 封面 URL + 系列标签）。 */
    public record NovelSeriesMeta(String caption, String coverUrl, List<TagDto> tags) {
    }

    /** 动图帧信息。 */
    public record UgoiraInfo(String zipUrl, List<Integer> delays) {
    }

    /**
     * 单本小说的完整发现 / 解析结果，供调度组装 {@code NovelDownloadRequest}。
     *
     * @param content         正文原始 markup（合订 / 再导出的权威源）
     * @param embeddedImages  {@code [uploadedimage:id]} → pximg 原图 URL
     * @param tags            标签（原名 + 英文翻译），同时供落库与服务端标签筛选
     */
    public record NovelDetail(long novelId, String title, int xRestrict, boolean ai, int bookmarkCount,
                              Long authorId, String authorName, String description, List<TagDto> tags,
                              Long seriesId, Long seriesOrder, String seriesTitle,
                              String content, Integer wordCount, Integer textLength,
                              Integer readingTimeSeconds, Integer pageCount, boolean original,
                              String language, String coverUrl, Long uploadTimestamp,
                              Map<String, String> embeddedImages) {
    }

    /** Pixiv AJAX 以 {@code error=true} 返回时抛出（常见于 Cookie 失效 / 受限作品需登录）。 */
    public static class PixivFetchException extends RuntimeException {
        public PixivFetchException(String message) {
            super(message);
        }
    }
}

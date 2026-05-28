package top.sywyar.pixivdownload.download.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.download.response.CollectionWorksResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PixivProxyController} 中「快捷获取」相关的纯函数解析：cookie 主人 uid 解析、珍藏集集内作品解析。
 * 均为纯函数，无需 Spring 上下文 / 不触网。
 */
@DisplayName("快捷获取代理解析")
class PixivMeProxyParseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode body(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ── extractUidFromCookie ────────────────────────────────────────────────

    @Test
    @DisplayName("uid 解析：从 PHPSESSID 的下划线前缀取出登录用户 id")
    void uidFromPhpsessidPrefix() {
        assertThat(PixivProxyController.extractUidFromCookie("PHPSESSID=12345_abcdefABCDEF; other=x"))
                .isEqualTo("12345");
    }

    @Test
    @DisplayName("uid 解析：忽略大小写与前后其它 cookie 项")
    void uidIgnoresCaseAndOtherPairs() {
        assertThat(PixivProxyController.extractUidFromCookie("a=1; phpsessid=9460149_xyz; b=2"))
                .isEqualTo("9460149");
    }

    @Test
    @DisplayName("uid 解析：缺 PHPSESSID / 无下划线 / 前缀非纯数字 / null 时返回 null")
    void uidReturnsNullWhenInvalid() {
        assertThat(PixivProxyController.extractUidFromCookie(null)).isNull();
        assertThat(PixivProxyController.extractUidFromCookie("foo=bar")).isNull();
        assertThat(PixivProxyController.extractUidFromCookie("PHPSESSID=noundersore")).isNull();
        assertThat(PixivProxyController.extractUidFromCookie("PHPSESSID=ab12_xyz")).isNull();
    }

    // ── parseCollectionWorks ────────────────────────────────────────────────

    @Test
    @DisplayName("珍藏集集内作品：按 tiles 顺序输出插画+小说混合，并从对应 thumbnails 取卡片详情")
    void collectionWorksMixedInTileOrder() {
        JsonNode b = body("{"
                + "\"thumbnails\":{"
                + "  \"illust\":[{\"id\":\"143390502\",\"title\":\"i1\",\"illustType\":0,\"xRestrict\":1,"
                + "               \"url\":\"https://i.pximg.net/a.jpg\",\"pageCount\":3,\"userId\":\"9\",\"userName\":\"u\",\"tags\":[\"t\"]}],"
                + "  \"novel\":[{\"id\":\"555\",\"title\":\"n1\",\"wordCount\":1200,\"isOriginal\":true,\"userName\":\"nu\"}]"
                + "},"
                + "\"data\":{\"detail\":{\"tiles\":["
                + "  {\"type\":\"Work\",\"status\":\"Active\",\"workType\":\"novel\",\"workId\":\"555\"},"
                + "  {\"type\":\"Work\",\"status\":\"Active\",\"workType\":\"illust\",\"workId\":\"143390502\"}"
                + "]}}}");
        List<CollectionWorksResponse.Work> works = PixivProxyController.parseCollectionWorks(b);
        assertThat(works).extracting(CollectionWorksResponse.Work::kind).containsExactly("novel", "illust");
        assertThat(works).extracting(CollectionWorksResponse.Work::id).containsExactly("555", "143390502");
        CollectionWorksResponse.Work novel = works.get(0);
        assertThat(novel.title()).isEqualTo("n1");
        assertThat(novel.wordCount()).isEqualTo(1200);
        assertThat(novel.isOriginal()).isTrue();
        CollectionWorksResponse.Work illust = works.get(1);
        assertThat(illust.title()).isEqualTo("i1");
        assertThat(illust.xRestrict()).isEqualTo(1);
        assertThat(illust.pageCount()).isEqualTo(3);
        assertThat(illust.thumbnailUrl()).isEqualTo("https://i.pximg.net/a.jpg");
        assertThat(illust.tags()).containsExactly("t");
    }

    @Test
    @DisplayName("珍藏集集内作品：跳过非 Work / 非 Active 的 tile 与缺失卡片的 workId")
    void collectionWorksSkipsInactiveAndMissing() {
        JsonNode b = body("{"
                + "\"thumbnails\":{\"illust\":[{\"id\":\"1\",\"title\":\"keep\"}],\"novel\":[]},"
                + "\"data\":{\"detail\":{\"tiles\":["
                + "  {\"type\":\"Work\",\"status\":\"Active\",\"workType\":\"illust\",\"workId\":\"1\"},"
                + "  {\"type\":\"Work\",\"status\":\"Inactive\",\"workType\":\"illust\",\"workId\":\"1\"},"
                + "  {\"type\":\"Quote\",\"status\":\"Active\",\"workType\":\"illust\",\"workId\":\"1\"},"
                + "  {\"type\":\"Work\",\"status\":\"Active\",\"workType\":\"illust\",\"workId\":\"999\"}"
                + "]}}}");
        List<CollectionWorksResponse.Work> works = PixivProxyController.parseCollectionWorks(b);
        assertThat(works).extracting(CollectionWorksResponse.Work::id).containsExactly("1");
    }

    @Test
    @DisplayName("珍藏集集内作品：body 为 null 或无 tiles 时返回空列表，不抛异常")
    void collectionWorksHandlesNull() {
        assertThat(PixivProxyController.parseCollectionWorks(null)).isEmpty();
        assertThat(PixivProxyController.parseCollectionWorks(body("{}"))).isEmpty();
    }
}

package top.sywyar.pixivdownload.download.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.download.response.SearchResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PixivProxyController} 中 User 模式卡片元数据解析（纯函数，无需 Spring 上下文 / 不触网）。
 */
@DisplayName("User 模式卡片元数据解析")
class PixivUserCardsParseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode body(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("插画卡片：按请求 ids 顺序保序，与 Pixiv 返回对象的键序无关")
    void illustPreservesRequestedOrder() {
        JsonNode b = body("{\"222\":{\"id\":\"222\",\"title\":\"b\"},\"111\":{\"id\":\"111\",\"title\":\"a\"}}");
        List<SearchResponse.SearchItem> items = PixivProxyController.parseUserIllustCards(b, List.of("111", "222"));
        assertThat(items).extracting(SearchResponse.SearchItem::getId).containsExactly("111", "222");
    }

    @Test
    @DisplayName("插画卡片：跳过 null / 缺失的已删除作品")
    void illustSkipsDeleted() {
        JsonNode b = body("{\"111\":{\"id\":\"111\"},\"222\":null}");
        List<SearchResponse.SearchItem> items = PixivProxyController.parseUserIllustCards(b, List.of("111", "222", "333"));
        assertThat(items).extracting(SearchResponse.SearchItem::getId).containsExactly("111");
    }

    @Test
    @DisplayName("插画卡片：完整映射字段（标题/类型/分级/AI/缩略图/页数/作者/去重标签）")
    void illustMapsAllFields() {
        JsonNode b = body("{\"111\":{\"id\":\"111\",\"title\":\"t\",\"illustType\":1,\"xRestrict\":1,"
                + "\"aiType\":2,\"url\":\"https://i.pximg.net/x.jpg\",\"pageCount\":5,"
                + "\"userId\":\"9\",\"userName\":\"u\",\"tags\":[\"a\",\"b\",\"a\"]}}");
        SearchResponse.SearchItem item = PixivProxyController.parseUserIllustCards(b, List.of("111")).get(0);
        assertThat(item.getTitle()).isEqualTo("t");
        assertThat(item.getIllustType()).isEqualTo(1);
        assertThat(item.getXRestrict()).isEqualTo(1);
        assertThat(item.getAiType()).isEqualTo(2);
        assertThat(item.getThumbnailUrl()).isEqualTo("https://i.pximg.net/x.jpg");
        assertThat(item.getPageCount()).isEqualTo(5);
        assertThat(item.getUserId()).isEqualTo("9");
        assertThat(item.getUserName()).isEqualTo("u");
        assertThat(item.getTags()).containsExactly("a", "b"); // 去重保序
    }

    @Test
    @DisplayName("插画卡片：body 或 ids 为 null 时返回空列表，不抛异常")
    void illustHandlesNull() {
        assertThat(PixivProxyController.parseUserIllustCards(null, List.of("1"))).isEmpty();
        assertThat(PixivProxyController.parseUserIllustCards(body("{}"), null)).isEmpty();
    }

}

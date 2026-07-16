package top.sywyar.pixivdownload.novel.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.novel.response.NovelSearchResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("User 模式小说卡片元数据解析")
class NovelPixivUserCardsParseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode body(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("小说卡片：按请求 ids 顺序保序并跳过已删除作品")
    void novelPreservesOrderAndSkipsDeleted() {
        JsonNode b = body("{\"222\":{\"id\":\"222\"},\"111\":null,\"333\":{\"id\":\"333\"}}");
        List<NovelSearchResponse.NovelSearchItem> items =
                NovelPixivProxyController.parseUserNovelCards(b, List.of("111", "222", "333"));
        assertThat(items).extracting(NovelSearchResponse.NovelSearchItem::id).containsExactly("222", "333");
    }

    @Test
    @DisplayName("小说卡片：缺失收藏数回退为 -1（前端据此判定需补抓），字段映射正确")
    void novelDefaultsBookmarkCountAndMapsFields() {
        JsonNode b = body("{\"111\":{\"id\":\"111\",\"title\":\"t\",\"xRestrict\":2,\"wordCount\":1200,"
                + "\"userName\":\"u\",\"isOriginal\":true,\"tags\":[\"x\"]}}");
        NovelSearchResponse.NovelSearchItem item =
                NovelPixivProxyController.parseUserNovelCards(b, List.of("111")).get(0);
        assertThat(item.xRestrict()).isEqualTo(2);
        assertThat(item.bookmarkCount()).isEqualTo(-1);
        assertThat(item.wordCount()).isEqualTo(1200);
        assertThat(item.isOriginal()).isTrue();
        assertThat(item.tags()).containsExactly("x");
    }
}

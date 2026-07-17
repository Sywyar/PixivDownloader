package top.sywyar.pixivdownload.gallery.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("gallery 插件私有 HTTP 投影")
class GalleryWebResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("作品与分页响应保持既有 JSON 字段且详情标签不新增目录计数")
    void preservesArtworkAndPageWireShape() {
        GalleryArtworkResponse artwork = new GalleryArtworkResponse(
                10L, "title", "folder", 2, "jpg,png", 100L,
                true, "moved", 200L, 1, false, 20L, "author", "description",
                30L, "template", List.of(new GalleryTagResponse(40L, "tag", "translated")),
                50L, 3L, "series", false);

        JsonNode artworkJson = objectMapper.valueToTree(artwork);
        assertThat(fieldNames(artworkJson)).containsExactlyInAnyOrder(
                "artworkId", "title", "folder", "count", "extensions", "time", "moved",
                "moveFolder", "moveTime", "xRestrict", "isAi", "authorId", "authorName",
                "description", "fileName", "fileNameTemplate", "tags", "seriesId",
                "seriesOrder", "seriesTitle", "deleted");
        assertThat(fieldNames(artworkJson.path("tags").get(0)))
                .containsExactlyInAnyOrder("tagId", "name", "translatedName");

        JsonNode pageJson = objectMapper.valueToTree(
                new GalleryPageResponse(List.of(artwork), 5, 0, 24, 1));
        assertThat(fieldNames(pageJson)).containsExactlyInAnyOrder(
                "content", "totalElements", "page", "size", "totalPages");
    }

    @Test
    @DisplayName("标签目录响应继续使用 artworkCount 字段")
    void preservesTagOptionWireShape() {
        JsonNode json = objectMapper.valueToTree(
                new GalleryTagOptionResponse(40L, "tag", "translated", 7));

        assertThat(fieldNames(json)).containsExactlyInAnyOrder(
                "tagId", "name", "translatedName", "artworkCount");
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> fields = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(fields::add);
        return fields;
    }
}

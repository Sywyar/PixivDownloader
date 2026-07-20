package top.sywyar.pixivdownload.download.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.download.request.DownloadRequest;
import top.sywyar.pixivdownload.download.response.ArtworkMetaResponse;
import top.sywyar.pixivdownload.download.response.SearchResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("插画下载 / 搜索 HTTP 投影契约")
class DownloadHttpProjectionContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("插画下载请求应绑定 camelCase 分级与布尔字段，且不暴露全小写别名")
    void downloadRequestBindsCamelCaseFields() throws Exception {
        DownloadRequest request = objectMapper.readValue("""
                {"artworkId":123,"title":"t","imageUrls":["u"],"other":{"xRestrict":2,"isAi":true,"isUgoira":true,"isUserDownload":true}}
                """, DownloadRequest.class);

        assertThat(request.getOther().getXRestrict()).isEqualTo(2);
        assertThat(request.getOther().isAi()).isTrue();
        assertThat(request.getOther().isUgoira()).isTrue();
        assertThat(request.getOther().isUserDownload()).isTrue();

        JsonNode other = objectMapper.valueToTree(request).path("other");
        assertThat(other.path("xRestrict").asInt()).isEqualTo(2);
        assertThat(other.path("isAi").asBoolean()).isTrue();
        assertThat(other.path("isUgoira").asBoolean()).isTrue();
        assertThat(other.path("isUserDownload").asBoolean()).isTrue();
        assertThat(other.has("xrestrict")).isFalse();
        assertThat(other.has("ai")).isFalse();
        assertThat(other.has("ugoira")).isFalse();
        assertThat(other.has("userDownload")).isFalse();
    }

    @Test
    @DisplayName("作品元数据响应只输出 camelCase 年龄分级与 AI 字段，不泄露全小写别名")
    void artworkMetaResponseUsesCanonicalAgeRatingFields() {
        ArtworkMetaResponse response = new ArtworkMetaResponse(
                0, "title", 2, true, 10, 3, 5L, "author", "desc",
                List.of(), 7L, 1L, "series");

        JsonNode json = objectMapper.valueToTree(response);
        assertThat(json.path("xRestrict").asInt()).isEqualTo(2);
        assertThat(json.path("isAi").asBoolean()).isTrue();
        assertThat(json.has("xrestrict")).isFalse();
        assertThat(json.has("ai")).isFalse();
    }

    @Test
    @DisplayName("搜索列表项响应只输出 camelCase 年龄分级字段，不泄露全小写别名")
    void searchItemUsesCanonicalAgeRatingFields() {
        SearchResponse.SearchItem item = new SearchResponse.SearchItem(
                "123", "title", 0, 1, 2, "thumb", 3, "456", "author", List.of("tag"));

        JsonNode json = objectMapper.valueToTree(item);
        assertThat(json.path("xRestrict").asInt()).isEqualTo(1);
        assertThat(json.has("xrestrict")).isFalse();
    }
}

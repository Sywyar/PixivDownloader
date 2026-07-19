package top.sywyar.pixivdownload.novel.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.core.work.WorkActionResult;
import top.sywyar.pixivdownload.novel.request.NovelDownloadRequest;
import top.sywyar.pixivdownload.novel.response.NovelAlreadyDownloadedResponse;
import top.sywyar.pixivdownload.novel.response.NovelDownloadResponse;
import top.sywyar.pixivdownload.novel.response.NovelQuotaExceededResponse;
import top.sywyar.pixivdownload.novel.response.NovelSearchResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("NovelDownloadController HTTP 投影契约")
class NovelDownloadHttpProjectionContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("小说下载请求应保持年龄分级与标签的 camelCase wire 结构")
    void downloadRequestBindsCamelCaseXRestrict() throws Exception {
        NovelDownloadRequest request = objectMapper.readValue("""
                {"novelId":123,"other":{"xRestrict":2,
                 "tags":[{"tagId":7,"name":"魔法","translatedName":"magic"}],
                 "seriesTags":[{"tagId":8,"name":"系列","translatedName":"series"}]}}
                """, NovelDownloadRequest.class);

        assertThat(request.getOther().getXRestrict()).isEqualTo(2);
        assertThat(request.getOther().getTags()).singleElement().satisfies(tag -> {
            assertThat(tag.tagId()).isEqualTo(7L);
            assertThat(tag.name()).isEqualTo("魔法");
            assertThat(tag.translatedName()).isEqualTo("magic");
        });
        assertThat(request.getOther().getSeriesTags()).singleElement()
                .satisfies(tag -> assertThat(tag.tagId()).isEqualTo(8L));

        JsonNode json = objectMapper.valueToTree(request);
        assertThat(json.path("other").path("xRestrict").asInt()).isEqualTo(2);
        assertThat(json.path("other").has("xrestrict")).isFalse();
        assertThat(json.path("other").path("tags").get(0).path("translatedName").asText())
                .isEqualTo("magic");
        assertThat(json.path("other").path("seriesTags").get(0).path("name").asText())
                .isEqualTo("系列");
    }

    @Test
    @DisplayName("小说列表响应应只输出 camelCase 年龄分级字段")
    void novelSearchItemUsesCanonicalAgeRatingFields() {
        NovelSearchResponse.NovelSearchItem item = new NovelSearchResponse.NovelSearchItem(
                "123", "title", 1, 0, 10, 20, 30,
                "456", "author", "cover", true, List.of("tag"));

        JsonNode json = objectMapper.valueToTree(item);
        assertThat(json.path("xRestrict").asInt()).isEqualTo(1);
        assertThat(json.path("isOriginal").asBoolean()).isTrue();
        assertThat(json.has("xrestrict")).isFalse();
        assertThat(json.has("original")).isFalse();
    }

    @Test
    @DisplayName("启动下载响应保持 success/message/downloadPath/downloadedCount 字段")
    void downloadResponseShapeIsStable() throws Exception {
        JsonNode json = objectMapper.valueToTree(NovelDownloadResponse.builder()
                .success(true)
                .message("started")
                .downloadPath("pending")
                .downloadedCount(1)
                .build());

        assertThat(json.path("success").asBoolean()).isTrue();
        assertThat(json.path("message").asText()).isEqualTo("started");
        assertThat(json.path("downloadPath").asText()).isEqualTo("pending");
        assertThat(json.path("downloadedCount").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("已下载响应保持 success/alreadyDownloaded/message 字段")
    void alreadyDownloadedResponseShapeIsStable() {
        JsonNode json = objectMapper.valueToTree(
                new NovelAlreadyDownloadedResponse(true, true, "already"));

        assertThat(json.path("success").asBoolean()).isTrue();
        assertThat(json.path("alreadyDownloaded").asBoolean()).isTrue();
        assertThat(json.path("message").asText()).isEqualTo("already");
    }

    @Test
    @DisplayName("配额超限响应保持归档与重置字段")
    void quotaExceededResponseShapeIsStable() {
        JsonNode json = objectMapper.valueToTree(new NovelQuotaExceededResponse(
                true, "quota", "token", 60L, 3, 10, 120L));

        assertThat(json.path("quotaExceeded").asBoolean()).isTrue();
        assertThat(json.path("message").asText()).isEqualTo("quota");
        assertThat(json.path("archiveToken").asText()).isEqualTo("token");
        assertThat(json.path("archiveExpireSeconds").asLong()).isEqualTo(60L);
        assertThat(json.path("artworksUsed").asInt()).isEqualTo(3);
        assertThat(json.path("maxArtworks").asInt()).isEqualTo(10);
        assertThat(json.path("resetSeconds").asLong()).isEqualTo(120L);
    }

    @Test
    @DisplayName("小说状态响应保持 bookmarkResult/collectionResult 动作结果字段")
    void statusActionResultShapeIsStable() {
        JsonNode json = objectMapper.valueToTree(new NovelDownloadController.NovelDownloadStatusResponse(
                true, "done", 7L, "title", "txt", "completed", true, false,
                "path", 0, 0, 0L, 0L,
                WorkActionResult.success("bookmarked"),
                WorkActionResult.exists("exists")));

        assertThat(json.path("bookmarkResult").path("status").asText()).isEqualTo("success");
        assertThat(json.path("bookmarkResult").path("message").asText()).isEqualTo("bookmarked");
        assertThat(json.path("collectionResult").path("status").asText()).isEqualTo("exists");
        assertThat(json.path("collectionResult").path("message").asText()).isEqualTo("exists");
    }
}

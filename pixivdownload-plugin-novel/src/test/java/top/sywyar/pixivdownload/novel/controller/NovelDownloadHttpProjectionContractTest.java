package top.sywyar.pixivdownload.novel.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.core.work.WorkActionResult;
import top.sywyar.pixivdownload.novel.response.NovelAlreadyDownloadedResponse;
import top.sywyar.pixivdownload.novel.response.NovelDownloadResponse;
import top.sywyar.pixivdownload.novel.response.NovelQuotaExceededResponse;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("NovelDownloadController HTTP 投影契约")
class NovelDownloadHttpProjectionContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

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

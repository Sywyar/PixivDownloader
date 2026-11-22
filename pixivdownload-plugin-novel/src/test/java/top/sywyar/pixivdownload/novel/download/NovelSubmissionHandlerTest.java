package top.sywyar.pixivdownload.novel.download;

import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import top.sywyar.pixivdownload.core.pixiv.PixivAjaxException;
import top.sywyar.pixivdownload.core.pixiv.PixivAjaxFailure;
import top.sywyar.pixivdownload.plugin.api.download.submission.DownloadTaskException;
import top.sywyar.pixivdownload.core.pixiv.PixivAjaxClient;
import top.sywyar.pixivdownload.novel.request.NovelDownloadRequest;
import top.sywyar.pixivdownload.plugin.api.download.lifecycle.DownloadAttempt;
import top.sywyar.pixivdownload.plugin.api.download.submission.DownloadSubmission;

import java.net.URI;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("小说公开任务提交")
class NovelSubmissionHandlerTest {
    private final jakarta.validation.ValidatorFactory validation = jakarta.validation.Validation.buildDefaultValidatorFactory();
    @org.junit.jupiter.api.AfterEach void closeValidation() { validation.close(); }
    @Test void onlyNotFoundIsTranslatedDuringNovelLookup() {
        var client = mock(PixivAjaxClient.class);
        var downloads = mock(NovelDownloadService.class);
        var handler = new NovelSubmissionHandler(client, JsonMapper.shared(), downloads, validation.getValidator());
        var submission = new DownloadSubmission(UUID.randomUUID(), "novel", "42", Map.of());
        var attempt = new DownloadAttempt(UUID.randomUUID(), "novel", "42");
        var forbidden = new PixivAjaxException(PixivAjaxFailure.HTTP_STATUS, 403);
        when(client.get(URI.create("https://www.pixiv.net/ajax/novel/42?lang=zh"), null))
                .thenThrow(new PixivAjaxException(PixivAjaxFailure.HTTP_STATUS, 404)).thenThrow(forbidden);
        assertThatExceptionOfType(DownloadTaskException.class).isThrownBy(() -> handler.submit(submission, attempt, null))
                .extracting(DownloadTaskException::code).isEqualTo(DownloadTaskException.Code.WORK_NOT_FOUND);
        assertThatThrownBy(() -> handler.submit(submission, attempt, null)).isSameAs(forbidden);
        verifyNoInteractions(downloads);
    }

    @Test @DisplayName("服务端取得正文后按同一任务身份入队，拒绝正文注入和非法格式")
    void fetchesContentAndRejectsInjectedOptions() {
        var client = mock(PixivAjaxClient.class);
        var downloads = mock(NovelDownloadService.class);
        var handler = new NovelSubmissionHandler(client, JsonMapper.builderWithJackson2Defaults().build(), downloads, validation.getValidator());
        var uri = URI.create("https://www.pixiv.net/ajax/novel/42?lang=zh");
        when(client.get(uri, "credential")).thenReturn("""
                {"error":false,"body":{"id":"42","title":"Story","content":"Text","userId":"7","userName":"Author"}}
                """);
        var attempt = new DownloadAttempt(UUID.randomUUID(), "novel", "42");
        handler.submit(new DownloadSubmission(UUID.randomUUID(), "novel", "42", Map.of(
                "format", "epub", "bookmark", "true", "collectionId", "9", "autoTranslate", "true",
                "autoTranslateLanguage", "en", "autoTranslateSegmentSize", "2000", "autoTranslateMerge", "true",
                "autoTranslateMergeFormat", "epub", "acquisitionMode", "search")), attempt, "credential");
        var request = ArgumentCaptor.forClass(NovelDownloadRequest.class);
        verify(downloads).submit(eq(attempt), request.capture(), isNull());
        verify(client).get(uri, "credential");
        assertThat(request.getValue().getContent()).isEqualTo("Text");
        assertThat(request.getValue().getOther().getFormat()).isEqualTo("epub");
        assertThat(request.getValue().getOther().isBookmark()).isTrue();
        assertThat(request.getValue().getOther().getCollectionId()).isEqualTo(9);
        assertThat(request.getValue().getOther().isAutoTranslate()).isTrue();
        assertThat(request.getValue().getOther().getAutoTranslateLanguage()).isEqualTo("en");
        assertThat(request.getValue().getOther().getAutoTranslateSegmentSize()).isEqualTo(2000);
        assertThat(request.getValue().getOther().isAutoTranslateMerge()).isTrue();
        assertThat(request.getValue().getOther().getAutoTranslateMergeFormat()).isEqualTo("epub");
        assertThat(request.getValue().getOther().getAcquisitionMode()).isEqualTo("search");
        assertThat(request.getValue().getOther().getRawMetaJson()).doesNotContain("content");
        for (var options : java.util.List.of(Map.of("content", "forged"), Map.of("format", "unknown"),
                Map.of("bookmark", "yes"), Map.of("collectionId", "-1"), Map.of("autoTranslateSegmentSize", "1000001"),
                Map.of("autoTranslateLanguage", "x".repeat(101)), Map.of("autoTranslateMergeFormat", "exe"))) {
            assertThatThrownBy(() -> handler.submit(new DownloadSubmission(UUID.randomUUID(), "novel", "42", options), attempt, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoMoreInteractions(client, downloads);
    }

    @Test @DisplayName("提供方发布的表单列全选项，文档示例可实际入队")
    void publishedSubmissionExamplesReachDownloadQueue() throws Exception {
        var mapper = JsonMapper.builderWithJackson2Defaults().build();
        var client = mock(PixivAjaxClient.class);
        var downloads = mock(NovelDownloadService.class);
        var handler = new NovelSubmissionHandler(client, mapper, downloads, validation.getValidator());
        try (var stream = handler.getClass().getResourceAsStream(handler.inputSchemaResource())) {
            assertThat(stream).isNotNull();
            var schema = mapper.readTree(stream);
            var choices = schema.at("/properties/options/properties");
            assertThat(choices.propertyNames()).containsExactlyInAnyOrder(
                    "fileNameTemplate", "pathOverflowAction", "bookmark", "collectionId", "acquisitionMode", "username",
                    "format", "autoTranslate", "autoTranslateLanguage", "autoTranslateSegmentSize",
                    "autoTranslateMerge", "autoTranslateMergeFormat");
            assertThat(schema.path("examples").size()).isPositive();
            for (var example : schema.path("examples")) {
                var submission = mapper.treeToValue(example, DownloadSubmission.class);
                assertThat(submission.workType()).isEqualTo(handler.workType());
                assertThat(choices.propertyNames()).containsAll(submission.options().keySet());
                var attempt = new DownloadAttempt(submission.requestId(), submission.workType(), submission.workId());
                var uri = URI.create("https://www.pixiv.net/ajax/novel/" + submission.workId() + "?lang=zh");
                var response = mapper.createObjectNode().put("error", false);
                response.putObject("body").put("id", submission.workId()).put("userId", "7")
                        .put("userName", "Author").put("title", "Example").put("content", "Text");
                when(client.get(uri, null)).thenReturn(response.toString());
                handler.submit(submission, attempt, null);
                verify(downloads).submit(eq(attempt), any(NovelDownloadRequest.class), isNull());
            }
        }
    }
}

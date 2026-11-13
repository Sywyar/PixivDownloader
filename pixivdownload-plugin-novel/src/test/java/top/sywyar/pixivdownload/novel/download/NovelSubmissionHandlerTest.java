package top.sywyar.pixivdownload.novel.download;

import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
}

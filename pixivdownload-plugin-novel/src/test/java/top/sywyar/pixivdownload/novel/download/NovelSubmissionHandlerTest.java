package top.sywyar.pixivdownload.novel.download;

import com.fasterxml.jackson.databind.ObjectMapper;
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
    @Test @DisplayName("服务端取得正文后按同一任务身份入队，拒绝正文注入和非法格式")
    void fetchesContentAndRejectsInjectedOptions() {
        var client = mock(PixivAjaxClient.class);
        var downloads = mock(NovelDownloadService.class);
        var handler = new NovelSubmissionHandler(client, new ObjectMapper(), downloads);
        var uri = URI.create("https://www.pixiv.net/ajax/novel/42?lang=zh");
        when(client.get(uri, "credential")).thenReturn("""
                {"error":false,"body":{"id":"42","title":"Story","content":"Text","userId":"7","userName":"Author"}}
                """);
        var attempt = new DownloadAttempt(UUID.randomUUID(), "novel", "42");
        handler.submit(new DownloadSubmission(UUID.randomUUID(), "novel", "42", Map.of("format", "epub")), attempt, "credential");
        var request = ArgumentCaptor.forClass(NovelDownloadRequest.class);
        verify(downloads).submit(eq(attempt), request.capture(), isNull());
        verify(client).get(uri, "credential");
        assertThat(request.getValue().getContent()).isEqualTo("Text");
        assertThat(request.getValue().getOther().getFormat()).isEqualTo("epub");
        assertThat(request.getValue().getOther().getRawMetaJson()).doesNotContain("content");
        for (var options : java.util.List.of(Map.of("content", "forged"), Map.of("format", "unknown"))) {
            assertThatThrownBy(() -> handler.submit(new DownloadSubmission(UUID.randomUUID(), "novel", "42", options), attempt, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoMoreInteractions(client, downloads);
    }
}

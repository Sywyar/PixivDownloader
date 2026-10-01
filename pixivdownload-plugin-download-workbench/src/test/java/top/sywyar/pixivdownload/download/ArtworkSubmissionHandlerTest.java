package top.sywyar.pixivdownload.download;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import top.sywyar.pixivdownload.download.request.DownloadRequest;
import top.sywyar.pixivdownload.plugin.api.download.lifecycle.DownloadAttempt;
import top.sywyar.pixivdownload.plugin.api.download.submission.DownloadSubmission;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("插画公开任务提交")
class ArtworkSubmissionHandlerTest {
    @Test @DisplayName("作品元数据由受控客户端取得，保留任务身份及输出选择")
    void delegatesVerifiedMetadataAndIdentity() throws Exception {
        var fetch = mock(PixivFetchService.class);
        var executor = mock(ArtworkDownloadExecutor.class);
        var meta = new PixivFetchService.ArtworkMeta(1, "Manga", 1, false, 7L, "Author",
                null, null, 2, 2, List.of(), "Description", null);
        var raw = new ObjectMapper().readTree("{\"id\":\"42\",\"title\":\"Manga\"}");
        when(fetch.fetchArtworkMetaCapture("42", "credential")).thenReturn(new PixivFetchService.ArtworkMetaCapture(meta, raw));
        var urls = List.of("https://i.pximg.net/first.jpg", "https://i.pximg.net/second.jpg");
        when(fetch.resolveArtworkPages("42", "credential")).thenReturn(new PixivFetchService.ArtworkPages(urls, raw));
        var attempt = new DownloadAttempt(UUID.randomUUID(), "artwork", "42");
        new ArtworkSubmissionHandler(fetch, executor).submit(new DownloadSubmission(UUID.randomUUID(), "artwork", "42",
                Map.of("imageFormats", "png")), attempt, "credential");
        var other = ArgumentCaptor.forClass(DownloadRequest.Other.class);
        verify(executor).submitImages(eq(attempt), eq(42L), eq("Manga"), eq(urls),
                eq("https://www.pixiv.net/artworks/42"), other.capture(), eq("credential"), isNull());
        assertThat(other.getValue().getAuthorId()).isEqualTo(7L);
        assertThat(other.getValue().getImageFormats()).isEqualTo("png");
        assertThat(other.getValue().getRawMetaJson()).contains("Manga");
        verify(fetch).fetchArtworkMetaCapture("42", "credential");
        verify(fetch).resolveArtworkPages("42", "credential");
        assertThatThrownBy(() -> new ArtworkSubmissionHandler(fetch, executor).submit(
                new DownloadSubmission(UUID.randomUUID(), "artwork", "42", Map.of("title", "forged")), attempt, null))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoMoreInteractions(fetch, executor);
    }
}

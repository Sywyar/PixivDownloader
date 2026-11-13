package top.sywyar.pixivdownload.download;

import tools.jackson.databind.json.JsonMapper;
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
        var raw = JsonMapper.builderWithJackson2Defaults().build().readTree("{\"id\":\"42\",\"title\":\"Manga\"}");
        when(fetch.fetchArtworkMetaCapture("42", "credential")).thenReturn(new PixivFetchService.ArtworkMetaCapture(meta, raw));
        var urls = List.of("https://i.pximg.net/first.jpg", "https://i.pximg.net/second.jpg");
        when(fetch.resolveArtworkPages("42", "credential")).thenReturn(new PixivFetchService.ArtworkPages(urls, raw));
        var attempt = new DownloadAttempt(UUID.randomUUID(), "artwork", "42");
        new ArtworkSubmissionHandler(fetch, executor).submit(new DownloadSubmission(UUID.randomUUID(), "artwork", "42",
                Map.of("imageFormats", "png", "mediaQuality", "83", "mediaMaximumEdge", "1080",
                        "mediaWebpLossless", "true", "bookmark", "true", "collectionId", "9",
                        "acquisitionMode", "search")), attempt, "credential");
        var other = ArgumentCaptor.forClass(DownloadRequest.Other.class);
        verify(executor).submitImages(eq(attempt), eq(42L), eq("Manga"), eq(urls),
                eq("https://www.pixiv.net/artworks/42"), other.capture(), eq("credential"), isNull());
        assertThat(other.getValue().getAuthorId()).isEqualTo(7L);
        assertThat(other.getValue().getImageFormats()).isEqualTo("png");
        assertThat(other.getValue().getMediaQuality()).isEqualTo(83);
        assertThat(other.getValue().getMediaMaximumEdge()).isEqualTo(1080);
        assertThat(other.getValue().isMediaWebpLossless()).isTrue();
        assertThat(other.getValue().isBookmark()).isTrue();
        assertThat(other.getValue().getCollectionId()).isEqualTo(9);
        assertThat(other.getValue().getAcquisitionMode()).isEqualTo("search");
        assertThat(other.getValue().getRawMetaJson()).contains("Manga");
        verify(fetch).fetchArtworkMetaCapture("42", "credential");
        verify(fetch).resolveArtworkPages("42", "credential");
        assertThatThrownBy(() -> new ArtworkSubmissionHandler(fetch, executor).submit(
                new DownloadSubmission(UUID.randomUUID(), "artwork", "42", Map.of("title", "forged")), attempt, null))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoMoreInteractions(fetch, executor);
    }

    @Test @DisplayName("非法输出选项和超长用户名在读取上游前拒绝")
    void invalidOptionsAreRejectedBeforeFetching() {
        var fetch = mock(PixivFetchService.class);
        var executor = mock(ArtworkDownloadExecutor.class);
        var handler = new ArtworkSubmissionHandler(fetch, executor);
        var attempt = new DownloadAttempt(UUID.randomUUID(), "artwork", "42");
        for (var options : List.of(Map.of("bookmark", "yes"), Map.of("mediaQuality", "0"),
                Map.of("mediaMaximumEdge", "-1"), Map.of("collectionId", "0"), Map.of("mediaWebpLossless", "1"),
                Map.of("username", "x".repeat(513)))) {
            assertThatThrownBy(() -> handler.submit(new DownloadSubmission(UUID.randomUUID(), "artwork", "42", options), attempt, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(fetch, executor);
    }
}

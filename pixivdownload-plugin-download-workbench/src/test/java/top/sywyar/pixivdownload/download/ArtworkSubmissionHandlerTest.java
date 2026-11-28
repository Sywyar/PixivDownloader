package top.sywyar.pixivdownload.download;

import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import top.sywyar.pixivdownload.core.pixiv.PixivAjaxException;
import top.sywyar.pixivdownload.core.pixiv.PixivAjaxFailure;
import top.sywyar.pixivdownload.plugin.api.download.submission.DownloadTaskException;
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
    @Test void onlyMissingPrimaryMetadataIdentifiesAMissingWork() throws Exception {
        var fetch = mock(PixivFetchService.class);
        var executor = mock(ArtworkDownloadExecutor.class);
        var handler = new ArtworkSubmissionHandler(fetch, executor);
        var submission = new DownloadSubmission(UUID.randomUUID(), "artwork", "42", Map.of());
        var attempt = new DownloadAttempt(UUID.randomUUID(), "artwork", "42");
        var notFound = new PixivAjaxException(PixivAjaxFailure.HTTP_STATUS, 404);
        var meta = mock(PixivFetchService.ArtworkMetaCapture.class, RETURNS_DEEP_STUBS);
        var limited = new PixivAjaxException(PixivAjaxFailure.HTTP_STATUS, 429, 12_345);
        when(fetch.fetchArtworkMetaCapture("42", null)).thenThrow(notFound).thenThrow(limited).thenReturn(meta);
        when(fetch.resolveArtworkPages("42", null)).thenThrow(limited).thenThrow(notFound);
        assertThatExceptionOfType(DownloadTaskException.class).isThrownBy(() -> handler.submit(submission, attempt, null))
                .extracting(DownloadTaskException::code).isEqualTo(DownloadTaskException.Code.WORK_NOT_FOUND);
        for (int i = 0; i < 2; i++) {
            var failure = handler.submitAndReportFailure(submission, attempt, null);
            assertThat(failure).isEqualTo(new DownloadTaskException.Failure(
                    DownloadTaskException.Code.UPSTREAM_RATE_LIMITED, null, null, 12_345));
        }
        assertThatThrownBy(() -> handler.submit(submission, attempt, null)).isSameAs(notFound);
        verifyNoInteractions(executor);
    }

    @Test @DisplayName("作品元数据由受控客户端取得，保留任务身份及输出选择")
    void delegatesVerifiedMetadataAndIdentity() throws Exception {
        var fetch = mock(PixivFetchService.class);
        var executor = mock(ArtworkDownloadExecutor.class);
        var meta = new PixivFetchService.ArtworkMeta(1, "Manga", 1, false, 7L, "Author",
                null, null, 2, 2, List.of(), "Description", null);
        var raw = JsonMapper.builderWithJackson2Defaults().build().readTree("{\"id\":\"42\",\"title\":\"Manga\",\"uploadDate\":\"2025-06-13T00:00:00Z\"}");
        when(fetch.fetchArtworkMetaCapture("42", "credential")).thenReturn(new PixivFetchService.ArtworkMetaCapture(meta, raw));
        var urls = List.of("https://i.pximg.net/first.jpg", "https://i.pximg.net/second.jpg");
        when(fetch.resolveArtworkPages("42", "credential")).thenReturn(new PixivFetchService.ArtworkPages(urls, raw));
        var attempt = new DownloadAttempt(UUID.randomUUID(), "artwork", "42");
        new ArtworkSubmissionHandler(fetch, executor).submit(new DownloadSubmission(UUID.randomUUID(), "artwork", "42",
                Map.of("imageFormats", "png", "mediaQuality", "83", "mediaMaximumEdge", "1080",
                        "mediaWebpLossless", "true", "bookmark", "true", "collectionId", "9",
                        "acquisitionMode", "search", "pages", "1")), attempt, "credential");
        var other = ArgumentCaptor.forClass(DownloadRequest.Other.class);
        verify(executor).submitImages(eq(attempt), eq(42L), eq("Manga"), eq(urls),
                eq("https://www.pixiv.net/artworks/42"), other.capture(), eq("credential"), isNull());
        assertThat(other.getValue().getAuthorId()).isEqualTo(7L);
        assertThat(other.getValue().getSelectedPages()).containsExactly(1);
        assertThat(other.getValue().getSourceUploadDate()).isEqualTo("2025-06-13T00:00:00Z");
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
                Map.of("pages", ""), Map.of("pages", "-1"), Map.of("pages", "1,1"), Map.of("pages", "0,99999999999"),
                Map.of("username", "x".repeat(513)))) {
            assertThatThrownBy(() -> handler.submit(new DownloadSubmission(UUID.randomUUID(), "artwork", "42", options), attempt, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        var failure = handler.submitAndReportFailure(new DownloadSubmission(UUID.randomUUID(), "artwork", "42",
                Map.of("pages", "0,0")), attempt, null);
        assertThat(failure.code()).isEqualTo(DownloadTaskException.Code.REJECTED);
        assertThat(failure.field()).isEqualTo("/options/pages");
        assertThat(failure.reason()).isEqualTo("duplicate");
        verifyNoInteractions(fetch, executor);
    }

    @Test
    void incompleteUpstreamPageListCannotShiftFollowingPageIndices() {
        var json = JsonMapper.builderWithJackson2Defaults().build();
        assertThatThrownBy(() -> PixivFetchService.originalPageUrls(json.readTree("""
                [{"urls":{"original":"first"}},{"urls":{}},{"urls":{"original":"third"}}]
                """))).isInstanceOf(java.io.IOException.class);
    }

    @Test @DisplayName("提供方发布的表单列全选项，文档示例可实际入队")
    void publishedSubmissionExamplesReachDownloadQueue() throws Exception {
        var mapper = JsonMapper.builderWithJackson2Defaults().build();
        var fetch = mock(PixivFetchService.class);
        var executor = mock(ArtworkDownloadExecutor.class);
        var handler = new ArtworkSubmissionHandler(fetch, executor);
        try (var stream = handler.getClass().getResourceAsStream(handler.inputSchemaResource())) {
            assertThat(stream).isNotNull();
            var schema = mapper.readTree(stream);
            var choices = schema.at("/properties/options/properties");
            assertThat(choices.propertyNames()).containsExactlyInAnyOrder(
                    "fileNameTemplate", "pathOverflowAction", "bookmark", "collectionId", "acquisitionMode", "username",
                    "imageFormats", "ugoiraFormats", "mediaQuality", "mediaWebpLossless", "mediaMaximumEdge", "pages");
            assertThat(schema.path("examples").size()).isPositive();
            for (var example : schema.path("examples")) {
                var submission = mapper.treeToValue(example, DownloadSubmission.class);
                assertThat(submission.workType()).isEqualTo(handler.workType());
                assertThat(choices.propertyNames()).containsAll(submission.options().keySet());
                var attempt = new DownloadAttempt(submission.requestId(), submission.workType(), submission.workId());
                var meta = new PixivFetchService.ArtworkMeta(0, "Example", 0, false, 7L, "Author",
                        null, null, 0, 1, List.of(), "", null);
                var body = mapper.createObjectNode().put("id", submission.workId());
                var urls = List.of("https://i.pximg.net/example.jpg");
                when(fetch.fetchArtworkMetaCapture(submission.workId(), null))
                        .thenReturn(new PixivFetchService.ArtworkMetaCapture(meta, body));
                when(fetch.resolveArtworkPages(submission.workId(), null))
                        .thenReturn(new PixivFetchService.ArtworkPages(urls, body));
                handler.submit(submission, attempt, null);
                verify(executor).submitImages(eq(attempt), eq(Long.parseLong(submission.workId())), eq("Example"),
                        eq(urls), anyString(), any(DownloadRequest.Other.class), isNull(), isNull());
            }
        }
    }
}

package top.sywyar.pixivdownload.novel.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import top.sywyar.pixivdownload.config.MultiModeSettings;
import top.sywyar.pixivdownload.core.pixiv.PixivAjaxClient;
import top.sywyar.pixivdownload.core.pixiv.PixivProxyAccessPolicy;
import top.sywyar.pixivdownload.core.quota.VisitorDownloadQuotaService;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.novel.browser.NovelBrowserFetchTicketStore;
import top.sywyar.pixivdownload.novel.download.NovelDownloadService;
import top.sywyar.pixivdownload.novel.export.NovelMergeService;
import top.sywyar.pixivdownload.novel.translation.NovelAutoTranslateService;
import top.sywyar.pixivdownload.novel.translation.NovelTranslationService;
import top.sywyar.pixivdownload.novelgallery.NovelGalleryService;
import top.sywyar.pixivdownload.core.work.model.WorkRestriction;
import top.sywyar.pixivdownload.core.work.model.WorkVisibilityScope;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentityResolver;
import top.sywyar.pixivdownload.setup.ApplicationModeProvider;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("小说系列合订与附件下载")
class NovelDownloadControllerMergeDownloadTest {

    @Mock private NovelDownloadService novelDownloadService;
    @Mock private NovelAutoTranslateService novelAutoTranslateService;
    @Mock private NovelDatabase novelDatabase;
    @Mock private NovelGalleryService novelGalleryService;
    @Mock private NovelMergeService novelMergeService;
    @Mock private NovelTranslationService novelTranslationService;
    @Mock private ApplicationModeProvider applicationModeProvider;
    @Mock private RequestOwnerIdentityResolver requestOwnerIdentityResolver;
    @Mock private VisitorDownloadQuotaService visitorDownloadQuotaService;
    @Mock private MultiModeSettings multiModeSettings;
    @Mock private MessageResolver messages;

    private NovelDownloadController controller() {
        return new NovelDownloadController(
                novelDownloadService,
                novelAutoTranslateService,
                novelDatabase,
                novelGalleryService,
                novelMergeService,
                novelTranslationService,
                applicationModeProvider,
                requestOwnerIdentityResolver,
                visitorDownloadQuotaService,
                multiModeSettings,
                new ObjectMapper(),
                mock(PixivAjaxClient.class),
                mock(PixivProxyAccessPolicy.class),
                messages,
                new NovelBrowserFetchTicketStore());
    }

    @ParameterizedTest
    @MethodSource("mergeFormats")
    @DisplayName("合订与附件下载采用相同格式语义，保留 Unicode 文件名、MIME 和文件字节")
    void returnsOriginalAsAttachment(String format, NovelDownloadService.NovelFormat expected, String mime)
            throws Exception {
        Path tmp = Files.createTempFile("章节 合订", "." + expected.ext());
        byte[] payload = "EPUB-bytes".getBytes(StandardCharsets.UTF_8);
        Files.write(tmp, payload);
        try {
            when(novelMergeService.merge(eq(42L), eq(expected)))
                    .thenReturn(new NovelMergeService.MergeResult(true, "ok", tmp.toString(), 3));

            ResponseEntity<byte[]> resp = controller().downloadMergedSeries(
                    42L, format, null, WorkVisibilityScope.unrestricted());

            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            assertThat(resp.getBody()).isEqualTo(payload);

            HttpHeaders headers = resp.getHeaders();
            assertThat(headers.getContentType()).isEqualTo(MediaType.parseMediaType(mime));
            String disposition = headers.getFirst(HttpHeaders.CONTENT_DISPOSITION);
            assertThat(disposition).startsWith("attachment;");
            assertThat(disposition).contains("filename=\"__ __");
            assertThat(disposition).contains("filename*=UTF-8''%E7%AB%A0%E8%8A%82%20%E5%90%88%E8%AE%A2");
            assertThat(disposition.chars().allMatch(c -> c >= 0x20 && c <= 0x7e)).isTrue();
            assertThat(headers.getCacheControl()).isEqualTo("no-store");

            var merged = controller().mergeSeries(42L, format, " \t ");
            assertThat(merged.getStatusCode().value()).isEqualTo(200);
            assertThat(merged.getBody()).isEqualTo(new NovelDownloadController.MergeResponse(
                    true, "ok", tmp.toString(), 3, expected.ext()));

            verify(novelMergeService, times(2)).merge(eq(42L), eq(expected));
            verify(novelMergeService, never())
                    .mergeVariant(eq(42L), eq(expected), org.mockito.ArgumentMatchers.anyString());
            verifyNoInteractions(novelDatabase);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static Stream<Arguments> mergeFormats() {
        return Stream.of(
                Arguments.of(null, NovelDownloadService.NovelFormat.EPUB, "application/epub+zip"),
                Arguments.of("", NovelDownloadService.NovelFormat.EPUB, "application/epub+zip"),
                Arguments.of(" \t ", NovelDownloadService.NovelFormat.EPUB, "application/epub+zip"),
                Arguments.of(" EPUB ", NovelDownloadService.NovelFormat.EPUB, "application/epub+zip"),
                Arguments.of(" HTML ", NovelDownloadService.NovelFormat.HTML, "text/html; charset=UTF-8"),
                Arguments.of("txt", NovelDownloadService.NovelFormat.TXT, "text/plain; charset=UTF-8"),
                Arguments.of("unknown", NovelDownloadService.NovelFormat.TXT, "text/plain; charset=UTF-8"));
    }

    @Test
    @DisplayName("传入 lang 时只重生该语言变体，不再触发原文基准合订")
    void delegatesToMergeVariantWhenLangProvided() throws Exception {
        Path tmp = Files.createTempFile("novel-series-merged-", ".epub");
        Files.write(tmp, new byte[]{1, 2, 3});
        try {
            when(novelMergeService.mergeVariant(
                    eq(7L), eq(NovelDownloadService.NovelFormat.EPUB), eq("zh-CN")))
                    .thenReturn(new NovelMergeService.MergeResult(true, "ok", tmp.toString(), 1));

            ResponseEntity<byte[]> resp = controller().downloadMergedSeries(
                    7L, "epub", " zh-CN ", WorkVisibilityScope.unrestricted());

            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            assertThat(controller().mergeSeries(7L, "epub", " zh-CN ").getBody())
                    .isEqualTo(new NovelDownloadController.MergeResponse(true, "ok", tmp.toString(), 1, "epub"));
            verify(novelMergeService, times(2)).mergeVariant(
                    eq(7L), eq(NovelDownloadService.NovelFormat.EPUB), eq("zh-CN"));
            verify(novelMergeService, never())
                    .merge(eq(7L), eq(NovelDownloadService.NovelFormat.EPUB));
            verifyNoInteractions(novelDatabase);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    @DisplayName("MergeService 返回失败时回 404，不读硬盘也不写响应体")
    void returns404WhenMergeFails() throws Exception {
        when(novelMergeService.merge(eq(9L), eq(NovelDownloadService.NovelFormat.EPUB)))
                .thenReturn(new NovelMergeService.MergeResult(false, "no-chapters", null, 0));

        ResponseEntity<byte[]> resp = controller().downloadMergedSeries(
                9L, null, null, WorkVisibilityScope.unrestricted());

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        assertThat(resp.getBody()).isNull();
    }

    @Test
    @DisplayName("MergeService 报成功但磁盘上文件缺失时也回 404")
    void returns404WhenFileMissing() throws Exception {
        Path tmp = Files.createTempFile("novel-series-merged-missing-", ".epub");
        Files.delete(tmp);
        when(novelMergeService.merge(eq(11L), eq(NovelDownloadService.NovelFormat.EPUB)))
                .thenReturn(new NovelMergeService.MergeResult(true, "ok", tmp.toString(), 1));

        ResponseEntity<byte[]> resp = controller().downloadMergedSeries(
                11L, null, null, WorkVisibilityScope.unrestricted());

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        verifyNoInteractions(novelDatabase);
    }

    @Test
    @DisplayName("受邀访客的系列可见性限制应交给小说插件目录服务")
    void delegatesSeriesVisibilityToOwnedCatalog() throws Exception {
        WorkRestriction restriction = new WorkRestriction(
                Set.of(0, 1), false, List.of(21L), true, List.of());
        WorkVisibilityScope scope = WorkVisibilityScope.restricted(restriction, restriction);
        when(novelGalleryService.visibleSeriesIds(restriction)).thenReturn(Set.of(8L));

        ResponseEntity<byte[]> resp = controller().downloadMergedSeries(7L, null, null, scope);

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        verify(novelGalleryService).visibleSeriesIds(restriction);
        verifyNoInteractions(novelMergeService);
    }
}

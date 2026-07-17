package top.sywyar.pixivdownload.novel.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import top.sywyar.pixivdownload.config.MultiModeSettings;
import top.sywyar.pixivdownload.core.metadata.GuestRestriction;
import top.sywyar.pixivdownload.core.metadata.novel.NovelGalleryRepository;
import top.sywyar.pixivdownload.core.metadata.novel.NovelSeriesSummary;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.novel.download.NovelDownloadService;
import top.sywyar.pixivdownload.novel.export.NovelMergeService;
import top.sywyar.pixivdownload.novel.translation.NovelAutoTranslateService;
import top.sywyar.pixivdownload.novel.translation.NovelTranslationService;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkRestriction;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkType;
import top.sywyar.pixivdownload.plugin.api.work.service.WorkVisibilityService;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentityResolver;
import top.sywyar.pixivdownload.quota.UserQuotaService;
import top.sywyar.pixivdownload.setup.ApplicationModeProvider;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("NovelDownloadController#downloadMergedSeries 单元测试")
class NovelDownloadControllerMergeDownloadTest {

    @Mock private NovelDownloadService novelDownloadService;
    @Mock private NovelAutoTranslateService novelAutoTranslateService;
    @Mock private NovelDatabase novelDatabase;
    @Mock private NovelGalleryRepository novelGalleryRepository;
    @Mock private NovelMergeService novelMergeService;
    @Mock private NovelTranslationService novelTranslationService;
    @Mock private ApplicationModeProvider applicationModeProvider;
    @Mock private RequestOwnerIdentityResolver requestOwnerIdentityResolver;
    @Mock private WorkVisibilityService workVisibilityService;
    @Mock private UserQuotaService userQuotaService;
    @Mock private MultiModeSettings multiModeSettings;
    @Mock private MessageResolver messages;

    private NovelDownloadController controller() {
        return new NovelDownloadController(
                novelDownloadService,
                novelAutoTranslateService,
                novelDatabase,
                novelGalleryRepository,
                novelMergeService,
                novelTranslationService,
                applicationModeProvider,
                requestOwnerIdentityResolver,
                workVisibilityService,
                userQuotaService,
                multiModeSettings,
                messages);
    }

    @Test
    @DisplayName("lang 为空时按原文基准合订并以 EPUB MIME + RFC 5987 attachment 头流式回传文件字节")
    void streamsOriginalEpubAsAttachment() throws Exception {
        Path tmp = Files.createTempFile("novel-series-merged-", ".epub");
        byte[] payload = "EPUB-bytes".getBytes(StandardCharsets.UTF_8);
        Files.write(tmp, payload);
        try {
            when(novelMergeService.merge(eq(42L), eq(NovelDownloadService.NovelFormat.EPUB)))
                    .thenReturn(new NovelMergeService.MergeResult(true, "ok", tmp.toString(), 3));

            HttpServletRequest req = mock(HttpServletRequest.class);
            ResponseEntity<byte[]> resp = controller().downloadMergedSeries(42L, null, null, req);

            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            assertThat(resp.getBody()).isEqualTo(payload);

            HttpHeaders headers = resp.getHeaders();
            assertThat(headers.getContentType()).isEqualTo(MediaType.parseMediaType("application/epub+zip"));
            String disposition = headers.getFirst(HttpHeaders.CONTENT_DISPOSITION);
            assertThat(disposition).startsWith("attachment;");
            assertThat(disposition).contains("filename=\"" + tmp.getFileName() + "\"");
            assertThat(disposition).contains("filename*=UTF-8''");

            verify(novelMergeService, times(1))
                    .merge(eq(42L), eq(NovelDownloadService.NovelFormat.EPUB));
            verify(novelMergeService, never())
                    .mergeVariant(eq(42L), eq(NovelDownloadService.NovelFormat.EPUB), eq("zh-CN"));
        } finally {
            Files.deleteIfExists(tmp);
        }
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

            HttpServletRequest req = mock(HttpServletRequest.class);
            ResponseEntity<byte[]> resp = controller().downloadMergedSeries(7L, "epub", "zh-CN", req);

            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            verify(novelMergeService).mergeVariant(
                    eq(7L), eq(NovelDownloadService.NovelFormat.EPUB), eq("zh-CN"));
            verify(novelMergeService, never())
                    .merge(eq(7L), eq(NovelDownloadService.NovelFormat.EPUB));
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    @DisplayName("MergeService 返回失败时回 404，不读硬盘也不写响应体")
    void returns404WhenMergeFails() throws Exception {
        when(novelMergeService.merge(eq(9L), eq(NovelDownloadService.NovelFormat.EPUB)))
                .thenReturn(new NovelMergeService.MergeResult(false, "no-chapters", null, 0));

        HttpServletRequest req = mock(HttpServletRequest.class);
        ResponseEntity<byte[]> resp = controller().downloadMergedSeries(9L, null, null, req);

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

        HttpServletRequest req = mock(HttpServletRequest.class);
        ResponseEntity<byte[]> resp = controller().downloadMergedSeries(11L, null, null, req);

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        verifyNoInteractions(novelDatabase);
    }

    @Test
    @DisplayName("受邀访客的系列可见性限制应按纯值适配后再查询宿主仓库")
    void adaptsWorkRestrictionForSeriesVisibility() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        WorkRestriction restriction = new WorkRestriction(
                Set.of(0, 1), false, List.of(21L), true, List.of());
        when(workVisibilityService.restrictionFrom(req, WorkType.NOVEL)).thenReturn(restriction);
        when(novelGalleryRepository.findVisibleNovelSeriesCounts(any()))
                .thenReturn(List.of(new NovelSeriesSummary(8L, "可见系列", null, null, 1)));

        ResponseEntity<byte[]> resp = controller().downloadMergedSeries(7L, null, null, req);

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        ArgumentCaptor<GuestRestriction> captor = ArgumentCaptor.forClass(GuestRestriction.class);
        verify(novelGalleryRepository).findVisibleNovelSeriesCounts(captor.capture());
        assertThat(captor.getValue().allowedXRestricts()).isEqualTo(restriction.allowedXRestricts());
        assertThat(captor.getValue().tagUnrestricted()).isEqualTo(restriction.tagUnrestricted());
        assertThat(captor.getValue().tagIds()).isEqualTo(restriction.tagIds());
        assertThat(captor.getValue().authorUnrestricted()).isEqualTo(restriction.authorUnrestricted());
        assertThat(captor.getValue().authorIds()).isEqualTo(restriction.authorIds());
        verifyNoInteractions(novelMergeService);
    }
}

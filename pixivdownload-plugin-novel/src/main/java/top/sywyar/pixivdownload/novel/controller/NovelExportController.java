package top.sywyar.pixivdownload.novel.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.novel.download.NovelDownloadService.NovelFormat;
import top.sywyar.pixivdownload.novel.export.NovelExportService;
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;
import top.sywyar.pixivdownload.translation.api.TranslationVariant;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** 从已保存的小说生成 EPUB 附件，不创建归档任务或临时文件。 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class NovelExportController {
    static final int MAX_DOCUMENT_BYTES = 64 * 1024 * 1024;
    private final NovelExportService exports;
    private final MessageResolver messages;

    @GetMapping("/api/novel/{novelId}/export")
    public ResponseEntity<?> download(@PathVariable long novelId, @RequestParam(required = false) String variant) {
        try {
            if (novelId <= 0) throw new IllegalArgumentException();
            if (variant != null && !variant.isBlank()) TranslationVariant.parse(variant);
        } catch (IllegalArgumentException invalid) {
            log.info("Novel attachment rejected: novelId={}, reason=invalid_request", novelId);
            return error(400, "invalid-request");
        }
        try {
            var exported = exports.export(novelId, NovelFormat.EPUB, variant);
            if (exported == null) return error(404, "unavailable");
            if (exported.content().length > MAX_DOCUMENT_BYTES) {
                log.warn("Novel attachment rejected: novelId={}, reason=document_too_large, bytes={}",
                        novelId, exported.content().length);
                return error(413, "single-too-large");
            }
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("application/epub+zip"))
                    .cacheControl(CacheControl.noStore())
                    .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                            .filename(exported.filename(), StandardCharsets.UTF_8).build().toString())
                    .body(exported.content());
        } catch (IOException | RuntimeException failure) {
            // 渲染服务记录失败原因；HTTP 只返回受控的用户提示。
            return error(500, "failed");
        }
    }

    private ResponseEntity<ApiErrorResponse> error(int status, String reason) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(ApiErrorResponse.of("novel.export." + reason, messages.get("novel.export." + reason)));
    }
}

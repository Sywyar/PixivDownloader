package top.sywyar.pixivdownload.novel.export;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import top.sywyar.pixivdownload.core.pixiv.filename.PixivWorkFileNameFormatter;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.core.work.service.WorkAssetService;
import top.sywyar.pixivdownload.core.work.service.WorkFileLock;
import top.sywyar.pixivdownload.core.work.service.WorkMetadataRepository;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.novel.download.NovelDownloadDocumentWriter;
import top.sywyar.pixivdownload.novel.download.NovelDownloadService.NovelFormat;
import top.sywyar.pixivdownload.novel.request.NovelDownloadRequest;
import top.sywyar.pixivdownload.translation.api.TranslationVariant;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 从本地正文生成单篇附件；不发布文件或改变作品记录。 */
@Slf4j
@RequiredArgsConstructor
public class NovelExportService {
    private final NovelDatabase novels;
    private final WorkMetadataRepository works;
    private final WorkAssetService assets;
    private final MessageResolver messages;

    public record Export(String filename, byte[] content) {}

    public Export export(long novelId, NovelFormat format, String variant) throws IOException {
        String key = variant == null || variant.isBlank() ? "" : TranslationVariant.parse(variant).key();
        String runId = UUID.randomUUID().toString();
        long started = System.nanoTime();
        log.info("Novel export started: runId={}, novelId={}, format={}, translated={}",
                runId, novelId, format.ext(), !key.isEmpty());
        try (var ignored = WorkFileLock.acquire(WorkType.NOVEL, novelId)) {
            var novel = novels.getNovel(novelId);
            if (novel == null || novel.deleted() || novel.rawContent() == null
                    || (!key.isEmpty() && !novels.hasTranslation(novelId, key))) {
                log.info("Novel export skipped: runId={}, novelId={}, reason=content_unavailable", runId, novelId);
                return null;
            }
            String title = novel.title();
            String body = novel.rawContent();
            String description = novel.description();
            if (!key.isEmpty()) {
                title = translatedOr(novels.getTranslationTitle(novelId, key), title);
                body = translatedOr(novels.getTranslationContent(novelId, key), body);
                description = translatedOr(novels.getTranslationDescription(novelId, key), description);
            }
            var metadata = new NovelDownloadRequest.Other();
            metadata.setLanguage(key.isEmpty() ? novel.xLanguage() : TranslationVariant.parse(key).language());
            metadata.setDescription(description);
            metadata.setUploadTimestamp(novel.uploadTime());
            metadata.setSeriesId(novel.seriesId());
            metadata.setSeriesOrder(novel.seriesOrder());
            works.find(WorkType.NOVEL, novelId).ifPresent(work -> {
                metadata.setAuthorName(work.authorName());
                metadata.setSeriesTitle(work.seriesTitle());
                metadata.setTags(work.tags());
            });
            Map<String, String> images = new LinkedHashMap<>();
            Path directory = novel.folder() == null || novel.folder().isBlank() ? null : Path.of(novel.folder());
            var cover = format == NovelFormat.EPUB
                    ? assets.existingThumbnail(WorkType.NOVEL, novelId, 0).orElse(null) : null;
            if ((format == NovelFormat.EPUB || format == NovelFormat.HTML) && directory != null) {
                for (String id : novels.getNovelImageIds(novelId)) {
                    String extension = novels.getNovelImageExt(novelId, id);
                    if (extension != null && !extension.isBlank()) images.put(id, extension);
                }
            }
            byte[] content = new NovelDownloadDocumentWriter(messages).render(format, novelId, title, metadata,
                    body, directory, cover == null ? null : cover.path(), cover == null ? null : cover.extension(), images);
            String baseName = key.isEmpty()
                    ? PixivWorkFileNameFormatter.normalizeBaseName(title, String.valueOf(novelId))
                    : PixivWorkFileNameFormatter.normalizeBaseNameWithSuffix(title, "_" + key, novelId + "_" + key);
            log.info("Novel export finished: runId={}, novelId={}, format={}, bytes={}, durationMs={}",
                    runId, novelId, format.ext(), content.length, (System.nanoTime() - started) / 1_000_000);
            return new Export(baseName + "." + format.ext(), content);
        } catch (IOException | RuntimeException failure) {
            log.error("Novel export failed: runId={}, novelId={}, format={}, exceptionType={}, durationMs={}",
                    runId, novelId, format.ext(), failure.getClass().getSimpleName(),
                    (System.nanoTime() - started) / 1_000_000);
            throw failure;
        }
    }

    private static String translatedOr(String translated, String original) {
        return translated == null || translated.isBlank() ? original : translated;
    }
}

package top.sywyar.pixivdownload.novel.export;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.author.AuthorService;
import top.sywyar.pixivdownload.core.db.ArtworkFileNameFormatter;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.core.db.TagDto;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.core.metadata.NovelRecord;
import top.sywyar.pixivdownload.core.metadata.NovelSeries;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import top.sywyar.pixivdownload.novel.download.NovelDownloadService;
import top.sywyar.pixivdownload.novel.download.NovelMarkupParser;

/**
 * 把同一个小说系列内已下载的章节按 {@code series_order} 升序合订成一份输出文件。
 * 单章文件本身不动；合订文件落在 {@code {rootFolder}/novel-series-{seriesId}/} 目录。
 *
 * <p>支持 TXT/HTML/EPUB 三种格式；**推荐 EPUB**：只有 EPUB 容器能内嵌封面/插图、
 * 承载跨小说的「小说→章节」多级目录与系列元数据。TXT/HTML 为纯文本/单页备选。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NovelMergeService {

    private final DownloadConfig downloadConfig;
    private final NovelDatabase novelDatabase;
    private final AuthorService authorService;
    private final AppMessages messages;

    public record MergeResult(boolean success, String message, String mergedPath, int chapterCount) {}

    // 按系列串行化合订写盘：同一系列的「原文基准 + 各语言变体」合订共用文件区，下载链路 base 合订与
    // 自动翻译 per-job 译文合订可能并发触发同一系列；不串行会并发写同名输出文件而损坏。锁按 seriesId 隔离，
    // 不同系列互不阻塞；synchronized 可重入，使 mergeVariant 空 langCode 委托 merge 时不自锁。
    private final ConcurrentHashMap<Long, Object> seriesMergeLocks = new ConcurrentHashMap<>();

    private Object mergeLockFor(long seriesId) {
        return seriesMergeLocks.computeIfAbsent(seriesId, k -> new Object());
    }

    /**
     * 合订整个系列：先生成原文基准合订本 {@code {title}.{ext}}，再为该系列已存在译文的每种语言重新生成
     * 语言变体合订本（best-effort）。译文章节会被替换，未翻译章节仍以原文出现。返回原文基准合订本的结果。
     */
    public MergeResult merge(long seriesId, NovelDownloadService.NovelFormat format) throws IOException {
        synchronized (mergeLockFor(seriesId)) {
            MergeResult base = writeMerge(seriesId, format, null);
            if (base.success()) {
                for (String lang : novelDatabase.getSeriesTranslatedLangs(seriesId)) {
                    try {
                        writeMerge(seriesId, format, lang);
                    } catch (Exception e) {
                        log.warn("novel series variant merge failed: seriesId={}, lang={}, err={}",
                                seriesId, lang, e.getMessage());
                    }
                }
            }
            return base;
        }
    }

    /**
     * 仅生成某语言变体合订本 {@code {title}_{lang}.{ext}}（用于「翻译整个系列」完成后重生该语言）。
     * {@code langCode} 为空时退化为 {@link #merge}（原文基准 + 全部变体）。
     */
    public MergeResult mergeVariant(long seriesId, NovelDownloadService.NovelFormat format,
                                    String langCode) throws IOException {
        if (langCode == null || langCode.isBlank()) {
            return merge(seriesId, format);
        }
        synchronized (mergeLockFor(seriesId)) {
            return writeMerge(seriesId, format, langCode.trim());
        }
    }

    /**
     * 写出一份合订本。{@code langCode} 为 {@code null} 时是原文基准合订本（文件名 {@code {title}.{ext}}）；
     * 非空时是语言变体合订本（文件名 {@code {title}_{lang}.{ext}}），逐章优先取该语言译文、缺失回退原文。
     */
    private MergeResult writeMerge(long seriesId, NovelDownloadService.NovelFormat format,
                                   String langCode) throws IOException {
        if (seriesId <= 0) {
            return new MergeResult(false, messages.get("novel.merge.invalid-series-id"), null, 0);
        }
        List<NovelRecord> chapters = novelDatabase.getNovelsBySeriesId(seriesId);
        if (chapters.isEmpty()) {
            return new MergeResult(false, messages.get("novel.merge.no-chapters"), null, 0);
        }

        NovelSeries series = novelDatabase.getSeries(seriesId);
        String originalSeriesTitle = series == null || series.title() == null || series.title().isBlank()
                ? String.valueOf(seriesId) : series.title();
        boolean variant = langCode != null && !langCode.isBlank();
        // 系列名：变体取该语言已落库的系列名译文（缺失回退原文），原文基准直接取原文。
        String seriesTitle = variant
                ? resolveSeriesTitleForLang(seriesId, langCode, originalSeriesTitle)
                : originalSeriesTitle;
        // 文件名：变体使用译后系列名 + 语言代码后缀；后缀保留作为同系列多语言变体的去重 tie-breaker，
        // 避免两种译文恰好同名时互相覆盖（例如 zh-CN / zh-TW 译名一致）。
        // 长标题必须用 normalizeBaseNameWithSuffix 保留后缀，否则后缀会被 180 字符上限截掉、
        // 变体路径退化为原文合订本路径并互相覆盖 / 误删。
        String safeTitle = variant
                ? ArtworkFileNameFormatter.normalizeBaseNameWithSuffix(
                seriesTitle, "_" + langCode, seriesId + "_" + langCode)
                : ArtworkFileNameFormatter.normalizeBaseName(seriesTitle, String.valueOf(seriesId));
        Path outDir = Paths.get(downloadConfig.getRootFolder())
                .resolve("novel-series-" + seriesId);
        Files.createDirectories(outDir);
        Path outFile = outDir.resolve(safeTitle + "." + format.ext());
        // 原文基准合订本的路径：用于在清理变体遗留时识别"看起来是变体名、其实长标题截断后等于原文合订本"
        // 的退化情况，避免误删原文合订本。
        Path originalBaseFile = outDir.resolve(
                ArtworkFileNameFormatter.normalizeBaseName(originalSeriesTitle, String.valueOf(seriesId))
                        + "." + format.ext());

        switch (format) {
            case TXT -> writeTxt(outFile, seriesTitle, chapters, langCode);
            case HTML -> writeHtml(outFile, seriesTitle, chapters, langCode);
            case EPUB -> writeEpub(outFile, seriesId, seriesTitle,
                    resolveSeriesDescriptionForLang(seriesId, langCode, series),
                    chapters, series, langCode);
        }
        if (!variant) {
            cleanupLegacyMerge(outDir, originalSeriesTitle, seriesId, format.ext(), outFile);
        } else {
            // 早期变体合订本固定使用「原系列名 + 语言后缀」；现在改为译后系列名 + 语言后缀，
            // 当译名 ≠ 原名时清理掉旧版基于原名的同语言合订本，避免遗留两份。
            cleanupLegacyVariantMerge(outDir, originalSeriesTitle, seriesId, langCode,
                    format.ext(), outFile, originalBaseFile);
        }
        log.info("novel series merged: seriesId={}, format={}, lang={}, file={}",
                seriesId, format.ext(), langCode == null ? "-" : langCode, outFile);
        return new MergeResult(true, messages.get("novel.merge.success"), outFile.toString(), chapters.size());
    }

    /** 逐章取用的正文：变体取该语言译文（缺失回退原文），原文基准直接取 {@code raw_content}。 */
    private String contentOf(NovelRecord r, String langCode) {
        if (langCode != null && !langCode.isBlank()) {
            String translated = novelDatabase.getTranslationContent(r.novelId(), langCode);
            if (translated != null && !translated.isBlank()) {
                return translated;
            }
        }
        return r.rawContent() == null ? "" : r.rawContent();
    }

    /** 逐章取用的章节标题：变体取该语言译文标题（缺失回退原文标题），原文基准直接取原标题。 */
    private String chapterTitleOf(NovelRecord r, String langCode) {
        String fallback = r.title() == null || r.title().isBlank() ? "#" + r.novelId() : r.title();
        if (langCode == null || langCode.isBlank()) return fallback;
        String translated = novelDatabase.getTranslationTitle(r.novelId(), langCode);
        return translated == null || translated.isBlank() ? fallback : translated;
    }

    /** 变体合订的系列名：DB 中存在该语言系列名译文则采用，否则回退原系列名。 */
    private String resolveSeriesTitleForLang(long seriesId, String langCode, String fallback) {
        String translated = novelDatabase.getSeriesTitleTranslation(seriesId, langCode);
        return translated == null || translated.isBlank() ? fallback : translated;
    }

    /**
     * 变体合订的系列简介（写入 EPUB 元数据）：DB 中存在该语言简介译文则采用，否则回退原文。
     * 原文基准合订（{@code langCode == null}）一律用原文。
     */
    private String resolveSeriesDescriptionForLang(long seriesId, String langCode, NovelSeries series) {
        String original = series == null ? null : series.description();
        if (langCode == null || langCode.isBlank()) return original;
        String translated = novelDatabase.getSeriesDescriptionTranslation(seriesId, langCode);
        return translated == null || translated.isBlank() ? original : translated;
    }

    /**
     * 兼容旧版命名：早期变体合订本固定使用「原系列名 + 语言代码后缀」（{@code {originalTitle}_{lang}.{ext}}）。
     * 现在变体合订本使用「译后系列名 + 语言代码后缀」（{@code {translatedTitle}_{lang}.{ext}}）；当译名与原名
     * 不同时旧文件不会被新写出覆盖，本方法在写出新文件后将其删除以避免遗留两份。
     *
     * <p>语言后缀通过 {@link ArtworkFileNameFormatter#normalizeBaseNameWithSuffix} 强制保留，避免长标题
     * 把后缀截掉后退化为原文合订本路径；同时显式排除 {@code newFile} 与 {@code originalBaseFile}，作为
     * 兜底防护。
     */
    private void cleanupLegacyVariantMerge(Path outDir, String originalSeriesTitle, long seriesId,
                                           String langCode, String ext, Path newFile,
                                           Path originalBaseFile) {
        String legacyName = ArtworkFileNameFormatter.normalizeBaseNameWithSuffix(
                originalSeriesTitle, "_" + langCode, seriesId + "_" + langCode);
        Path legacy = outDir.resolve(legacyName + "." + ext);
        if (legacy.equals(newFile) || legacy.equals(originalBaseFile)) return;
        try {
            if (Files.deleteIfExists(legacy)) {
                log.info("removed legacy variant merged file: {}", legacy);
            }
        } catch (IOException e) {
            log.warn("failed to remove legacy variant merged file {}: {}", legacy, e.getMessage());
        }
    }

    /**
     * 兼容旧版命名：早期原文基准合订本带「合订 / merged」后缀（{@code {title}_合订.{ext}}）。
     * 新版去掉该后缀（{@code {title}.{ext}}），写出新文件后删除遗留的旧后缀文件，避免重复留存。
     *
     * <p>同样通过 {@link ArtworkFileNameFormatter#normalizeBaseNameWithSuffix} 保留后缀，避免长标题
     * 把后缀截掉后路径退化为新版合订本路径并被误删。
     */
    private void cleanupLegacyMerge(Path outDir, String seriesTitle, long seriesId,
                                    String ext, Path newFile) {
        for (String suffix : List.of(messages.get("novel.merge.suffix"), "合订", "merged")) {
            if (suffix == null || suffix.isBlank()) continue;
            String legacyName = ArtworkFileNameFormatter.normalizeBaseNameWithSuffix(
                    seriesTitle, "_" + suffix, String.valueOf(seriesId));
            Path legacy = outDir.resolve(legacyName + "." + ext);
            if (legacy.equals(newFile)) continue;
            try {
                if (Files.deleteIfExists(legacy)) {
                    log.info("removed legacy merged file: {}", legacy);
                }
            } catch (IOException e) {
                log.warn("failed to remove legacy merged file {}: {}", legacy, e.getMessage());
            }
        }
    }

    private void writeTxt(Path file, String seriesTitle, List<NovelRecord> chapters,
                          String langCode) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(seriesTitle).append("\n\n");
        for (NovelRecord r : chapters) {
            String chapterTitle = chapterTitleOf(r, langCode);
            sb.append("\n\n=== ").append(chapterTitle).append(" ===\n\n");
            String body = NovelMarkupParser.render(
                    contentOf(r, langCode),
                    NovelMarkupParser.Format.TXT,
                    imageLabels());
            sb.append(body);
        }
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }

    private void writeHtml(Path file, String seriesTitle, List<NovelRecord> chapters,
                           String langCode) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"UTF-8\">\n<title>")
                .append(escapeHtml(seriesTitle)).append("</title>\n<style>\n")
                .append("body{font-family:serif;line-height:1.7;max-width:42em;margin:2em auto;padding:0 1em;}\n")
                .append("h1{font-size:2em;}h2{margin-top:3em;border-bottom:1px solid #ccc;padding-bottom:0.5em;}\n")
                .append("ruby rt{font-size:0.6em;}\n")
                .append(".novel-image-placeholder{color:#888;}\n.novel-jump{color:#888;font-size:0.85em;}\n")
                .append("</style>\n</head>\n<body>\n<h1>").append(escapeHtml(seriesTitle)).append("</h1>\n");
        for (NovelRecord r : chapters) {
            String chapterTitle = chapterTitleOf(r, langCode);
            sb.append("<h2>").append(escapeHtml(chapterTitle)).append("</h2>\n");
            sb.append(NovelMarkupParser.render(
                    contentOf(r, langCode),
                    NovelMarkupParser.Format.HTML,
                    imageLabels()));
        }
        sb.append("</body>\n</html>\n");
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }

    private void writeEpub(Path file, long seriesId, String seriesTitle, String seriesDescription,
                           List<NovelRecord> chapters, NovelSeries series,
                           String langCode) throws IOException {
        List<NovelEpubWriter.Chapter> epubChapters = new ArrayList<>();
        List<NovelEpubWriter.NavEntry> nav = new ArrayList<>();
        // Pixiv uploadedimage id 全局唯一，跨章节用同一张 OEBPS/images/embed_{id}.{ext}
        Map<String, NovelEpubWriter.ImageResource> imagesById = new LinkedHashMap<>();
        String firstLang = "ja";
        for (NovelRecord r : chapters) {
            String novelTitle = chapterTitleOf(r, langCode);
            collectChapterImages(r, imagesById);
            // 每本小说内部再按 [chapter:] 拆分，形成「小说 → 章节」两级目录
            List<NovelMarkupParser.Segment> segments = NovelMarkupParser.splitChapters(
                    contentOf(r, langCode));
            int firstIndex = epubChapters.size();
            List<NovelEpubWriter.NavEntry> children = new ArrayList<>();
            for (NovelMarkupParser.Segment seg : segments) {
                String segTitle = (seg.title() != null && !seg.title().isBlank())
                        ? seg.title() : novelTitle;
                String body = NovelMarkupParser.render(
                        seg.raw(), NovelMarkupParser.Format.XHTML,
                        epubImageResolver(imagesById), imageLabels());
                int idx = epubChapters.size();
                epubChapters.add(new NovelEpubWriter.Chapter(segTitle, body));
                children.add(new NovelEpubWriter.NavEntry(segTitle, idx));
            }
            // 单段小说不再嵌套一层冗余子节点
            if (children.size() <= 1) {
                nav.add(new NovelEpubWriter.NavEntry(novelTitle, firstIndex));
            } else {
                nav.add(new NovelEpubWriter.NavEntry(novelTitle, firstIndex, children));
            }
            if (firstLang.equals("ja") && r.xLanguage() != null && !r.xLanguage().isBlank()) {
                firstLang = r.xLanguage();
            }
        }
        // 变体合订本以译文语言作为 EPUB 语言；标识符附加语言代码，区别于原文基准合订本
        boolean variant = langCode != null && !langCode.isBlank();
        String epubLang = variant ? langCode : firstLang;
        String identifier = variant
                ? "urn:pixiv:novel-series:" + seriesId + ":" + langCode
                : "urn:pixiv:novel-series:" + seriesId;
        byte[] epub = NovelEpubWriter.write(seriesTitle, resolveSeriesAuthor(series, chapters), epubLang,
                identifier, epubChapters, nav,
                new ArrayList<>(imagesById.values()),
                readSeriesCover(series),
                buildSeriesMetadata(seriesId, seriesTitle, seriesDescription),
                epubLabels());
        Files.write(file, epub);
    }

    private String resolveSeriesAuthor(NovelSeries series, List<NovelRecord> chapters) {
        List<Long> authorIds = new ArrayList<>();
        if (series != null && series.authorId() != null && series.authorId() > 0) {
            authorIds.add(series.authorId());
        }
        for (NovelRecord chapter : chapters) {
            if (chapter.authorId() != null && chapter.authorId() > 0
                    && !authorIds.contains(chapter.authorId())) {
                authorIds.add(chapter.authorId());
            }
        }
        Map<Long, String> names = authorService.getAuthorNames(authorIds);
        if (series != null && series.authorId() != null) {
            String seriesAuthor = names.get(series.authorId());
            if (seriesAuthor != null && !seriesAuthor.isBlank()) {
                return seriesAuthor;
            }
        }
        for (Long authorId : authorIds) {
            String name = names.get(authorId);
            if (name != null && !name.isBlank()) {
                return name;
            }
        }
        return "";
    }

    /**
     * 系列合订本的 OPF 元数据：系列简介、标签、Pixiv 系列源链接、系列归组。
     * {@code seriesDescription} 由调用方按变体语言解析（变体取译后简介、原文基准取原文）。
     */
    private NovelEpubWriter.Metadata buildSeriesMetadata(long seriesId, String seriesTitle,
                                                         String seriesDescription) {
        List<String> subjects = novelDatabase.getNovelSeriesTags(seriesId).stream()
                .map(TagDto::getName)
                .filter(n -> n != null && !n.isBlank())
                .toList();
        String source = "https://www.pixiv.net/novel/series/" + seriesId;
        return new NovelEpubWriter.Metadata(seriesDescription, null, subjects, source, seriesTitle, null);
    }

    /**
     * 系列封面 {@code {coverFolder}/cover.{coverExt}} 读回字节，内嵌进合订 EPUB。
     * Best-effort：无封面记录 / 读失败一律返回 null（合订本不带封面页）。
     */
    private NovelEpubWriter.Cover readSeriesCover(NovelSeries series) {
        if (series == null || series.coverExt() == null || series.coverExt().isBlank()
                || series.coverFolder() == null || series.coverFolder().isBlank()) {
            return null;
        }
        Path cover = Paths.get(series.coverFolder()).resolve("cover." + series.coverExt());
        try {
            return new NovelEpubWriter.Cover(series.coverExt(), Files.readAllBytes(cover));
        } catch (IOException ex) {
            log.warn("merge epub cover read failed, skipped: {} — {}", cover, ex.getMessage());
            return null;
        }
    }

    /** 把某章节 novel_images 记录到的内嵌图（已落盘 {@code {folder}/embed_{id}.{ext}}）读进 {@code sink}。 */
    private void collectChapterImages(NovelRecord r, Map<String, NovelEpubWriter.ImageResource> sink) {
        if (r.folder() == null || r.folder().isBlank()) return;
        for (String id : novelDatabase.getNovelImageIds(r.novelId())) {
            if (sink.containsKey(id)) continue;
            String ext = novelDatabase.getNovelImageExt(r.novelId(), id);
            if (ext == null || ext.isBlank()) continue;
            Path img = Paths.get(r.folder()).resolve("embed_" + id + "." + ext);
            try {
                sink.put(id, new NovelEpubWriter.ImageResource(id, ext, Files.readAllBytes(img)));
            } catch (IOException ex) {
                log.warn("merge epub embed image read failed, skipped: {} — {}", img, ex.getMessage());
            }
        }
    }

    private static NovelMarkupParser.ImageResolver epubImageResolver(
            Map<String, NovelEpubWriter.ImageResource> imagesById) {
        return new NovelMarkupParser.ImageResolver() {
            @Override public String uploadedImage(String id) {
                NovelEpubWriter.ImageResource img = imagesById.get(id);
                return img == null ? null : img.href();
            }
            @Override public String pixivImage(String id) { return null; }
        };
    }

    private NovelMarkupParser.ImageLabels imageLabels() {
        return new NovelMarkupParser.ImageLabels() {
            @Override public String uploadedImage(String id) {
                return messages.get("novel.render.uploaded-image", id);
            }

            @Override public String pixivImage(String id) {
                return messages.get("novel.render.pixiv-image", id);
            }
        };
    }

    private NovelEpubWriter.Labels epubLabels() {
        return new NovelEpubWriter.Labels() {
            @Override public String untitled() {
                return messages.get("novel.epub.untitled");
            }

            @Override public String unknownAuthor() {
                return messages.get("novel.epub.unknown-author");
            }

            @Override public String chapter(int index) {
                return messages.get("novel.epub.chapter", index);
            }
        };
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }
}

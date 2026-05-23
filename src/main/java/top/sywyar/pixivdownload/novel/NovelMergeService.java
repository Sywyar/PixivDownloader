package top.sywyar.pixivdownload.novel;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.author.AuthorService;
import top.sywyar.pixivdownload.download.ArtworkFileNameFormatter;
import top.sywyar.pixivdownload.download.config.DownloadConfig;
import top.sywyar.pixivdownload.download.db.TagDto;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.novel.db.NovelRecord;
import top.sywyar.pixivdownload.novel.db.NovelSeries;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    public MergeResult merge(long seriesId, NovelDownloadService.NovelFormat format) throws IOException {
        if (seriesId <= 0) {
            return new MergeResult(false, messages.get("novel.merge.invalid-series-id"), null, 0);
        }
        List<NovelRecord> chapters = novelDatabase.getNovelsBySeriesId(seriesId);
        if (chapters.isEmpty()) {
            return new MergeResult(false, messages.get("novel.merge.no-chapters"), null, 0);
        }

        NovelSeries series = novelDatabase.getSeries(seriesId);
        String seriesTitle = series == null || series.title() == null || series.title().isBlank()
                ? String.valueOf(seriesId) : series.title();
        String safeTitle = ArtworkFileNameFormatter.normalizeBaseName(
                seriesTitle + "_" + messages.get("novel.merge.suffix"), String.valueOf(seriesId));
        Path outDir = Paths.get(downloadConfig.getRootFolder())
                .resolve("novel-series-" + seriesId);
        Files.createDirectories(outDir);
        Path outFile = outDir.resolve(safeTitle + "." + format.ext());

        switch (format) {
            case TXT -> writeTxt(outFile, seriesTitle, chapters);
            case HTML -> writeHtml(outFile, seriesTitle, chapters);
            case EPUB -> writeEpub(outFile, seriesId, seriesTitle, chapters, series);
        }
        log.info("novel series merged: seriesId={}, format={}, file={}",
                seriesId, format.ext(), outFile);
        return new MergeResult(true, messages.get("novel.merge.success"), outFile.toString(), chapters.size());
    }

    private void writeTxt(Path file, String seriesTitle, List<NovelRecord> chapters) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(seriesTitle).append("\n\n");
        for (NovelRecord r : chapters) {
            String chapterTitle = r.title() == null || r.title().isBlank()
                    ? "#" + r.novelId() : r.title();
            sb.append("\n\n=== ").append(chapterTitle).append(" ===\n\n");
            String body = NovelMarkupParser.render(
                    r.rawContent() == null ? "" : r.rawContent(),
                    NovelMarkupParser.Format.TXT,
                    imageLabels());
            sb.append(body);
        }
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }

    private void writeHtml(Path file, String seriesTitle, List<NovelRecord> chapters) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"UTF-8\">\n<title>")
                .append(escapeHtml(seriesTitle)).append("</title>\n<style>\n")
                .append("body{font-family:serif;line-height:1.7;max-width:42em;margin:2em auto;padding:0 1em;}\n")
                .append("h1{font-size:2em;}h2{margin-top:3em;border-bottom:1px solid #ccc;padding-bottom:0.5em;}\n")
                .append("ruby rt{font-size:0.6em;}\n")
                .append(".novel-image-placeholder{color:#888;}\n.novel-jump{color:#888;font-size:0.85em;}\n")
                .append("</style>\n</head>\n<body>\n<h1>").append(escapeHtml(seriesTitle)).append("</h1>\n");
        for (NovelRecord r : chapters) {
            String chapterTitle = r.title() == null || r.title().isBlank()
                    ? "#" + r.novelId() : r.title();
            sb.append("<h2>").append(escapeHtml(chapterTitle)).append("</h2>\n");
            sb.append(NovelMarkupParser.render(
                    r.rawContent() == null ? "" : r.rawContent(),
                    NovelMarkupParser.Format.HTML,
                    imageLabels()));
        }
        sb.append("</body>\n</html>\n");
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }

    private void writeEpub(Path file, long seriesId, String seriesTitle,
                           List<NovelRecord> chapters, NovelSeries series) throws IOException {
        List<NovelEpubWriter.Chapter> epubChapters = new ArrayList<>();
        List<NovelEpubWriter.NavEntry> nav = new ArrayList<>();
        // Pixiv uploadedimage id 全局唯一，跨章节用同一张 OEBPS/images/embed_{id}.{ext}
        Map<String, NovelEpubWriter.ImageResource> imagesById = new LinkedHashMap<>();
        String firstLang = "ja";
        for (NovelRecord r : chapters) {
            String novelTitle = r.title() == null || r.title().isBlank()
                    ? "#" + r.novelId() : r.title();
            collectChapterImages(r, imagesById);
            // 每本小说内部再按 [chapter:] 拆分，形成「小说 → 章节」两级目录
            List<NovelMarkupParser.Segment> segments = NovelMarkupParser.splitChapters(
                    r.rawContent() == null ? "" : r.rawContent());
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
        byte[] epub = NovelEpubWriter.write(seriesTitle, resolveSeriesAuthor(series, chapters), firstLang,
                "urn:pixiv:novel-series:" + seriesId, epubChapters, nav,
                new ArrayList<>(imagesById.values()),
                readSeriesCover(series), buildSeriesMetadata(seriesId, seriesTitle, series),
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

    /** 系列合订本的 OPF 元数据：系列简介、标签、Pixiv 系列源链接、系列归组。 */
    private NovelEpubWriter.Metadata buildSeriesMetadata(long seriesId, String seriesTitle,
                                                         NovelSeries series) {
        String description = series == null ? null : series.description();
        List<String> subjects = novelDatabase.getNovelSeriesTags(seriesId).stream()
                .map(TagDto::getName)
                .filter(n -> n != null && !n.isBlank())
                .toList();
        String source = "https://www.pixiv.net/novel/series/" + seriesId;
        return new NovelEpubWriter.Metadata(description, null, subjects, source, seriesTitle, null);
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

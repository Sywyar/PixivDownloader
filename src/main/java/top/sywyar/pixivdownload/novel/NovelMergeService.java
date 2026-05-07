package top.sywyar.pixivdownload.novel;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.download.ArtworkFileNameFormatter;
import top.sywyar.pixivdownload.download.config.DownloadConfig;
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
import java.util.List;

/**
 * 把同一个小说系列内已下载的章节按 {@code series_order} 升序合订成一份输出文件。
 * 单章文件本身不动；合订文件落在 {@code {rootFolder}/novel-series-{seriesId}/} 目录。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NovelMergeService {

    private final DownloadConfig downloadConfig;
    private final NovelDatabase novelDatabase;
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
            case EPUB -> writeEpub(outFile, seriesTitle, chapters);
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

    private void writeEpub(Path file, String seriesTitle, List<NovelRecord> chapters) throws IOException {
        List<NovelEpubWriter.Chapter> epubChapters = new ArrayList<>();
        String firstAuthor = null;
        String firstLang = "ja";
        for (NovelRecord r : chapters) {
            String chapterTitle = r.title() == null || r.title().isBlank()
                    ? "#" + r.novelId() : r.title();
            String body = NovelMarkupParser.render(
                    r.rawContent() == null ? "" : r.rawContent(),
                    NovelMarkupParser.Format.XHTML,
                    imageLabels());
            epubChapters.add(new NovelEpubWriter.Chapter(chapterTitle, body));
            if (firstLang.equals("ja") && r.xLanguage() != null && !r.xLanguage().isBlank()) {
                firstLang = r.xLanguage();
            }
        }
        byte[] epub = NovelEpubWriter.write(seriesTitle, firstAuthor == null ? "" : firstAuthor,
                firstLang, epubChapters, epubLabels());
        Files.write(file, epub);
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

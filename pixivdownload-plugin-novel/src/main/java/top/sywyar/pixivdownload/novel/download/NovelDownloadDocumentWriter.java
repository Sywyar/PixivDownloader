package top.sywyar.pixivdownload.novel.download;

import lombok.extern.slf4j.Slf4j;
import top.sywyar.pixivdownload.core.time.EpochMillisNormalizer;
import top.sywyar.pixivdownload.core.work.model.WorkTag;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.novel.export.NovelEpubWriter;
import top.sywyar.pixivdownload.novel.request.NovelDownloadRequest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 将单本小说写出为 TXT、HTML 或 EPUB。 */
@Slf4j
final class NovelDownloadDocumentWriter {

    private final MessageResolver messages;

    NovelDownloadDocumentWriter(MessageResolver messages) {
        this.messages = messages;
    }

    void write(
            NovelDownloadService.NovelFormat format,
            long novelId,
            String title,
            NovelDownloadRequest.Other other,
            String raw,
            Path downloadPath,
            String baseName,
            String coverExt,
            Map<String, String> embeddedExts
    ) throws IOException {
        Path file = downloadPath.resolve(baseName + "." + format.ext());
        switch (format) {
            case TXT -> writeTxt(file, raw);
            case HTML -> writeHtml(file, title, raw, other, localFolderResolver(embeddedExts));
            case EPUB -> writeEpub(
                    file,
                    novelId,
                    title,
                    other,
                    raw,
                    downloadPath,
                    baseName,
                    coverExt,
                    embeddedExts
            );
        }
    }

    private void writeTxt(Path file, String raw) throws IOException {
        String text = NovelMarkupParser.render(raw, NovelMarkupParser.Format.TXT, imageLabels());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private void writeHtml(
            Path file,
            String title,
            String raw,
            NovelDownloadRequest.Other other,
            NovelMarkupParser.ImageResolver resolver
    ) throws IOException {
        String body = NovelMarkupParser.render(raw, NovelMarkupParser.Format.HTML, resolver, imageLabels());
        StringBuilder html = new StringBuilder()
                .append("<!DOCTYPE html>\n")
                .append("<html lang=\"")
                .append(escapeHtml(NovelEpubWriter.normalizeLanguageTag(other.getLanguage())))
                .append("\">\n<head>\n<meta charset=\"UTF-8\">\n<title>")
                .append(escapeHtml(title))
                .append("</title>\n<style>\n")
                .append("body{font-family:serif;line-height:1.7;max-width:42em;margin:2em auto;padding:0 1em;}\n")
                .append("h1,h2{font-weight:700;}\n")
                .append("figure.novel-image{text-align:center;margin:1em 0;max-width:100%;}\n")
                .append("figure.novel-image img{display:block;margin:0 auto;max-width:90%;height:auto;}\n")
                .append(".novel-image-placeholder{color:#888;}\n.novel-jump{color:#888;font-size:0.85em;}\n")
                .append("ruby rt{font-size:0.6em;}\n")
                .append("</style>\n</head>\n<body>\n<h1>").append(escapeHtml(title)).append("</h1>\n")
                .append(body)
                .append("</body>\n</html>\n");
        Files.writeString(file, html.toString(), StandardCharsets.UTF_8);
    }

    private void writeEpub(
            Path file,
            long novelId,
            String title,
            NovelDownloadRequest.Other other,
            String raw,
            Path downloadPath,
            String baseName,
            String coverExt,
            Map<String, String> embeddedExts
    ) throws IOException {
        NovelMarkupParser.ImageResolver resolver = epubImageResolver(embeddedExts);
        List<NovelMarkupParser.Segment> segments = NovelMarkupParser.splitChapters(raw);
        List<NovelEpubWriter.Chapter> chapters = new ArrayList<>();
        List<NovelEpubWriter.NavEntry> nav = new ArrayList<>();
        for (int index = 0; index < segments.size(); index++) {
            NovelMarkupParser.Segment segment = segments.get(index);
            String chapterTitle = segment.title() != null && !segment.title().isBlank()
                    ? segment.title() : title;
            String body = NovelMarkupParser.render(
                    segment.raw(),
                    NovelMarkupParser.Format.XHTML,
                    resolver,
                    imageLabels()
            );
            chapters.add(new NovelEpubWriter.Chapter(chapterTitle, body));
            nav.add(new NovelEpubWriter.NavEntry(chapterTitle, index));
        }
        byte[] epub = NovelEpubWriter.write(
                title,
                other.getAuthorName(),
                other.getLanguage(),
                "urn:pixiv:novel:" + novelId,
                chapters,
                nav,
                readEmbeddedImages(downloadPath, embeddedExts),
                readCover(downloadPath, baseName, coverExt),
                buildNovelMetadata(novelId, other),
                epubLabels()
        );
        Files.write(file, epub);
    }

    private NovelEpubWriter.Metadata buildNovelMetadata(long novelId, NovelDownloadRequest.Other other) {
        String isoDate = null;
        if (other.getUploadTimestamp() != null) {
            isoDate = Instant.ofEpochMilli(EpochMillisNormalizer.normalize(other.getUploadTimestamp()))
                    .toString().replaceAll("\\.\\d+Z$", "Z");
        }
        List<String> subjects = other.getTags() == null ? List.of()
                : other.getTags().stream()
                        .map(WorkTag::name)
                        .filter(name -> name != null && !name.isBlank())
                        .toList();
        String collectionTitle = null;
        String collectionPosition = null;
        if (other.getSeriesId() != null && other.getSeriesId() > 0) {
            collectionTitle = other.getSeriesTitle() != null && !other.getSeriesTitle().isBlank()
                    ? other.getSeriesTitle() : "series-" + other.getSeriesId();
            if (other.getSeriesOrder() != null) {
                collectionPosition = String.valueOf(other.getSeriesOrder());
            }
        }
        return new NovelEpubWriter.Metadata(
                other.getDescription(),
                isoDate,
                subjects,
                "https://www.pixiv.net/novel/show.php?id=" + novelId,
                collectionTitle,
                collectionPosition
        );
    }

    private NovelEpubWriter.Cover readCover(Path downloadPath, String baseName, String coverExt) {
        if (coverExt == null || coverExt.isBlank()) {
            return null;
        }
        Path cover = downloadPath.resolve(baseName + "_thumb." + coverExt);
        try {
            return new NovelEpubWriter.Cover(coverExt, Files.readAllBytes(cover));
        } catch (IOException e) {
            log.warn("epub cover read failed, skipped: {} — {}", cover, e.getMessage());
            return null;
        }
    }

    private List<NovelEpubWriter.ImageResource> readEmbeddedImages(
            Path downloadPath,
            Map<String, String> embeddedExts
    ) {
        if (embeddedExts == null || embeddedExts.isEmpty()) {
            return List.of();
        }
        List<NovelEpubWriter.ImageResource> images = new ArrayList<>();
        for (Map.Entry<String, String> entry : embeddedExts.entrySet()) {
            Path image = downloadPath.resolve("embed_" + entry.getKey() + "." + entry.getValue());
            try {
                images.add(new NovelEpubWriter.ImageResource(
                        entry.getKey(),
                        entry.getValue(),
                        Files.readAllBytes(image)
                ));
            } catch (IOException e) {
                log.warn("epub embed image read failed, skipped: {} — {}", image, e.getMessage());
            }
        }
        return images;
    }

    private NovelMarkupParser.ImageLabels imageLabels() {
        return new NovelMarkupParser.ImageLabels() {
            @Override
            public String uploadedImage(String id) {
                return messages.get("novel.render.uploaded-image", id);
            }

            @Override
            public String pixivImage(String id) {
                return messages.get("novel.render.pixiv-image", id);
            }
        };
    }

    private NovelEpubWriter.Labels epubLabels() {
        return new NovelEpubWriter.Labels() {
            @Override
            public String untitled() {
                return messages.get("novel.epub.untitled");
            }

            @Override
            public String unknownAuthor() {
                return messages.get("novel.epub.unknown-author");
            }

            @Override
            public String chapter(int index) {
                return messages.get("novel.epub.chapter", index);
            }
        };
    }

    private static NovelMarkupParser.ImageResolver localFolderResolver(Map<String, String> extensions) {
        if (extensions == null || extensions.isEmpty()) {
            return NovelMarkupParser.ImageResolver.NONE;
        }
        return new NovelMarkupParser.ImageResolver() {
            @Override
            public String uploadedImage(String id) {
                String extension = extensions.get(id);
                return extension == null ? null : "embed_" + id + "." + extension;
            }

            @Override
            public String pixivImage(String id) {
                return null;
            }
        };
    }

    private static NovelMarkupParser.ImageResolver epubImageResolver(Map<String, String> extensions) {
        if (extensions == null || extensions.isEmpty()) {
            return NovelMarkupParser.ImageResolver.NONE;
        }
        return new NovelMarkupParser.ImageResolver() {
            @Override
            public String uploadedImage(String id) {
                String extension = extensions.get(id);
                return extension == null ? null : "images/embed_" + id + "." + extension;
            }

            @Override
            public String pixivImage(String id) {
                return null;
            }
        };
    }

    private static String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}

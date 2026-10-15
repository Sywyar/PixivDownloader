package top.sywyar.pixivdownload.novel.download;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.novel.request.NovelDownloadRequest;
import top.sywyar.pixivdownload.novel.testsupport.NovelTestMessages;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("小说下载文档写出")
class NovelDownloadDocumentWriterTest {

    @Test
    @DisplayName("按请求格式写出正文并把本地图片装入 HTML 与 EPUB")
    void writesRequestedFormatsWithLocalImages(@TempDir Path tempDir) throws Exception {
        NovelDownloadDocumentWriter writer = new NovelDownloadDocumentWriter(
                NovelTestMessages.messageResolver()
        );
        NovelDownloadRequest.Other other = new NovelDownloadRequest.Other();
        other.setAuthorName("作者");
        other.setLanguage("zh");
        String raw = "[chapter:第一章]\n正文[uploadedimage:7][uploadedimage:8][pixivimage:9]";
        Map<String, String> images = Map.of("7", "png");
        Files.write(tempDir.resolve("book_thumb.jpg"), new byte[]{1, 2, 3});
        Files.write(tempDir.resolve("embed_7.png"), new byte[]{4, 5, 6});

        writer.write(
                NovelDownloadService.NovelFormat.TXT,
                42L,
                "标题 & 测试",
                other,
                raw,
                tempDir,
                "book",
                "jpg",
                images
        );
        writer.write(
                NovelDownloadService.NovelFormat.HTML,
                42L,
                "标题 & 测试",
                other,
                raw,
                tempDir,
                "book",
                "jpg",
                images
        );
        writer.write(
                NovelDownloadService.NovelFormat.EPUB,
                42L,
                "标题 & 测试",
                other,
                raw,
                tempDir,
                "book",
                "jpg",
                images
        );

        assertThat(Files.readString(tempDir.resolve("book.txt"), StandardCharsets.UTF_8))
                .contains("正文");
        assertThat(Files.readString(tempDir.resolve("book.html"), StandardCharsets.UTF_8))
                .contains("<html lang=\"zh-CN\">")
                .contains("<title>标题 &amp; 测试</title>")
                .contains("src=\"embed_7.png\"")
                .contains("data-uploaded-image=\"8\"><span", "data-pixiv-image=\"9\"><span");
        try (ZipFile epub = new ZipFile(tempDir.resolve("book.epub").toFile())) {
            assertThat(epub.getEntry("OEBPS/chapter-1.xhtml")).isNotNull();
            try (var input = epub.getInputStream(epub.getEntry("OEBPS/chapter-1.xhtml"))) {
                assertThat(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                        .contains("src=\"images/embed_7.png\"")
                        .contains("data-uploaded-image=\"8\"><span", "data-pixiv-image=\"9\"><span");
            }
            assertThat(epub.getEntry("OEBPS/images/embed_7.png")).isNotNull();
            assertThat(epub.getEntry("OEBPS/images/cover.jpg")).isNotNull();
        }
    }

    @Test
    @DisplayName("图片映射为空时 HTML 与 EPUB 都保留占位文本")
    void retainsPlaceholdersWithoutImages(@TempDir Path tempDir) throws Exception {
        var writer = new NovelDownloadDocumentWriter(NovelTestMessages.messageResolver());
        for (Map<String, String> images : Arrays.<Map<String, String>>asList(null, Map.of())) {
            for (var format : new NovelDownloadService.NovelFormat[]{
                    NovelDownloadService.NovelFormat.HTML, NovelDownloadService.NovelFormat.EPUB}) {
                writer.write(format, 42L, "title", new NovelDownloadRequest.Other(),
                        "[uploadedimage:7]", tempDir, "empty", null, images);
                String content;
                if (format == NovelDownloadService.NovelFormat.HTML) {
                    content = Files.readString(tempDir.resolve("empty.html"), StandardCharsets.UTF_8);
                } else {
                    try (ZipFile epub = new ZipFile(tempDir.resolve("empty.epub").toFile());
                         var input = epub.getInputStream(epub.getEntry("OEBPS/chapter-1.xhtml"))) {
                        content = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                    }
                }
                assertThat(content).contains("data-uploaded-image=\"7\"><span").doesNotContain("<img ");
            }
        }
    }
}

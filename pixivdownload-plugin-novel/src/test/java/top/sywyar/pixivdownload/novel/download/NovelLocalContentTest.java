package top.sywyar.pixivdownload.novel.download;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("本地小说正文读取")
class NovelLocalContentTest {
    @TempDir Path directory;

    @Test @DisplayName("UTF-8 TXT 保留换行和小说标记，仅移除 BOM，原文件不变")
    void readsTxt() throws Exception {
        Path file = directory.resolve("novel.txt");
        String original = "\uFEFF[chapter:标题]\r\n正文 🌸\n[newpage]\n[[rb:汉字 > 注音]]";
        Files.writeString(file, original, StandardCharsets.UTF_8);
        assertEquals(original.substring(1), NovelLocalContent.read(file, "txt"));
        assertEquals(original, Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test @DisplayName("EPUB 按 spine 顺序读取本地章节，保留文字和标记，不读取外部 DTD")
    void readsEpubInSpineOrder() throws Exception {
        Path file = epub("<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.1//EN\" \"http://127.0.0.1:1/unreachable.dtd\">"
                + "<html><head><title>Ignored title</title></head><body><p>[chapter:章节]</p>"
                + "<p>第一段 &amp; 🌸<br/>换行</p><script>hidden</script></body></html>", true);
        byte[] before = Files.readAllBytes(file);
        String body = NovelLocalContent.read(file, "epub");
        assertTrue(body.indexOf("第一段 & 🌸") < body.indexOf("最后一段"));
        assertTrue(body.contains("[chapter:章节]"));
        assertTrue(body.contains("🌸\n换行"));
        assertFalse(body.contains("Ignored title"));
        assertFalse(body.contains("hidden"));
        assertArrayEquals(before, Files.readAllBytes(file));
    }

    @Test @DisplayName("拒绝外部实体、缺失章节和正文超限，不返回部分内容")
    void rejectsUnsafeAndIncompleteEpub() throws Exception {
        Path secret = Files.writeString(directory.resolve("secret.txt"), "private");
        Path xxe = epub("<!DOCTYPE html [<!ENTITY stolen SYSTEM \"" + secret.toUri() + "\">]>"
                + "<html><body>&stolen;</body></html>", true);
        assertThrows(UncheckedIOException.class, () -> NovelLocalContent.read(xxe, "epub"));
        Path missing = epub("<html><body>first</body></html>", false);
        assertThrows(UncheckedIOException.class, () -> NovelLocalContent.read(missing, "epub"));
        Path noBody = epub("<html><head><title>no body</title></head></html>", true);
        assertThrows(UncheckedIOException.class, () -> NovelLocalContent.read(noBody, "epub"));
        Path oversize = directory.resolve("large.txt");
        try (var out = Files.newOutputStream(oversize)) {
            byte[] block = new byte[8192];
            Arrays.fill(block, (byte) 'a');
            for (int i = 0; i < NovelLocalContent.MAX_TEXT_BYTES / block.length; i++) out.write(block);
            out.write('a');
        }
        assertThrows(UncheckedIOException.class, () -> NovelLocalContent.read(oversize, "txt"));
    }

    @Test @DisplayName("EPUB 累计文本预算和 XML 深度限制均在返回正文前拒绝")
    void rejectsCumulativeXmlAndDeepMarkup() throws Exception {
        Path file = directory.resolve("expanded.epub");
        try (var zip = new ZipOutputStream(Files.newOutputStream(file), StandardCharsets.UTF_8)) {
            byte[] block = new byte[8192];
            Arrays.fill(block, (byte) 'a');
            for (int document = 0; document < 3; document++) {
                zip.putNextEntry(new ZipEntry(document + ".xhtml"));
                for (int i = 0; i < NovelLocalContent.MAX_TEXT_BYTES / block.length; i++) zip.write(block);
                zip.closeEntry();
            }
        }
        var large = assertThrows(UncheckedIOException.class, () -> NovelLocalContent.read(file, "epub"));
        assertEquals("NOVEL_CONTENT_LIMIT", large.getCause().getMessage());
        Path boundary = epub("<html><body>" + "<div>".repeat(98) + "content"
                + "</div>".repeat(98) + "</body></html>", true);
        assertTrue(NovelLocalContent.read(boundary, "epub").contains("content"));
        Path nested = epub("<html><body>" + "<div>".repeat(99) + "content"
                + "</div>".repeat(99) + "</body></html>", true);
        var deep = assertThrows(UncheckedIOException.class, () -> NovelLocalContent.read(nested, "epub"));
        assertEquals("NOVEL_CONTENT_XML", deep.getCause().getMessage());
    }

    @Test @DisplayName("空文本、非法 UTF-8 与已中断读取不登记为空正文")
    void rejectsInvalidTextAndCancellation() throws Exception {
        Path file = directory.resolve("invalid.txt");
        for (byte[] bytes : List.of(new byte[0], new byte[]{(byte) 0xff}, new byte[]{0})) {
            Files.write(file, bytes);
            assertThrows(UncheckedIOException.class, () -> NovelLocalContent.read(file, "txt"));
        }
        Files.writeString(file, "content");
        Thread.currentThread().interrupt();
        try {
            var error = assertThrows(UncheckedIOException.class, () -> NovelLocalContent.read(file, "txt"));
            assertInstanceOf(InterruptedIOException.class, error.getCause());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    @Test @DisplayName("重复 spine 引用按每次解析累计预算，不以少量正文掩盖重复工作")
    void repeatedSpineHasParsingBudget() throws Exception {
        Path file = epub("<html><body><!--" + " ".repeat(8 * 1024 * 1024) + "-->short</body></html>", true,
                "<itemref idref=\"first\"/>".repeat(5));
        var failure = assertThrows(UncheckedIOException.class, () -> NovelLocalContent.read(file, "epub"));
        assertEquals("NOVEL_CONTENT_LIMIT", failure.getCause().getMessage());
    }

    private Path epub(String first, boolean includeLast) throws Exception {
        return epub(first, includeLast, "<itemref idref=\"first\"/><itemref idref=\"last\"/>");
    }

    private Path epub(String first, boolean includeLast, String spine) throws Exception {
        Path file = directory.resolve(UUID.randomUUID() + ".epub");
        try (var zip = new ZipOutputStream(Files.newOutputStream(file), StandardCharsets.UTF_8)) {
            entry(zip, "mimetype", "application/epub+zip");
            entry(zip, "META-INF/container.xml", """
                    <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                    <rootfiles><rootfile full-path="OEBPS/book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>
                    """);
            if (includeLast) entry(zip, "OEBPS/last.xhtml", "<html><body><p>最后一段</p></body></html>");
            entry(zip, "OEBPS/first.xhtml", first);
            entry(zip, "OEBPS/book.opf", """
                    <package xmlns="http://www.idpf.org/2007/opf"><manifest>
                    <item id="last" href="last.xhtml" media-type="application/xhtml+xml"/>
                    <item id="first" href="first.xhtml" media-type="application/xhtml+xml"/>
                    </manifest><spine>%s</spine></package>
                    """.formatted(spine));
        }
        return file;
    }

    private void entry(ZipOutputStream zip, String path, String value) throws Exception {
        zip.putNextEntry(new ZipEntry(path));
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}

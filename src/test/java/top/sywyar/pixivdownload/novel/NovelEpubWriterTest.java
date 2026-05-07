package top.sywyar.pixivdownload.novel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("NovelEpubWriter tests")
class NovelEpubWriterTest {

    @Test
    @DisplayName("EPUB 包含规范的固定文件 + 每个章节一份 XHTML")
    void writesValidEpubStructure() throws Exception {
        byte[] epub = NovelEpubWriter.write("书名 & 测试", "作者 <名>", "ja", List.of(
                new NovelEpubWriter.Chapter("第一章", "<section><p>正文一</p></section>"),
                new NovelEpubWriter.Chapter("第二章", "<section><p>正文二</p></section>")
        ));

        Map<String, EntryInfo> entries = readEntries(epub);

        // mimetype 必须存在且 STORED 写入（不被压缩）
        assertThat(entries).containsKey("mimetype");
        assertThat(entries.get("mimetype").method).isEqualTo(ZipEntry.STORED);
        assertThat(new String(entries.get("mimetype").data, StandardCharsets.US_ASCII))
                .isEqualTo("application/epub+zip");

        // container.xml 指向 OEBPS/content.opf
        String container = new String(entries.get("META-INF/container.xml").data, StandardCharsets.UTF_8);
        assertThat(container).contains("full-path=\"OEBPS/content.opf\"");

        // content.opf 含转义后的 title/creator
        String opf = new String(entries.get("OEBPS/content.opf").data, StandardCharsets.UTF_8);
        assertThat(opf).contains("<dc:title>书名 &amp; 测试</dc:title>");
        assertThat(opf).contains("<dc:creator>作者 &lt;名&gt;</dc:creator>");
        assertThat(opf).contains("<itemref idref=\"chap1\"/>");
        assertThat(opf).contains("<itemref idref=\"chap2\"/>");

        // toc.ncx navMap 与章数一致
        String ncx = new String(entries.get("OEBPS/toc.ncx").data, StandardCharsets.UTF_8);
        long navPoints = ncx.split("<navPoint ", -1).length - 1;
        assertThat(navPoints).isEqualTo(2);
        assertThat(ncx).contains("<text>第一章</text>");
        assertThat(ncx).contains("<text>第二章</text>");

        // 每个章节的 XHTML 都有
        assertThat(entries).containsKey("OEBPS/chapter-1.xhtml");
        assertThat(entries).containsKey("OEBPS/chapter-2.xhtml");
        String xhtml = new String(entries.get("OEBPS/chapter-1.xhtml").data, StandardCharsets.UTF_8);
        assertThat(xhtml).contains("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        assertThat(xhtml).contains("xmlns=\"http://www.w3.org/1999/xhtml\"");
        assertThat(xhtml).contains("<h1>第一章</h1>");
    }

    @Test
    @DisplayName("零章节抛 IllegalArgumentException")
    void rejectsEmptyChapters() {
        assertThrows(IllegalArgumentException.class, () ->
                NovelEpubWriter.write("t", "a", "ja", List.of()));
    }

    @Test
    @DisplayName("contentHash 对相同内容稳定，对不同内容不同")
    void contentHashStable() {
        var a = List.of(new NovelEpubWriter.Chapter("c", "body"));
        var b = List.of(new NovelEpubWriter.Chapter("c", "body"));
        var c = List.of(new NovelEpubWriter.Chapter("c", "different"));
        assertThat(NovelEpubWriter.contentHash(a)).isEqualTo(NovelEpubWriter.contentHash(b));
        assertThat(NovelEpubWriter.contentHash(a)).isNotEqualTo(NovelEpubWriter.contentHash(c));
    }

    private static Map<String, EntryInfo> readEntries(byte[] epub) throws Exception {
        Map<String, EntryInfo> map = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(epub))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                EntryInfo info = new EntryInfo();
                info.method = e.getMethod();
                info.data = zis.readAllBytes();
                map.put(e.getName(), info);
            }
        }
        return map;
    }

    private static class EntryInfo {
        int method;
        byte[] data;
    }
}

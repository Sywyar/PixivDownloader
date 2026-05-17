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
    @DisplayName("内嵌图片写入 OEBPS/images 并注册到 manifest")
    void embedsImages() throws Exception {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};
        byte[] epub = NovelEpubWriter.write("书", "作者", "ja",
                List.of(new NovelEpubWriter.Chapter("c",
                        "<section><figure class=\"novel-image\">"
                                + "<img src=\"images/embed_42.png\" alt=\"x\" /></figure></section>")),
                List.of(new NovelEpubWriter.ImageResource("42", "PNG", png)),
                null);

        Map<String, EntryInfo> entries = readEntries(epub);

        assertThat(entries).containsKey("OEBPS/images/embed_42.png");
        assertThat(entries.get("OEBPS/images/embed_42.png").data).isEqualTo(png);

        String opf = new String(entries.get("OEBPS/content.opf").data, StandardCharsets.UTF_8);
        assertThat(opf).contains(
                "<item id=\"img1\" href=\"images/embed_42.png\" media-type=\"image/png\"/>");

        String xhtml = new String(entries.get("OEBPS/chapter-1.xhtml").data, StandardCharsets.UTF_8);
        assertThat(xhtml).contains("src=\"images/embed_42.png\"");
    }

    @Test
    @DisplayName("封面内嵌为 cover-image + 首个 spine 页 + EPUB2 meta")
    void embedsCover() throws Exception {
        byte[] jpg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2};
        byte[] epub = NovelEpubWriter.write("书", "作者", "ja",
                List.of(new NovelEpubWriter.Chapter("c", "<section><p>正文</p></section>")),
                List.of(),
                new NovelEpubWriter.Cover("JPG", jpg),
                null);

        Map<String, EntryInfo> entries = readEntries(epub);

        assertThat(entries).containsKey("OEBPS/images/cover.jpg");
        assertThat(entries.get("OEBPS/images/cover.jpg").data).isEqualTo(jpg);
        assertThat(entries).containsKey("OEBPS/cover.xhtml");
        assertThat(new String(entries.get("OEBPS/cover.xhtml").data, StandardCharsets.UTF_8))
                .contains("src=\"images/cover.jpg\"");

        String opf = new String(entries.get("OEBPS/content.opf").data, StandardCharsets.UTF_8);
        assertThat(opf).contains(
                "<item id=\"cover-image\" href=\"images/cover.jpg\""
                        + " media-type=\"image/jpeg\" properties=\"cover-image\"/>");
        assertThat(opf).contains("<meta name=\"cover\" content=\"cover-image\"/>");
        // 封面页排在章节之前
        assertThat(opf.indexOf("<itemref idref=\"cover\""))
                .isLessThan(opf.indexOf("<itemref idref=\"chap1\""));
    }

    @Test
    @DisplayName("EPUB3 nav 文档 / 稳定标识符 / 嵌套目录 / OPF 元数据")
    void writesNavIdentifierMetadataAndNestedToc() throws Exception {
        var chapters = List.of(
                new NovelEpubWriter.Chapter("小说A", "<section><p>a</p></section>"),
                new NovelEpubWriter.Chapter("第一章", "<section><p>b</p></section>"),
                new NovelEpubWriter.Chapter("第二章", "<section><p>c</p></section>"));
        var nav = List.of(
                new NovelEpubWriter.NavEntry("小说A", 0),
                new NovelEpubWriter.NavEntry("小说B", 1, List.of(
                        new NovelEpubWriter.NavEntry("第一章", 1),
                        new NovelEpubWriter.NavEntry("第二章", 2))));
        var meta = new NovelEpubWriter.Metadata(
                "简介<a href=\"x\">链接</a>", "2024-01-02T03:04:05Z",
                List.of("百合", "GL"), "https://www.pixiv.net/novel/series/99",
                "我的系列", "3");

        byte[] epub = NovelEpubWriter.write("书", "作者", "ja",
                "urn:pixiv:novel-series:99", chapters, nav,
                List.of(), null, meta, null);

        Map<String, EntryInfo> entries = readEntries(epub);

        // EPUB3 强制 nav 文档 + manifest properties="nav"
        assertThat(entries).containsKey("OEBPS/nav.xhtml");
        String navDoc = new String(entries.get("OEBPS/nav.xhtml").data, StandardCharsets.UTF_8);
        assertThat(navDoc).contains("epub:type=\"toc\"").contains("<a href=\"chapter-2.xhtml\">第一章</a>");

        String opf = new String(entries.get("OEBPS/content.opf").data, StandardCharsets.UTF_8);
        assertThat(opf).contains(
                "<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>");
        assertThat(opf).contains("<dc:identifier id=\"BookId\">urn:pixiv:novel-series:99</dc:identifier>");
        assertThat(opf).contains("<dc:description>简介链接</dc:description>");
        assertThat(opf).contains("<dc:date>2024-01-02T03:04:05Z</dc:date>");
        assertThat(opf).contains("<dc:source>https://www.pixiv.net/novel/series/99</dc:source>");
        assertThat(opf).contains("<dc:subject>百合</dc:subject>");
        assertThat(opf).contains("<meta property=\"belongs-to-collection\" id=\"series\">我的系列</meta>");
        assertThat(opf).contains("<meta refines=\"#series\" property=\"group-position\">3</meta>");

        // NCX 嵌套：depth=2，子章节 navPoint 在父 navPoint 内
        String ncx = new String(entries.get("OEBPS/toc.ncx").data, StandardCharsets.UTF_8);
        assertThat(ncx).contains("<meta name=\"dtb:depth\" content=\"2\"/>");
        assertThat(ncx.split("<navPoint ", -1).length - 1).isEqualTo(4);
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

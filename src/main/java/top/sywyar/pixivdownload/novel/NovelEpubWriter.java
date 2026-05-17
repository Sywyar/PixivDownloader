package top.sywyar.pixivdownload.novel;

import top.sywyar.pixivdownload.i18n.MessageBundles;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Hand-built EPUB3 writer. Produces a minimal but compliant EPUB3 archive containing one or
 * more chapter XHTML pages. No external dependencies; relies only on {@link ZipOutputStream}.
 *
 * <p>Archive layout:
 * <pre>
 * mimetype                         (STORED, no compression, must be first entry)
 * META-INF/container.xml
 * OEBPS/content.opf
 * OEBPS/toc.ncx
 * OEBPS/styles.css
 * OEBPS/images/embed_{id}.{ext}    (zero or more embedded images)
 * OEBPS/chapter-{n}.xhtml
 * </pre>
 */
public final class NovelEpubWriter {

    private static final String MIMETYPE = "application/epub+zip";
    private static final String CSS = """
            body { font-family: serif; line-height: 1.7; padding: 0.5em 1em; }
            h1, h2 { font-weight: bold; }
            h2.novel-chapter { margin: 1em 0 0.5em; }
            section.novel-page { margin-bottom: 2em; }
            ruby rt { font-size: 0.6em; }
            figure.novel-image { text-align: center; margin: 1em 0; }
            figure.novel-image img { max-width: 100%; height: auto; }
            .novel-image-placeholder { color: #888; }
            .novel-jump { color: #888; font-size: 0.85em; }
            """;

    private NovelEpubWriter() {}

    /** 一个 spine 文档（对应一份 {@code chapter-n.xhtml}）。 */
    public record Chapter(String title, String xhtmlBody) {}

    /**
     * 目录树节点。{@code chapterIndex} 指向 {@code chapters} 列表中的某个 spine 文件；
     * {@code children} 表示子级目录（如系列合订本里「小说 → 章节」两级）。
     */
    public record NavEntry(String title, int chapterIndex, List<NavEntry> children) {
        public NavEntry(String title, int chapterIndex) {
            this(title, chapterIndex, List.of());
        }
    }

    /** OPF 可选元数据。所有字段允许为空；空字段不写出对应元素。 */
    public record Metadata(String description, String isoDate, List<String> subjects,
                           String source, String collectionTitle, String collectionPosition) {
        public static final Metadata EMPTY = new Metadata(null, null, List.of(), null, null, null);
    }

    /**
     * 一张内嵌进 EPUB 的图片。{@code id} 与正文 {@code [uploadedimage:id]} 的 id 对应，
     * 在归档内落为 {@code OEBPS/images/embed_{id}.{ext}}，章节 XHTML 以相对路径
     * {@code images/embed_{id}.{ext}} 引用。
     */
    public record ImageResource(String id, String ext, byte[] data) {

        /** 章节 XHTML 中引用该图片的相对路径（相对 {@code OEBPS/chapter-n.xhtml}）。 */
        public String href() {
            return "images/embed_" + id + "." + normalizedExt();
        }

        String normalizedExt() {
            return normalizeExt(ext);
        }

        String mediaType() {
            return mediaTypeForExt(normalizedExt());
        }
    }

    /**
     * 封面图。落为 {@code OEBPS/images/cover.{ext}}，并生成首个 spine 页 {@code OEBPS/cover.xhtml}；
     * 同时写出 EPUB3 {@code properties="cover-image"} 与 EPUB2 {@code <meta name="cover">} 兼容标记。
     */
    public record Cover(String ext, byte[] data) {

        String normalizedExt() {
            return normalizeExt(ext);
        }

        String href() {
            return "images/cover." + normalizedExt();
        }

        String mediaType() {
            return mediaTypeForExt(normalizedExt());
        }

        boolean usable() {
            return data != null && data.length > 0;
        }
    }

    private static String normalizeExt(String ext) {
        return (ext == null || ext.isBlank()) ? "jpg" : ext.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static String mediaTypeForExt(String normalizedExt) {
        return switch (normalizedExt) {
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            default -> "image/jpeg";
        };
    }

    public interface Labels {
        String untitled();
        String unknownAuthor();
        String chapter(int index);

        static Labels fromMessages() {
            return new Labels() {
                @Override public String untitled() {
                    return MessageBundles.get("novel.epub.untitled");
                }

                @Override public String unknownAuthor() {
                    return MessageBundles.get("novel.epub.unknown-author");
                }

                @Override public String chapter(int index) {
                    return MessageBundles.get("novel.epub.chapter", index);
                }
            };
        }
    }

    public static byte[] write(String bookTitle, String author, String language,
                               List<Chapter> chapters) throws IOException {
        return write(bookTitle, author, language, chapters, List.of(), null, Labels.fromMessages());
    }

    public static byte[] write(String bookTitle, String author, String language,
                               List<Chapter> chapters, Labels labels) throws IOException {
        return write(bookTitle, author, language, chapters, List.of(), null, labels);
    }

    public static byte[] write(String bookTitle, String author, String language,
                               List<Chapter> chapters, List<ImageResource> images,
                               Labels labels) throws IOException {
        return write(bookTitle, author, language, chapters, images, null, labels);
    }

    public static byte[] write(String bookTitle, String author, String language,
                               List<Chapter> chapters, List<ImageResource> images,
                               Cover cover, Labels labels) throws IOException {
        return write(bookTitle, author, language, null, chapters, null,
                images, cover, Metadata.EMPTY, labels);
    }

    /**
     * 完整入口。
     *
     * @param identifier 稳定书籍标识（如 {@code urn:pixiv:novel:123}）；为空则回退到随机 UUID。
     * @param nav        目录树；为空则按 {@code chapters} 顺序生成扁平单层目录。
     * @param metadata   可选 OPF 元数据；为空视为 {@link Metadata#EMPTY}。
     */
    public static byte[] write(String bookTitle, String author, String language,
                               String identifier, List<Chapter> chapters, List<NavEntry> nav,
                               List<ImageResource> images, Cover cover,
                               Metadata metadata, Labels labels) throws IOException {
        if (chapters == null || chapters.isEmpty()) {
            throw new IllegalArgumentException("at least one chapter required");
        }
        if (images == null) images = List.of();
        if (cover != null && !cover.usable()) cover = null;
        if (labels == null) labels = Labels.fromMessages();
        if (metadata == null) metadata = Metadata.EMPTY;
        String safeTitle = (bookTitle == null || bookTitle.isBlank()) ? labels.untitled() : bookTitle.trim();
        String safeAuthor = (author == null || author.isBlank()) ? labels.unknownAuthor() : author.trim();
        String safeLang = (language == null || language.isBlank()) ? "ja" : language.trim();
        String bookId = (identifier == null || identifier.isBlank())
                ? "urn:uuid:" + UUID.randomUUID() : identifier.trim();
        List<NavEntry> navTree = (nav == null || nav.isEmpty()) ? flatNav(chapters, labels) : nav;

        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(raw)) {
            // mimetype must be the first entry, STORED with no compression
            byte[] mimetype = MIMETYPE.getBytes(StandardCharsets.US_ASCII);
            ZipEntry mt = new ZipEntry("mimetype");
            mt.setMethod(ZipEntry.STORED);
            mt.setSize(mimetype.length);
            mt.setCompressedSize(mimetype.length);
            CRC32 crc = new CRC32();
            crc.update(mimetype);
            mt.setCrc(crc.getValue());
            zip.putNextEntry(mt);
            zip.write(mimetype);
            zip.closeEntry();

            putDeflated(zip, "META-INF/container.xml", containerXml());
            putDeflated(zip, "OEBPS/styles.css", CSS);

            if (cover != null) {
                putBytes(zip, "OEBPS/" + cover.href(), cover.data());
                putDeflated(zip, "OEBPS/cover.xhtml", coverXhtml(cover, safeLang, safeTitle));
            }

            for (ImageResource img : images) {
                if (img == null || img.data() == null || img.data().length == 0) continue;
                putBytes(zip, "OEBPS/" + img.href(), img.data());
            }

            List<String> chapterFiles = new ArrayList<>();
            for (int i = 0; i < chapters.size(); i++) {
                Chapter ch = chapters.get(i);
                String filename = "chapter-" + (i + 1) + ".xhtml";
                putDeflated(zip, "OEBPS/" + filename,
                        chapterXhtml(chapterTitle(ch.title(), i, labels), ch.xhtmlBody(), safeLang));
                chapterFiles.add(filename);
            }
            putDeflated(zip, "OEBPS/nav.xhtml",
                    navXhtml(safeTitle, safeLang, navTree, chapterFiles, labels));
            putDeflated(zip, "OEBPS/content.opf",
                    contentOpf(safeTitle, safeAuthor, safeLang, bookId, chapterFiles, images, cover, metadata));
            putDeflated(zip, "OEBPS/toc.ncx",
                    tocNcx(safeTitle, bookId, navTree, chapterFiles, labels));
        }
        return raw.toByteArray();
    }

    private static void putDeflated(ZipOutputStream zip, String path, String content) throws IOException {
        ZipEntry entry = new ZipEntry(path);
        entry.setMethod(ZipEntry.DEFLATED);
        zip.putNextEntry(entry);
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static void putBytes(ZipOutputStream zip, String path, byte[] content) throws IOException {
        ZipEntry entry = new ZipEntry(path);
        entry.setMethod(ZipEntry.DEFLATED);
        zip.putNextEntry(entry);
        zip.write(content);
        zip.closeEntry();
    }

    private static String containerXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">\n"
                + "  <rootfiles>\n"
                + "    <rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/>\n"
                + "  </rootfiles>\n"
                + "</container>\n";
    }

    private static String chapterXhtml(String title, String body, String lang) {
        String safeTitle = title == null ? "" : title;
        String safeBody = body == null ? "" : body;
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<!DOCTYPE html>\n"
                + "<html xmlns=\"http://www.w3.org/1999/xhtml\""
                + " xmlns:epub=\"http://www.idpf.org/2007/ops\""
                + " xml:lang=\"" + escapeXml(lang) + "\">\n"
                + "<head>\n"
                + "  <meta charset=\"UTF-8\"/>\n"
                + "  <title>" + escapeXml(safeTitle) + "</title>\n"
                + "  <link rel=\"stylesheet\" type=\"text/css\" href=\"styles.css\"/>\n"
                + "</head>\n"
                + "<body>\n"
                + "<h1>" + escapeXml(safeTitle) + "</h1>\n"
                + safeBody
                + "</body>\n"
                + "</html>\n";
    }

    private static String coverXhtml(Cover cover, String lang, String title) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<!DOCTYPE html>\n"
                + "<html xmlns=\"http://www.w3.org/1999/xhtml\""
                + " xmlns:epub=\"http://www.idpf.org/2007/ops\""
                + " xml:lang=\"" + escapeXml(lang) + "\">\n"
                + "<head>\n"
                + "  <meta charset=\"UTF-8\"/>\n"
                + "  <title>" + escapeXml(title) + "</title>\n"
                + "  <style>html,body{margin:0;padding:0;}"
                + " .cover{text-align:center;}"
                + " .cover img{max-width:100%;max-height:100vh;height:auto;}</style>\n"
                + "</head>\n"
                + "<body epub:type=\"cover\">\n"
                + "<div class=\"cover\"><img src=\"" + escapeXml(cover.href())
                + "\" alt=\"" + escapeXml(title) + "\"/></div>\n"
                + "</body>\n"
                + "</html>\n";
    }

    /** 默认目录：每个 spine 文件一个扁平条目（无嵌套）。 */
    private static List<NavEntry> flatNav(List<Chapter> chapters, Labels labels) {
        List<NavEntry> nav = new ArrayList<>();
        for (int i = 0; i < chapters.size(); i++) {
            nav.add(new NavEntry(chapterTitle(chapters.get(i).title(), i, labels), i));
        }
        return nav;
    }

    private static int navDepth(List<NavEntry> nav, int current) {
        int max = current;
        for (NavEntry e : nav) {
            if (e.children() != null && !e.children().isEmpty()) {
                max = Math.max(max, navDepth(e.children(), current + 1));
            }
        }
        return max;
    }

    private static String navLabel(NavEntry e, List<String> chapterFiles, Labels labels) {
        if (e.title() != null && !e.title().isBlank()) return e.title();
        int idx = e.chapterIndex();
        return labels.chapter(idx >= 0 ? idx + 1 : 1);
    }

    private static String hrefFor(NavEntry e, List<String> chapterFiles) {
        int idx = e.chapterIndex();
        if (idx < 0 || idx >= chapterFiles.size()) idx = 0;
        return chapterFiles.get(idx);
    }

    /** EPUB3 强制的 XHTML 导航文档（{@code properties="nav"}）。 */
    private static String navXhtml(String title, String lang, List<NavEntry> nav,
                                   List<String> chapterFiles, Labels labels) {
        StringBuilder ol = new StringBuilder();
        appendNavOl(ol, nav, chapterFiles, labels, 1);
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<!DOCTYPE html>\n"
                + "<html xmlns=\"http://www.w3.org/1999/xhtml\""
                + " xmlns:epub=\"http://www.idpf.org/2007/ops\""
                + " xml:lang=\"" + escapeXml(lang) + "\">\n"
                + "<head>\n  <meta charset=\"UTF-8\"/>\n  <title>" + escapeXml(title) + "</title>\n"
                + "  <link rel=\"stylesheet\" type=\"text/css\" href=\"styles.css\"/>\n</head>\n"
                + "<body>\n"
                + "<nav epub:type=\"toc\" id=\"toc\">\n"
                + "<h1>" + escapeXml(title) + "</h1>\n"
                + ol
                + "</nav>\n"
                + "</body>\n</html>\n";
    }

    private static void appendNavOl(StringBuilder out, List<NavEntry> nav,
                                    List<String> chapterFiles, Labels labels, int indent) {
        String pad = "  ".repeat(indent);
        out.append(pad).append("<ol>\n");
        for (NavEntry e : nav) {
            out.append(pad).append("  <li><a href=\"").append(escapeXml(hrefFor(e, chapterFiles)))
                    .append("\">").append(escapeXml(navLabel(e, chapterFiles, labels))).append("</a>");
            if (e.children() != null && !e.children().isEmpty()) {
                out.append("\n");
                appendNavOl(out, e.children(), chapterFiles, labels, indent + 2);
                out.append(pad).append("  </li>\n");
            } else {
                out.append("</li>\n");
            }
        }
        out.append(pad).append("</ol>\n");
    }

    private static String contentOpf(String title, String author, String lang, String bookId,
                                     List<String> chapterFiles, List<ImageResource> images,
                                     Cover cover, Metadata metadata) {
        StringBuilder manifest = new StringBuilder();
        manifest.append("    <item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>\n");
        manifest.append("    <item id=\"nav\" href=\"nav.xhtml\""
                + " media-type=\"application/xhtml+xml\" properties=\"nav\"/>\n");
        manifest.append("    <item id=\"css\" href=\"styles.css\" media-type=\"text/css\"/>\n");
        if (cover != null) {
            manifest.append("    <item id=\"cover-image\" href=\"").append(escapeXml(cover.href()))
                    .append("\" media-type=\"").append(cover.mediaType())
                    .append("\" properties=\"cover-image\"/>\n");
            manifest.append("    <item id=\"cover\" href=\"cover.xhtml\""
                    + " media-type=\"application/xhtml+xml\"/>\n");
        }
        int imgIndex = 0;
        for (ImageResource img : images) {
            if (img == null || img.data() == null || img.data().length == 0) continue;
            manifest.append("    <item id=\"img").append(++imgIndex)
                    .append("\" href=\"").append(escapeXml(img.href()))
                    .append("\" media-type=\"").append(img.mediaType()).append("\"/>\n");
        }
        StringBuilder spine = new StringBuilder();
        if (cover != null) {
            spine.append("    <itemref idref=\"cover\" linear=\"yes\"/>\n");
        }
        for (int i = 0; i < chapterFiles.size(); i++) {
            String id = "chap" + (i + 1);
            manifest.append("    <item id=\"").append(id)
                    .append("\" href=\"").append(escapeXml(chapterFiles.get(i)))
                    .append("\" media-type=\"application/xhtml+xml\"/>\n");
            spine.append("    <itemref idref=\"").append(id).append("\"/>\n");
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\""
                + " unique-identifier=\"BookId\" xml:lang=\"" + escapeXml(lang) + "\">\n"
                + "  <metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n"
                + "    <dc:identifier id=\"BookId\">" + escapeXml(bookId) + "</dc:identifier>\n"
                + "    <dc:title>" + escapeXml(title) + "</dc:title>\n"
                + "    <dc:creator>" + escapeXml(author) + "</dc:creator>\n"
                + "    <dc:language>" + escapeXml(lang) + "</dc:language>\n"
                + metadataElements(metadata)
                + "    <meta property=\"dcterms:modified\">"
                + java.time.Instant.now().toString().replaceAll("\\.\\d+Z$", "Z")
                + "</meta>\n"
                + (cover != null ? "    <meta name=\"cover\" content=\"cover-image\"/>\n" : "")
                + "  </metadata>\n"
                + "  <manifest>\n"
                + manifest
                + "  </manifest>\n"
                + "  <spine toc=\"ncx\">\n"
                + spine
                + "  </spine>\n"
                + "</package>\n";
    }

    private static String metadataElements(Metadata m) {
        if (m == null) return "";
        StringBuilder sb = new StringBuilder();
        String desc = stripHtml(m.description());
        if (desc != null && !desc.isBlank()) {
            sb.append("    <dc:description>").append(escapeXml(desc.trim())).append("</dc:description>\n");
        }
        if (m.isoDate() != null && !m.isoDate().isBlank()) {
            sb.append("    <dc:date>").append(escapeXml(m.isoDate().trim())).append("</dc:date>\n");
        }
        if (m.source() != null && !m.source().isBlank()) {
            sb.append("    <dc:source>").append(escapeXml(m.source().trim())).append("</dc:source>\n");
        }
        if (m.subjects() != null) {
            for (String s : m.subjects()) {
                if (s != null && !s.isBlank()) {
                    sb.append("    <dc:subject>").append(escapeXml(s.trim())).append("</dc:subject>\n");
                }
            }
        }
        if (m.collectionTitle() != null && !m.collectionTitle().isBlank()) {
            sb.append("    <meta property=\"belongs-to-collection\" id=\"series\">")
                    .append(escapeXml(m.collectionTitle().trim())).append("</meta>\n");
            sb.append("    <meta refines=\"#series\" property=\"collection-type\">series</meta>\n");
            if (m.collectionPosition() != null && !m.collectionPosition().isBlank()) {
                sb.append("    <meta refines=\"#series\" property=\"group-position\">")
                        .append(escapeXml(m.collectionPosition().trim())).append("</meta>\n");
            }
        }
        return sb.toString();
    }

    /** 粗略去除 HTML 标签，供 {@code dc:description} 使用（描述源串可能含 Pixiv 链接 HTML）。 */
    private static String stripHtml(String s) {
        if (s == null) return null;
        return s.replaceAll("<[^>]+>", "")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
    }

    private static String tocNcx(String title, String bookId, List<NavEntry> nav,
                                 List<String> chapterFiles, Labels labels) {
        StringBuilder navMap = new StringBuilder();
        appendNcxPoints(navMap, nav, chapterFiles, labels, new int[]{0}, 2);
        int depth = navDepth(nav, 1);
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<ncx xmlns=\"http://www.daisy.org/z3986/2005/ncx/\" version=\"2005-1\">\n"
                + "  <head>\n"
                + "    <meta name=\"dtb:uid\" content=\"" + escapeXml(bookId) + "\"/>\n"
                + "    <meta name=\"dtb:depth\" content=\"" + depth + "\"/>\n"
                + "    <meta name=\"dtb:totalPageCount\" content=\"0\"/>\n"
                + "    <meta name=\"dtb:maxPageNumber\" content=\"0\"/>\n"
                + "  </head>\n"
                + "  <docTitle><text>" + escapeXml(title) + "</text></docTitle>\n"
                + "  <navMap>\n"
                + navMap
                + "  </navMap>\n"
                + "</ncx>\n";
    }

    private static void appendNcxPoints(StringBuilder out, List<NavEntry> nav,
                                        List<String> chapterFiles, Labels labels,
                                        int[] order, int indent) {
        String pad = "  ".repeat(indent);
        for (NavEntry e : nav) {
            int playOrder = ++order[0];
            out.append(pad).append("<navPoint id=\"nav").append(playOrder)
                    .append("\" playOrder=\"").append(playOrder).append("\">\n")
                    .append(pad).append("  <navLabel><text>")
                    .append(escapeXml(navLabel(e, chapterFiles, labels))).append("</text></navLabel>\n")
                    .append(pad).append("  <content src=\"")
                    .append(escapeXml(hrefFor(e, chapterFiles))).append("\"/>\n");
            if (e.children() != null && !e.children().isEmpty()) {
                appendNcxPoints(out, e.children(), chapterFiles, labels, order, indent + 1);
            }
            out.append(pad).append("</navPoint>\n");
        }
    }

    private static String chapterTitle(String title, int index, Labels labels) {
        return title == null || title.isBlank() ? labels.chapter(index + 1) : title;
    }

    private static String escapeXml(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&apos;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /** Stable hash of the chapter content list — handy for cache invalidation. */
    static String contentHash(List<Chapter> chapters) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (Chapter c : chapters) {
                md.update((c.title() == null ? "" : c.title()).getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
                md.update((c.xhtmlBody() == null ? "" : c.xhtmlBody()).getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

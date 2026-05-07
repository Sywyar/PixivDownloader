package top.sywyar.pixivdownload.novel;

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
            .novel-image-placeholder { color: #888; }
            .novel-jump { color: #888; font-size: 0.85em; }
            """;

    private NovelEpubWriter() {}

    public record Chapter(String title, String xhtmlBody) {}

    public static byte[] write(String bookTitle, String author, String language,
                               List<Chapter> chapters) throws IOException {
        if (chapters == null || chapters.isEmpty()) {
            throw new IllegalArgumentException("at least one chapter required");
        }
        String safeTitle = (bookTitle == null || bookTitle.isBlank()) ? "Untitled" : bookTitle.trim();
        String safeAuthor = (author == null || author.isBlank()) ? "Unknown" : author.trim();
        String safeLang = (language == null || language.isBlank()) ? "ja" : language.trim();
        String bookId = "urn:uuid:" + UUID.randomUUID();

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

            List<String> chapterFiles = new ArrayList<>();
            for (int i = 0; i < chapters.size(); i++) {
                Chapter ch = chapters.get(i);
                String filename = "chapter-" + (i + 1) + ".xhtml";
                putDeflated(zip, "OEBPS/" + filename, chapterXhtml(ch.title(), ch.xhtmlBody(), safeLang));
                chapterFiles.add(filename);
            }
            putDeflated(zip, "OEBPS/content.opf",
                    contentOpf(safeTitle, safeAuthor, safeLang, bookId, chapters, chapterFiles));
            putDeflated(zip, "OEBPS/toc.ncx", tocNcx(safeTitle, bookId, chapters, chapterFiles));
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

    private static String contentOpf(String title, String author, String lang, String bookId,
                                     List<Chapter> chapters, List<String> chapterFiles) {
        StringBuilder manifest = new StringBuilder();
        manifest.append("    <item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>\n");
        manifest.append("    <item id=\"css\" href=\"styles.css\" media-type=\"text/css\"/>\n");
        StringBuilder spine = new StringBuilder();
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
                + "    <meta property=\"dcterms:modified\">"
                + java.time.Instant.now().toString().replaceAll("\\.\\d+Z$", "Z")
                + "</meta>\n"
                + "  </metadata>\n"
                + "  <manifest>\n"
                + manifest
                + "  </manifest>\n"
                + "  <spine toc=\"ncx\">\n"
                + spine
                + "  </spine>\n"
                + "</package>\n";
    }

    private static String tocNcx(String title, String bookId, List<Chapter> chapters,
                                 List<String> chapterFiles) {
        StringBuilder navMap = new StringBuilder();
        for (int i = 0; i < chapters.size(); i++) {
            String chTitle = chapters.get(i).title();
            if (chTitle == null || chTitle.isBlank()) chTitle = "Chapter " + (i + 1);
            navMap.append("    <navPoint id=\"nav").append(i + 1)
                    .append("\" playOrder=\"").append(i + 1).append("\">\n")
                    .append("      <navLabel><text>").append(escapeXml(chTitle)).append("</text></navLabel>\n")
                    .append("      <content src=\"").append(escapeXml(chapterFiles.get(i))).append("\"/>\n")
                    .append("    </navPoint>\n");
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<ncx xmlns=\"http://www.daisy.org/z3986/2005/ncx/\" version=\"2005-1\">\n"
                + "  <head>\n"
                + "    <meta name=\"dtb:uid\" content=\"" + escapeXml(bookId) + "\"/>\n"
                + "    <meta name=\"dtb:depth\" content=\"1\"/>\n"
                + "    <meta name=\"dtb:totalPageCount\" content=\"0\"/>\n"
                + "    <meta name=\"dtb:maxPageNumber\" content=\"0\"/>\n"
                + "  </head>\n"
                + "  <docTitle><text>" + escapeXml(title) + "</text></docTitle>\n"
                + "  <navMap>\n"
                + navMap
                + "  </navMap>\n"
                + "</ncx>\n";
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

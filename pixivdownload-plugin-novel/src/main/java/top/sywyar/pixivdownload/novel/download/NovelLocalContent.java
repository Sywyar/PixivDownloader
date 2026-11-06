package top.sywyar.pixivdownload.novel.download;

import javax.xml.stream.*;
import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipInputStream;

/** 从只读源文件取得可供阅读、翻译和再导出的正文，不访问外部资源。 */
final class NovelLocalContent {
    static final int MAX_TEXT_BYTES = 16 * 1024 * 1024;
    static final int MAX_XML_BYTES = 32 * 1024 * 1024;
    static final long MAX_EXPANDED_BYTES = 1024L * 1024 * 1024;
    static final int MAX_ENTRIES = 4096;
    private static final int MAX_CONTENT_CHARS = 16 * 1024 * 1024;
    private static final Set<String> HIDDEN_ELEMENTS = Set.of("script", "style", "nav");
    private static final Set<String> BLOCK_ELEMENTS = Set.of("p", "div", "h1", "h2", "h3", "li", "blockquote", "body");
    private NovelLocalContent() {}

    static String read(Path file, String extension) {
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            String content = switch (extension) {
                case "txt" -> decode(readBounded(input, MAX_TEXT_BYTES));
                case "epub" -> epub(input);
                default -> throw new IOException("NOVEL_CONTENT_FORMAT");
            };
            if (content.startsWith("\uFEFF")) content = content.substring(1);
            if (content.isBlank() || content.indexOf('\0') >= 0) throw new IOException("NOVEL_CONTENT_EMPTY");
            return content;
        } catch (IOException failure) {
            throw new UncheckedIOException("NOVEL_CONTENT_READ_FAILED", failure);
        }
    }

    private static String epub(InputStream input) throws IOException {
        Map<String, byte[]> documents = new HashMap<>();
        Set<String> names = new HashSet<>();
        long expanded = 0;
        int xmlBytes = 0;
        byte[] buffer = new byte[8192];
        try (var zip = new ZipInputStream(input, StandardCharsets.UTF_8)) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                checkInterrupted();
                String name = entry.getName();
                if (names.size() >= MAX_ENTRIES || name.length() > 4096 || !names.add(name)
                        || name.startsWith("/") || name.contains("\\") || name.contains("../"))
                    throw new IOException("NOVEL_ARCHIVE_ENTRIES");
                String lower = name.toLowerCase(Locale.ROOT);
                boolean document = name.equals("mimetype") || lower.endsWith(".xml") || lower.endsWith(".opf")
                        || lower.endsWith(".xhtml") || lower.endsWith(".html");
                var bytes = document ? new ByteArrayOutputStream() : null;
                int n;
                while ((n = zip.read(buffer)) != -1) {
                    checkInterrupted();
                    expanded += n;
                    if (expanded > MAX_EXPANDED_BYTES) throw new IOException("NOVEL_ARCHIVE_LIMIT");
                    if (bytes != null) {
                        xmlBytes += n;
                        if (xmlBytes > MAX_XML_BYTES || bytes.size() + n > MAX_TEXT_BYTES)
                            throw new IOException("NOVEL_CONTENT_LIMIT");
                        bytes.write(buffer, 0, n);
                    }
                }
                if (bytes != null) documents.put(name, bytes.toByteArray());
            }
        }
        if (!"application/epub+zip".equals(decode(required(documents, "mimetype"))))
            throw new IOException("NOVEL_CONTENT_FORMAT");
        List<String> packages = new ArrayList<>();
        xml(required(documents, "META-INF/container.xml"), reader -> {
            if (reader.isStartElement() && reader.getLocalName().equals("rootfile")
                    && "application/oebps-package+xml".equals(attribute(reader, "media-type")))
                packages.add(attribute(reader, "full-path"));
        });
        if (packages.size() != 1) throw new IOException("NOVEL_CONTENT_PACKAGE");
        String packagePath = packages.getFirst();
        Map<String, String> manifest = new HashMap<>();
        List<String> spine = new ArrayList<>();
        xml(required(documents, packagePath), reader -> {
            if (!reader.isStartElement()) return;
            if (reader.getLocalName().equals("item")
                    && "application/xhtml+xml".equals(attribute(reader, "media-type"))) {
                if (manifest.put(attribute(reader, "id"), resolve(packagePath, attribute(reader, "href"))) != null)
                    throw new IOException("NOVEL_CONTENT_MANIFEST");
            }
            if (reader.getLocalName().equals("itemref") && !"no".equals(attribute(reader, "linear")))
                spine.add(attribute(reader, "idref"));
        });
        if (spine.isEmpty()) throw new IOException("NOVEL_CONTENT_SPINE");
        StringBuilder content = new StringBuilder();
        long parsedBytes = 0;
        for (String id : spine) {
            String path = manifest.get(id);
            if (path == null) throw new IOException("NOVEL_CONTENT_SPINE");
            byte[] chapter = required(documents, path);
            parsedBytes += chapter.length;
            if (parsedBytes > MAX_XML_BYTES) throw new IOException("NOVEL_CONTENT_LIMIT");
            int[] bodyDepth = {0};
            int[] hiddenDepth = {0};
            boolean[] hasBody = {false};
            xml(chapter, reader -> {
                if (reader.isStartElement()) {
                    String tag = reader.getLocalName();
                    if (bodyDepth[0] == 0) {
                        if (tag.equals("body")) { bodyDepth[0] = 1; hasBody[0] = true; }
                        return;
                    }
                    bodyDepth[0]++;
                    if (hiddenDepth[0] > 0 || HIDDEN_ELEMENTS.contains(tag)) hiddenDepth[0]++;
                    else if (tag.equals("br")) append(content, "\n");
                } else if (reader.isEndElement() && bodyDepth[0] > 0) {
                    if (hiddenDepth[0] > 0) hiddenDepth[0]--;
                    else if (BLOCK_ELEMENTS.contains(reader.getLocalName()))
                        append(content, "\n");
                    bodyDepth[0]--;
                } else if (reader.isCharacters() && bodyDepth[0] > 0 && hiddenDepth[0] == 0) {
                    append(content, reader.getText());
                }
            });
            if (!hasBody[0]) throw new IOException("NOVEL_CONTENT_MISSING");
            append(content, "\n");
        }
        return content.toString();
    }

    private static void append(StringBuilder text, String value) throws IOException {
        if (value.length() > MAX_CONTENT_CHARS - text.length()) throw new IOException("NOVEL_CONTENT_LIMIT");
        text.append(value);
    }

    private static String resolve(String base, String href) throws IOException {
        try {
            URI path = new URI(null, null, base, null).resolve(href).normalize();
            if (path.isAbsolute() || path.getAuthority() != null || path.getQuery() != null
                    || path.getPath().startsWith("/") || path.getPath().startsWith("../")
                    || path.getPath().contains("\\"))
                throw new IOException("NOVEL_CONTENT_PATH");
            return path.getPath();
        } catch (IllegalArgumentException | URISyntaxException invalid) { throw new IOException("NOVEL_CONTENT_PATH"); }
    }

    private static byte[] required(Map<String, byte[]> documents, String path) throws IOException {
        byte[] result = documents.get(path);
        if (result == null) throw new IOException("NOVEL_CONTENT_MISSING");
        return result;
    }

    private static String attribute(XMLStreamReader reader, String name) {
        return Objects.toString(reader.getAttributeValue(null, name), "");
    }

    @FunctionalInterface private interface XmlElement { void accept(XMLStreamReader reader) throws IOException; }

    private static void xml(byte[] bytes, XmlElement action) throws IOException {
        var factory = XMLInputFactory.newDefaultFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty("jdk.xml.maxElementDepth", 100);
        factory.setXMLResolver((publicId, systemId, base, namespace) -> {
            throw new XMLStreamException("NOVEL_CONTENT_EXTERNAL_ENTITY");
        });
        try {
            var reader = factory.createXMLStreamReader(new StringReader(decode(bytes)));
            try {
                while (reader.hasNext()) {
                    checkInterrupted();
                    int event = reader.next();
                    if (event == XMLStreamConstants.DTD && reader.getText().contains("[")
                            || event == XMLStreamConstants.ENTITY_REFERENCE)
                        throw new IOException("NOVEL_CONTENT_ENTITY");
                    action.accept(reader);
                }
            } finally { reader.close(); }
        } catch (XMLStreamException invalid) { throw new IOException("NOVEL_CONTENT_XML"); }
    }

    private static byte[] readBounded(InputStream input, int maximum) throws IOException {
        var bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = input.read(buffer)) != -1) {
            checkInterrupted();
            if (n > maximum - bytes.size()) throw new IOException("NOVEL_CONTENT_LIMIT");
            bytes.write(buffer, 0, n);
        }
        return bytes.toByteArray();
    }

    private static String decode(byte[] bytes) throws IOException {
        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("NOVEL_CONTENT_CANCELLED");
    }
}

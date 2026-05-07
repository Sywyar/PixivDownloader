package top.sywyar.pixivdownload.novel;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses Pixiv novel markup into TXT, HTML or EPUB-XHTML output.
 *
 * <p>Recognised tokens:
 * <ul>
 *   <li>{@code [newpage]} — section break (own line)</li>
 *   <li>{@code [chapter:Title]} — chapter heading (own line)</li>
 *   <li>{@code [[rb:base > ruby]]} — ruby annotation</li>
 *   <li>{@code [[jumpuri:text > url]]} — external link</li>
 *   <li>{@code [jump:N]} — in-novel page jump (rendered as note)</li>
 *   <li>{@code [uploadedimage:id]} — embedded Pixiv-uploaded image</li>
 *   <li>{@code [pixivimage:illustId]} or {@code [pixivimage:illustId-page]} — embedded illust</li>
 * </ul>
 */
public final class NovelMarkupParser {

    public enum Format { TXT, HTML, XHTML }

    /**
     * 把内嵌图片占位符（{@code [uploadedimage:id]} / {@code [pixivimage:id]}）解析为可展示的 URL。
     * 返回 {@code null} 表示没有可用图片，渲染器回退到占位文本。
     */
    public interface ImageResolver {
        /** 上传图占位 → 图片 URL（可为相对或绝对）。 */
        String uploadedImage(String id);
        /** Pixiv 插图占位 → 图片 URL（可为相对或绝对）。 */
        String pixivImage(String id);

        ImageResolver NONE = new ImageResolver() {
            @Override public String uploadedImage(String id) { return null; }
            @Override public String pixivImage(String id) { return null; }
        };
    }

    private static final Pattern INLINE_PATTERN = Pattern.compile(
            "\\[\\[rb:(?<rbBase>[^>\\]]+?)\\s*>\\s*(?<rbRuby>[^\\]]+?)\\]\\]"
                    + "|\\[\\[jumpuri:(?<juText>[^>\\]]+?)\\s*>\\s*(?<juUrl>[^\\]]+?)\\]\\]"
                    + "|\\[jump:(?<jumpPage>\\d+)\\]"
                    + "|\\[uploadedimage:(?<upImg>\\d+)\\]"
                    + "|\\[pixivimage:(?<pxImg>\\d+(?:-\\d+)?)\\]"
    );

    private static final Pattern CHAPTER_LINE = Pattern.compile("^\\s*\\[chapter:(.+?)\\]\\s*$");
    private static final Pattern NEWPAGE_LINE = Pattern.compile("^\\s*\\[newpage\\]\\s*$");

    private NovelMarkupParser() {}

    /** 扫描原始正文中出现的 [uploadedimage:id] 占位符 ID 列表（按出现顺序去重）。 */
    public static Set<String> findUploadedImageIds(String raw) {
        if (raw == null || raw.isEmpty()) return Set.of();
        Set<String> ids = new LinkedHashSet<>();
        Matcher m = Pattern.compile("\\[uploadedimage:(\\d+)\\]").matcher(raw);
        while (m.find()) ids.add(m.group(1));
        return ids;
    }

    /** Pixiv 标记 → 三种格式的正文（不解析内嵌图片，使用占位符渲染）。 */
    public static String render(String raw, Format format) {
        return render(raw, format, ImageResolver.NONE);
    }

    /**
     * Pixiv 标记 → 三种格式的正文。HTML/XHTML 输出在 {@code resolver} 返回非空 URL 时
     * 把图片占位符替换为 {@code <img>} 标签；TXT 输出永远使用占位符（保证下载文件中可读）。
     */
    public static String render(String raw, Format format, ImageResolver resolver) {
        if (raw == null) raw = "";
        if (resolver == null) resolver = ImageResolver.NONE;
        List<Block> blocks = tokenize(raw);
        return switch (format) {
            case TXT -> renderTxt(blocks);
            case HTML -> renderHtml(blocks, false, resolver);
            case XHTML -> renderHtml(blocks, true, resolver);
        };
    }

    /** Tokenize the input by lines into block-level structure. */
    private static List<Block> tokenize(String raw) {
        List<Block> blocks = new ArrayList<>();
        String normalized = raw.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        StringBuilder buf = new StringBuilder();
        for (String line : lines) {
            Matcher chapter = CHAPTER_LINE.matcher(line);
            if (chapter.matches()) {
                flushParagraph(buf, blocks);
                blocks.add(new Block(BlockKind.CHAPTER, chapter.group(1).trim()));
                continue;
            }
            if (NEWPAGE_LINE.matcher(line).matches()) {
                flushParagraph(buf, blocks);
                blocks.add(new Block(BlockKind.NEWPAGE, ""));
                continue;
            }
            if (buf.length() > 0) buf.append('\n');
            buf.append(line);
        }
        flushParagraph(buf, blocks);
        return blocks;
    }

    private static void flushParagraph(StringBuilder buf, List<Block> blocks) {
        if (buf.length() == 0) return;
        blocks.add(new Block(BlockKind.PARAGRAPH, buf.toString()));
        buf.setLength(0);
    }

    // ── TXT renderer ────────────────────────────────────────────────────────────

    private static String renderTxt(List<Block> blocks) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < blocks.size(); i++) {
            Block b = blocks.get(i);
            switch (b.kind) {
                case CHAPTER -> {
                    if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') out.append('\n');
                    if (out.length() > 0) out.append('\n');
                    out.append("【").append(b.text).append("】\n\n");
                }
                case NEWPAGE -> {
                    if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') out.append('\n');
                    out.append("\n");
                }
                case PARAGRAPH -> {
                    out.append(renderInlineTxt(b.text));
                    if (i < blocks.size() - 1) out.append('\n');
                }
            }
        }
        return out.toString();
    }

    private static String renderInlineTxt(String text) {
        Matcher m = INLINE_PATTERN.matcher(text);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            out.append(text, last, m.start());
            if (m.group("rbBase") != null) {
                out.append(m.group("rbBase").trim());
            } else if (m.group("juText") != null) {
                out.append(m.group("juText").trim()).append(" (").append(m.group("juUrl").trim()).append(")");
            } else if (m.group("jumpPage") != null) {
                // skip page jumps in plain text
            } else if (m.group("upImg") != null) {
                out.append("[图片#").append(m.group("upImg")).append("]");
            } else if (m.group("pxImg") != null) {
                out.append("[Pixiv图#").append(m.group("pxImg")).append("]");
            }
            last = m.end();
        }
        out.append(text, last, text.length());
        return out.toString();
    }

    // ── HTML / XHTML renderer ──────────────────────────────────────────────────

    private static String renderHtml(List<Block> blocks, boolean xhtml, ImageResolver resolver) {
        StringBuilder out = new StringBuilder();
        boolean sectionOpen = false;
        out.append(openSection(xhtml));
        sectionOpen = true;
        for (Block b : blocks) {
            switch (b.kind) {
                case CHAPTER -> {
                    out.append("<h2 class=\"novel-chapter\">")
                            .append(escapeHtml(b.text))
                            .append("</h2>\n");
                }
                case NEWPAGE -> {
                    if (sectionOpen) {
                        out.append("</section>\n");
                        sectionOpen = false;
                    }
                    out.append(openSection(xhtml));
                    sectionOpen = true;
                }
                case PARAGRAPH -> {
                    String[] paragraphs = b.text.split("\n{2,}");
                    for (String p : paragraphs) {
                        if (p.isEmpty()) continue;
                        out.append("<p>").append(renderInlineHtml(p, xhtml, resolver)).append("</p>\n");
                    }
                }
            }
        }
        if (sectionOpen) out.append("</section>\n");
        return out.toString();
    }

    private static String openSection(boolean xhtml) {
        return xhtml
                ? "<section class=\"novel-page\" epub:type=\"chapter\">\n"
                : "<section class=\"novel-page\">\n";
    }

    private static String renderInlineHtml(String text, boolean xhtml, ImageResolver resolver) {
        Matcher m = INLINE_PATTERN.matcher(text);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            appendEscapedWithBreaks(out, text.substring(last, m.start()));
            if (m.group("rbBase") != null) {
                out.append("<ruby>")
                        .append(escapeHtml(m.group("rbBase").trim()))
                        .append("<rt>")
                        .append(escapeHtml(m.group("rbRuby").trim()))
                        .append("</rt></ruby>");
            } else if (m.group("juText") != null) {
                out.append("<a href=\"")
                        .append(escapeAttr(m.group("juUrl").trim()))
                        .append("\" rel=\"noopener noreferrer\" target=\"_blank\">")
                        .append(escapeHtml(m.group("juText").trim()))
                        .append("</a>");
            } else if (m.group("jumpPage") != null) {
                out.append("<span class=\"novel-jump\">↗ p.")
                        .append(escapeHtml(m.group("jumpPage")))
                        .append("</span>");
            } else if (m.group("upImg") != null) {
                String id = m.group("upImg");
                String url = resolver.uploadedImage(id);
                appendImageFigure(out, url, "data-uploaded-image", id, "[图片 #", xhtml);
            } else if (m.group("pxImg") != null) {
                String id = m.group("pxImg");
                String url = resolver.pixivImage(id);
                appendImageFigure(out, url, "data-pixiv-image", id, "[Pixiv图 #", xhtml);
            }
            last = m.end();
        }
        appendEscapedWithBreaks(out, text.substring(last));
        return out.toString();
    }

    private static void appendImageFigure(StringBuilder out, String url, String dataAttr,
                                          String id, String placeholderPrefix, boolean xhtml) {
        out.append("<figure class=\"novel-image\" ")
                .append(dataAttr).append("=\"")
                .append(escapeAttr(id))
                .append("\">");
        if (url != null && !url.isBlank()) {
            String selfClose = xhtml ? " />" : ">";
            out.append("<img src=\"").append(escapeAttr(url))
                    .append("\" alt=\"").append(escapeAttr(placeholderPrefix)).append(escapeAttr(id)).append("]\"")
                    .append(" loading=\"lazy\"")
                    .append(selfClose);
        } else {
            out.append("<span class=\"novel-image-placeholder\">")
                    .append(escapeHtml(placeholderPrefix))
                    .append(escapeHtml(id))
                    .append("]</span>");
        }
        out.append("</figure>");
    }

    private static void appendEscapedWithBreaks(StringBuilder out, String text) {
        if (text.isEmpty()) return;
        String[] parts = text.split("\n", -1);
        for (int i = 0; i < parts.length; i++) {
            out.append(escapeHtml(parts[i]));
            if (i < parts.length - 1) out.append("<br />");
        }
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static String escapeAttr(String s) {
        return escapeHtml(s);
    }

    private enum BlockKind { PARAGRAPH, CHAPTER, NEWPAGE }

    private record Block(BlockKind kind, String text) {}
}

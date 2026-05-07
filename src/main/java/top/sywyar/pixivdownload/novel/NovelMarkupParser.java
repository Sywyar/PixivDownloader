package top.sywyar.pixivdownload.novel;

import java.util.ArrayList;
import java.util.List;
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

    /** Pixiv 标记 → 三种格式的正文。 */
    public static String render(String raw, Format format) {
        if (raw == null) raw = "";
        List<Block> blocks = tokenize(raw);
        return switch (format) {
            case TXT -> renderTxt(blocks);
            case HTML -> renderHtml(blocks, false);
            case XHTML -> renderHtml(blocks, true);
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

    private static String renderHtml(List<Block> blocks, boolean xhtml) {
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
                        out.append("<p>").append(renderInlineHtml(p, xhtml)).append("</p>\n");
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

    private static String renderInlineHtml(String text, boolean xhtml) {
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
                String br = xhtml ? "<br />" : "<br>";
                out.append("<figure class=\"novel-image\" data-uploaded-image=\"")
                        .append(escapeAttr(m.group("upImg")))
                        .append("\"><span class=\"novel-image-placeholder\">[图片 #")
                        .append(escapeHtml(m.group("upImg")))
                        .append("]</span></figure>");
            } else if (m.group("pxImg") != null) {
                out.append("<figure class=\"novel-image\" data-pixiv-image=\"")
                        .append(escapeAttr(m.group("pxImg")))
                        .append("\"><span class=\"novel-image-placeholder\">[Pixiv图 #")
                        .append(escapeHtml(m.group("pxImg")))
                        .append("]</span></figure>");
            }
            last = m.end();
        }
        appendEscapedWithBreaks(out, text.substring(last));
        return out.toString();
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

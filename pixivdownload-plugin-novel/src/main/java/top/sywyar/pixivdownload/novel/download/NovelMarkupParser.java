package top.sywyar.pixivdownload.novel.download;

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

    public interface ImageLabels {
        String uploadedImage(String id);
        String pixivImage(String id);

        ImageLabels MARKUP = new ImageLabels() {
            @Override public String uploadedImage(String id) { return "[uploadedimage:" + id + "]"; }
            @Override public String pixivImage(String id) { return "[pixivimage:" + id + "]"; }
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

    /** 一个章节切片：{@code title} 来自 {@code [chapter:..]}（无章节标记时为 {@code null}），{@code raw} 为该段原始 markup（不含 chapter 行）。 */
    public record Segment(String title, String raw) {}

    /**
     * 按行首 {@code [chapter:标题]} 把原始正文切成多个 {@link Segment}（用于 EPUB 按章拆分 spine + 多级目录）。
     * 第一个 {@code [chapter:]} 之前的内容若非空白，作为一个 {@code title=null} 的前置切片；
     * 没有任何章节标记时返回单个 {@code Segment(null, raw)}。
     */
    public static List<Segment> splitChapters(String raw) {
        if (raw == null) raw = "";
        String normalized = raw.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        List<Segment> segments = new ArrayList<>();
        String currentTitle = null;
        boolean started = false;
        StringBuilder buf = new StringBuilder();
        for (String line : lines) {
            Matcher chapter = CHAPTER_LINE.matcher(line);
            if (chapter.matches()) {
                if (started || buf.toString().strip().length() > 0) {
                    segments.add(new Segment(currentTitle, buf.toString()));
                }
                buf.setLength(0);
                currentTitle = chapter.group(1).trim();
                started = true;
                continue;
            }
            if (buf.length() > 0) buf.append('\n');
            buf.append(line);
        }
        if (started || buf.toString().strip().length() > 0 || segments.isEmpty()) {
            segments.add(new Segment(currentTitle, buf.toString()));
        }
        return segments;
    }

    /** 一个<b>可朗读渲染块</b>的类型。{@code NEWPAGE} 不产生块（不出现在 {@link #textBlocks} 结果中）。 */
    public enum TextBlockKind { CHAPTER, PARAGRAPH }

    /**
     * 一个可朗读渲染块：{@code text} 已是<b>纯朗读文本</b>（ruby 注音取基词、外链取链接文字、翻页标记 /
     * 内嵌图片占位一律剔除），与前端渲染出的 DOM 文本内容一致。
     */
    public record TextBlock(TextBlockKind kind, String text) {}

    /**
     * 把原始 Pixiv markup 拆成<b>与前端渲染块逐一对齐</b>的可朗读纯文本块序列，供「AI 听小说」断句 + 段落级高亮使用。
     *
     * <p>本方法的块枚举顺序与数量必须与前端 {@code pixiv-novel-render.js} 渲染出的
     * {@code <h2 class="novel-chapter">} / {@code <p>} 元素（即 {@code querySelectorAll('h2.novel-chapter, p')}）
     * 严格一致，以保证 {@code paragraphIndex} 能在两端对齐：
     * <ul>
     *   <li>{@code [chapter:标题]} → 一个 {@link TextBlockKind#CHAPTER} 块；</li>
     *   <li>{@code [newpage]} → <b>不产生块</b>（前端只是切 section，无 h2/p）；</li>
     *   <li>其余连续行先按 {@code \n} 累积为一个段落缓冲，再按<b>空行（{@code \n{2,}}）</b>切成多个片段，每个
     *       <b>非空字符串</b>片段对应一个 {@link TextBlockKind#PARAGRAPH} 块（与前端「每个非空片段渲染一个
     *       {@code <p>}」一致）。</li>
     * </ul>
     * 块的 {@code text} 经 {@link #plainText} 还原为朗读文本；某些块（如仅含翻页 / 缺失图片的片段）{@code text}
     * 可能为空白，但<b>仍占一个块位</b>（与前端 DOM 中存在的空 {@code <p>} 对齐），以免下标错位。
     */
    public static List<TextBlock> textBlocks(String raw) {
        if (raw == null) raw = "";
        String normalized = raw.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        List<TextBlock> out = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        boolean hasBuf = false;
        for (String line : lines) {
            Matcher chapter = CHAPTER_LINE.matcher(line);
            if (chapter.matches()) {
                flushTextParagraph(buf, hasBuf, out);
                buf.setLength(0);
                hasBuf = false;
                out.add(new TextBlock(TextBlockKind.CHAPTER, plainText(chapter.group(1).trim())));
                continue;
            }
            if (NEWPAGE_LINE.matcher(line).matches()) {
                flushTextParagraph(buf, hasBuf, out);
                buf.setLength(0);
                hasBuf = false;
                // newpage 在前端只切 section、不产生 h2/p，这里同样不产生块。
                continue;
            }
            if (hasBuf) buf.append('\n');
            buf.append(line);
            hasBuf = true;
        }
        flushTextParagraph(buf, hasBuf, out);
        return out;
    }

    /** 把一个段落缓冲按空行（{@code \n{2,}}）切成多个 {@code <p>} 对应块；与前端 {@code if (!p) continue} 一致，仅跳过空串片段。 */
    private static void flushTextParagraph(StringBuilder buf, boolean hasBuf, List<TextBlock> out) {
        if (!hasBuf) return;
        String[] pieces = buf.toString().split("\\n{2,}", -1);
        for (String piece : pieces) {
            if (piece.isEmpty()) continue;
            out.add(new TextBlock(TextBlockKind.PARAGRAPH, plainText(piece)));
        }
    }

    /**
     * 把一段含内联标记的文本还原为<b>纯朗读文本</b>：ruby 取基词、外链取链接文字、翻页提示与内嵌图片占位一律剔除。
     * 与前端把这些内联元素渲染后取 {@code textContent} 的结果一致（外链只读出链接文字、不读 URL；图片不读出）。
     */
    private static String plainText(String text) {
        if (text == null || text.isEmpty()) return "";
        Matcher m = INLINE_PATTERN.matcher(text);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            out.append(text, last, m.start());
            if (m.group("rbBase") != null) {
                out.append(m.group("rbBase").trim());
            } else if (m.group("juText") != null) {
                out.append(m.group("juText").trim());
            }
            // jumpPage / upImg / pxImg：剔除，不读出。
            last = m.end();
        }
        out.append(text, last, text.length());
        return out.toString();
    }

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
        return render(raw, format, ImageResolver.NONE, ImageLabels.MARKUP);
    }

    public static String render(String raw, Format format, ImageLabels labels) {
        return render(raw, format, ImageResolver.NONE, labels);
    }

    /**
     * Pixiv 标记 → 三种格式的正文。HTML/XHTML 输出在 {@code resolver} 返回非空 URL 时
     * 把图片占位符替换为 {@code <img>} 标签；TXT 输出永远使用占位符（保证下载文件中可读）。
     */
    public static String render(String raw, Format format, ImageResolver resolver) {
        return render(raw, format, resolver, ImageLabels.MARKUP);
    }

    public static String render(String raw, Format format, ImageResolver resolver, ImageLabels labels) {
        if (raw == null) raw = "";
        if (resolver == null) resolver = ImageResolver.NONE;
        if (labels == null) labels = ImageLabels.MARKUP;
        List<Block> blocks = tokenize(raw);
        return switch (format) {
            case TXT -> renderTxt(blocks, labels);
            case HTML -> renderHtml(blocks, false, resolver, labels);
            case XHTML -> renderHtml(blocks, true, resolver, labels);
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

    private static String renderTxt(List<Block> blocks, ImageLabels labels) {
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
                    out.append(renderInlineTxt(b.text, labels));
                    if (i < blocks.size() - 1) out.append('\n');
                }
            }
        }
        return out.toString();
    }

    private static String renderInlineTxt(String text, ImageLabels labels) {
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
                out.append(labels.uploadedImage(m.group("upImg")));
            } else if (m.group("pxImg") != null) {
                out.append(labels.pixivImage(m.group("pxImg")));
            }
            last = m.end();
        }
        out.append(text, last, text.length());
        return out.toString();
    }

    // ── HTML / XHTML renderer ──────────────────────────────────────────────────

    private static String renderHtml(List<Block> blocks, boolean xhtml, ImageResolver resolver, ImageLabels labels) {
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
                        out.append("<p>").append(renderInlineHtml(p, xhtml, resolver, labels)).append("</p>\n");
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

    private static String renderInlineHtml(String text, boolean xhtml, ImageResolver resolver, ImageLabels labels) {
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
                appendImageFigure(out, url, "data-uploaded-image", id, labels.uploadedImage(id), xhtml);
            } else if (m.group("pxImg") != null) {
                String id = m.group("pxImg");
                String url = resolver.pixivImage(id);
                appendImageFigure(out, url, "data-pixiv-image", id, labels.pixivImage(id), xhtml);
            }
            last = m.end();
        }
        appendEscapedWithBreaks(out, text.substring(last));
        return out.toString();
    }

    private static void appendImageFigure(StringBuilder out, String url, String dataAttr,
                                          String id, String label, boolean xhtml) {
        out.append("<figure class=\"novel-image\" ")
                .append(dataAttr).append("=\"")
                .append(escapeAttr(id))
                .append("\">");
        if (url != null && !url.isBlank()) {
            String selfClose = xhtml ? " />" : ">";
            out.append("<img src=\"").append(escapeAttr(url))
                    .append("\" alt=\"").append(escapeAttr(label)).append("\"")
                    .append(" loading=\"lazy\"")
                    .append(selfClose);
        } else {
            out.append("<span class=\"novel-image-placeholder\">")
                    .append(escapeHtml(label))
                    .append("</span>");
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

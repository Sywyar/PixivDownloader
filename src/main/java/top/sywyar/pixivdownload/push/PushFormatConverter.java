package top.sywyar.pixivdownload.push;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 推送格式化系统的<b>框架层</b>：协商目标格式 + 在格式间转换。无状态，被 {@link PushService} 复用。
 * <p>
 * 职责对应用户诉求里的「发送框架检测发送驱动和发送类型是否支持」：给定某通道
 * {@link PushChannel#supportedFormats() 支持的格式}与消息 {@link PushMessage#sourceFormat() 源格式}，
 * {@link #negotiate} 选出该通道最合适的目标格式，{@link #render} 把正文转换到该格式并产出
 * {@link RenderedMessage}。任意源恒可达 {@link PushFormat#PLAIN_TEXT}，因此协商恒有解、并据此实现
 * 「不可转换时尽力降级为纯文本仍发送」的 best-effort 语义。
 *
 * <h2>转换矩阵（best-effort、最小可测）</h2>
 * <ul>
 *   <li>identity：X → X</li>
 *   <li>{@code MARKDOWN → PLAIN_TEXT}：剥离标记</li>
 *   <li>{@code PLAIN_TEXT → MARKDOWN}：原样透传（纯文本即合法 Markdown）</li>
 *   <li>{@code HTML → PLAIN_TEXT}：去标签 + 反转义实体</li>
 *   <li>{@code PLAIN_TEXT → HTML}：转义特殊字符，保留原始换行（Telegram HTML 不支持 {@code <br>}）</li>
 *   <li>{@code MARKDOWN → HTML}：受限子集（粗体 / 斜体 / 行内代码 / 链接，标题降级为去标记）</li>
 *   <li>{@code HTML → MARKDOWN}：<b>不支持</b> → 调用方降级为 {@link PushFormat#PLAIN_TEXT}</li>
 *   <li>{@code * → CARD}：CARD 正文以 Markdown 内联承载，故 {@code CARD} 可达 ⟺ {@code MARKDOWN} 可达</li>
 * </ul>
 */
@Component
public class PushFormatConverter {

    private static final Pattern MARKDOWN_LINK = Pattern.compile("\\[([^\\]]*)\\]\\(([^)]*)\\)");
    private static final Pattern INLINE_CODE = Pattern.compile("`([^`]+)`");

    /**
     * 按通道<b>优先级顺序</b>选出第一个可从 {@code source} 转换到的目标格式。
     * {@code supported} 为空或都不可达时返回 {@link PushFormat#PLAIN_TEXT}（恒可达的兜底）。
     */
    public PushFormat negotiate(List<PushFormat> supported, PushFormat source) {
        PushFormat src = normalizeSource(source);
        if (supported != null) {
            for (PushFormat target : supported) {
                if (target != null && reachable(src, target)) {
                    return target;
                }
            }
        }
        return PushFormat.PLAIN_TEXT;
    }

    /**
     * 把消息正文由其源格式转换到 {@code target} 并产出 {@link RenderedMessage}。
     * <p>目标不可达时尽力降级为 {@link PushFormat#PLAIN_TEXT}（与 {@link #negotiate} 的兜底一致）；
     * {@link PushFormat#CARD} 目标的正文转为 Markdown 内联文本（不可达 Markdown 时降级为纯文本）。
     */
    public RenderedMessage render(PushMessage message, PushFormat target) {
        PushMessage msg = message == null ? PushMessage.of("", "") : message;
        PushFormat src = normalizeSource(msg.sourceFormat());
        PushFormat tgt = target == null ? PushFormat.PLAIN_TEXT : target;

        if (tgt == PushFormat.CARD) {
            PushFormat bodyFormat = reachable(src, PushFormat.MARKDOWN)
                    ? PushFormat.MARKDOWN : PushFormat.PLAIN_TEXT;
            String body = convert(msg.content(), src, bodyFormat);
            return new RenderedMessage(msg.title(), body, PushFormat.CARD, msg.level());
        }
        PushFormat effective = reachable(src, tgt) ? tgt : PushFormat.PLAIN_TEXT;
        String body = convert(msg.content(), src, effective);
        return new RenderedMessage(msg.title(), body, effective, msg.level());
    }

    /** {@code source} 能否转换到 {@code target}。 */
    private boolean reachable(PushFormat from, PushFormat to) {
        if (to == PushFormat.CARD) {
            return reachable(from, PushFormat.MARKDOWN);
        }
        if (from == to) {
            return true;
        }
        // 文本三格式间唯一不可达的转换：HTML → MARKDOWN。
        return !(from == PushFormat.HTML && to == PushFormat.MARKDOWN);
    }

    /** 源格式归一：{@code null} / {@link PushFormat#CARD}（不可作源）归一为 {@link PushFormat#MARKDOWN}。 */
    private PushFormat normalizeSource(PushFormat source) {
        return (source == null || source == PushFormat.CARD) ? PushFormat.MARKDOWN : source;
    }

    /** 文本三格式间的转换。{@code from == to} 已在调用前/此处短路。 */
    private String convert(String content, PushFormat from, PushFormat to) {
        String c = content == null ? "" : content;
        if (from == to) {
            return c;
        }
        switch (to) {
            case PLAIN_TEXT:
                return from == PushFormat.HTML ? htmlToText(c) : markdownToText(c);
            case HTML:
                return from == PushFormat.MARKDOWN ? markdownToHtml(c) : textToHtml(c);
            case MARKDOWN:
            default:
                // 仅 PLAIN_TEXT → MARKDOWN 可达（透传）；HTML → MARKDOWN 不支持，调用方已降级。
                return c;
        }
    }

    // ---- 具体转换（best-effort、bounded） ------------------------------------------------

    private static String markdownToText(String md) {
        String t = md;
        t = t.replaceAll("(?m)^\\s{0,3}#{1,6}\\s+", "");          // 标题标记
        t = t.replaceAll("!\\[([^\\]]*)\\]\\([^)]*\\)", "$1");    // 图片 → alt
        t = t.replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1");     // 链接 → 文字
        t = t.replaceAll("(\\*\\*|__)(.+?)\\1", "$2");            // 粗体
        t = t.replaceAll("(\\*|_)(.+?)\\1", "$2");                // 斜体
        t = t.replaceAll("`([^`]*)`", "$1");                      // 行内代码
        t = t.replaceAll("(?m)^\\s{0,3}>\\s?", "");               // 引用
        return t;
    }

    private static String markdownToHtml(String md) {
        List<String> protectedHtml = new ArrayList<>();
        String t = escapeHtml(md);
        t = t.replaceAll("(?m)^\\s{0,3}#{1,6}\\s+", "");                              // 标题标记 → 去除
        t = protectInlineCode(t, protectedHtml);
        t = t.replaceAll("!\\[([^\\]]*)\\]\\([^)]*\\)", "$1");                        // 图片 → alt
        t = protectMarkdownLinks(t, protectedHtml);
        t = t.replaceAll("(\\*\\*|__)(.+?)\\1", "<b>$2</b>");                         // 粗体
        t = t.replaceAll("(\\*|_)(.+?)\\1", "<i>$2</i>");                             // 斜体
        return restoreProtectedHtml(t, protectedHtml);
    }

    private static String textToHtml(String text) {
        return escapeHtml(text);
    }

    private static String protectInlineCode(String text, List<String> protectedHtml) {
        Matcher matcher = INLINE_CODE.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String html = "<code>" + matcher.group(1) + "</code>";
            matcher.appendReplacement(sb, Matcher.quoteReplacement(protectHtml(html, protectedHtml)));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static String protectMarkdownLinks(String text, List<String> protectedHtml) {
        Matcher matcher = MARKDOWN_LINK.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String html = "<a href=\"" + matcher.group(2) + "\">" + matcher.group(1) + "</a>";
            matcher.appendReplacement(sb, Matcher.quoteReplacement(protectHtml(html, protectedHtml)));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static String protectHtml(String html, List<String> protectedHtml) {
        int index = protectedHtml.size();
        protectedHtml.add(html);
        return htmlToken(index);
    }

    private static String restoreProtectedHtml(String text, List<String> protectedHtml) {
        String restored = text;
        for (int i = 0; i < protectedHtml.size(); i++) {
            restored = restored.replace(htmlToken(i), protectedHtml.get(i));
        }
        return restored;
    }

    private static String htmlToken(int index) {
        // token 不得含 `_` / `*`：否则后续的粗体 / 斜体正则（如 `_HTML_` → `<i>HTML</i>`）会误伤占位符，
        // 导致 restoreProtectedHtml 无法还原、token 泄漏到输出。两端的   哨兵保证不与正文冲突。
        return "\u0000PUSHHTML" + index + "\u0000";
    }

    private static String htmlToText(String html) {
        String t = html.replaceAll("(?i)<br\\s*/?>", "\n");
        t = t.replaceAll("(?i)</p\\s*>", "\n");
        t = t.replaceAll("<[^>]+>", "");
        return unescapeHtml(t);
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String unescapeHtml(String s) {
        return s.replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&");
    }
}

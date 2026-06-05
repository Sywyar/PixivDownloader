package top.sywyar.pixivdownload.push;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.i18n.AppMessages;

import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 推送侧的<b>构建中心</b>：把一个通知 id + 级别 + 运行期占位符渲染成一条 {@link PushMessage}（Markdown 单源）。
 * <ul>
 *   <li>标题取 i18n {@code push.message.{id}.title}；正文取 {@code push.message.{id}.body}（Markdown 源）。</li>
 *   <li>正文 / 标题中的 {@code {{key}}} 运行期占位符由传入的 {@code placeholders} 替换；缺失的 key 替换为空串，
 *       <b>绝不</b>外发裸 {@code {{key}}}（与 {@code MailTemplateRegistry} 的取值占位符语义一致，便于两侧复用同一套键）。</li>
 * </ul>
 * 本类<b>刻意只接收原始 {@code id} / {@link PushLevel}</b>，<b>不</b>依赖上层的通知场景枚举，避免 {@code push}
 * 包反向依赖 {@code notification} 包。
 */
@Service
@RequiredArgsConstructor
public class PushMessageFactory {

    private static final String KEY_PREFIX = "push.message.";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_.\\-]+)\\s*}}");

    private final AppMessages messages;

    /**
     * 渲染一条推送消息。
     *
     * @param id           canonical id（与邮件模板 id 一致），用于拼 {@code push.message.{id}.title/.body}
     * @param level        推送级别（{@code null} 由 {@link PushMessage} 归一为 {@link PushLevel#INFO}）
     * @param locale       目标语言
     * @param placeholders {@code {{key}}} 运行期占位符的替换值；缺失的 key 替换为空串
     */
    public PushMessage render(String id, PushLevel level, Locale locale, Map<String, String> placeholders) {
        Map<String, String> values = placeholders == null ? Map.of() : placeholders;
        // 标题不做 Markdown 转义：标题在各通道的渲染并不统一为 Markdown（钉钉 / 企微按 Markdown 标题、
        // Telegram 以 HTML 加粗、飞书以 plain_text、Bark 纯文本），转义会在非 Markdown 渲染处留下可见反斜杠。
        String title = applyPlaceholders(messages.get(locale, KEY_PREFIX + id + ".title"), values, false);
        // 正文源格式恒为 Markdown：数据型占位符值先做 Markdown 字面转义，避免值里的 * / _ / [ ] 等与
        // Markdown 语法冲突被渲染器吞掉（如企业微信吞 Cron 的 *）；标记型占位符（*_md / *_html）保持原样。
        String body = applyPlaceholders(messages.get(locale, KEY_PREFIX + id + ".body"), values, true);
        return PushMessage.markdown(title, body, level);
    }

    /**
     * 单段 {@code {{key}}} 替换：缺失的 key 替换为空串，绝不保留裸占位符。
     * {@code escapeMarkdown=true} 时，对<b>数据型</b>占位符值做 Markdown 字面转义（{@link #isRawMarkup}
     * 标记型的 {@code *_md} / {@code *_html} 除外，其值本身就是 Markdown / HTML 片段）。
     */
    private static String applyPlaceholders(String text, Map<String, String> values, boolean escapeMarkdown) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        return substitute(text, key -> {
            String value = values.getOrDefault(key, "");
            return escapeMarkdown && !isRawMarkup(key) ? MarkdownEscape.escape(value) : value;
        });
    }

    /** 标记型占位符：键以 {@code _md} / {@code _html} 结尾的值本身即 Markdown / HTML 片段，原样代入、不转义。 */
    private static boolean isRawMarkup(String key) {
        return key.endsWith("_md") || key.endsWith("_html");
    }

    private static String substitute(String text, Function<String, String> resolver) {
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        while (matcher.find()) {
            String replacement = resolver.apply(matcher.group(1));
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement == null ? "" : replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}

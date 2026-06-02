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
        String title = applyPlaceholders(messages.get(locale, KEY_PREFIX + id + ".title"), values);
        String body = applyPlaceholders(messages.get(locale, KEY_PREFIX + id + ".body"), values);
        return PushMessage.markdown(title, body, level);
    }

    /** 单段 {@code {{key}}} 替换：缺失的 key 替换为空串，绝不保留裸占位符。 */
    private static String applyPlaceholders(String text, Map<String, String> values) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        return substitute(text, key -> values.getOrDefault(key, ""));
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

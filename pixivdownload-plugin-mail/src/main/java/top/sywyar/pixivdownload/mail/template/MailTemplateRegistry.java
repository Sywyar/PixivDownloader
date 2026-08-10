package top.sywyar.pixivdownload.mail.template;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.plugin.api.notification.NotificationTemplateCatalog;
import top.sywyar.pixivdownload.plugin.api.notification.NotificationTemplateContribution;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 邮件介质模板渲染器。
 * <p>
 * SMTP 配置测试模板仍归 mail 插件本地所有；业务通知模板按 {@code (id, mail, locale)} 从宿主纯值目录读取，
 * 该目录由实际场景插件通过 plugin-api 稳定契约贡献。
 * <ul>
 *   <li>本地配置测试 HTML 中的占位符有两种：
 *     <ul>
 *       <li>{@code {{i18n:key}}} —— 用当前 locale 在 {@code messages.properties} 中查值（先解析）。</li>
 *       <li>{@code {{key}}} —— 用调用方传入的 placeholders map 替换（后解析，可承接 i18n 值里的二级占位符）。</li>
 *     </ul>
 *   </li>
 *   <li>贡献模板已在场景插件注册时完成本地化，发送时只替换 {@code {{key}}} 运行期值。</li>
 *   <li>缺失的值占位符替换为空串而不是裸 {@code {{key}}}；i18n key 缺失时回退为 key 本身（loud-failure），方便发现遗漏。</li>
 * </ul>
 * 全程 UTF-8（HTML 文件以 UTF-8 读，properties 由 {@code MessageSource} 按 UTF-8 加载）。
 * 模板**绝不含** cookie / PHPSESSID / 密码。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MailTemplateRegistry {

    /** 发送测试 / "邮件配置成功" 通知。 */
    public static final String TEMPLATE_CONFIG_SUCCESS = "mail-config-success";
    private static final String I18N_PREFIX = "i18n:";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_.\\-:]+)\\s*}}");

    private final MessageResolver messages;
    private final NotificationTemplateCatalog notificationTemplates;

    private final Map<String, MailTemplate> localTemplates =
            Map.of(TEMPLATE_CONFIG_SUCCESS, MailTemplate.of(TEMPLATE_CONFIG_SUCCESS));

    /**
     * 渲染指定模板。
     *
     * @param id           本地测试模板 id 或已贡献业务场景 id
     * @param locale       目标语言；为空时跟随 JVM 默认 locale
     * @param placeholders {@code {{key}}} 值占位符的替换值；模板中出现但 map 中缺失的 key 替换为空串
     * @return 渲染好的 {@link RenderedMail}
     * @throws IllegalArgumentException 模板 id 未登记
     * @throws IOException              HTML 文件读取失败
     */
    public RenderedMail render(String id, Locale locale, Map<String, String> placeholders) throws IOException {
        Locale effective = locale == null ? Locale.getDefault() : locale;
        Map<String, String> values = placeholders == null ? Map.of() : placeholders;
        MailTemplate local = localTemplates.get(id);
        if (local != null) {
            String html = loadHtml(id);
            return new RenderedMail(
                    applyLocalPlaceholders(messages.get(effective, local.subjectKey()), effective, values),
                    applyLocalPlaceholders(html, effective, values));
        }
        NotificationTemplateContribution contributed = notificationTemplates
                .find(id, "mail", effective)
                .orElseThrow(() -> new IllegalArgumentException("unknown mail template id: " + id));
        return new RenderedMail(
                applyValuePlaceholders(contributed.titleTemplate(), values),
                applyValuePlaceholders(contributed.bodyTemplate(), values));
    }

    /** 公开给测试 / 文档生成器用；不可变。 */
    public Map<String, MailTemplate> templates() {
        Map<String, MailTemplate> templates = new LinkedHashMap<>(localTemplates);
        notificationTemplates.scenarioIds("mail")
                .forEach(id -> templates.put(id, MailTemplate.of(id)));
        return Map.copyOf(templates);
    }

    // ── 私有工具 ─────────────────────────────────────────────────────────────

    private static String loadHtml(String id) throws IOException {
        String path = "mail/templates/" + id + ".html";
        ClassLoader classLoader = MailTemplateRegistry.class.getClassLoader();
        InputStream in = classLoader.getResourceAsStream(path);
        if (in == null) {
            throw new IOException("mail template not found: " + id);
        }
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * 两段式替换：先解析 {@code {{i18n:key}}}（locale-aware），再解析 {@code {{key}}}（运行时值）。
     * 顺序很重要——i18n 值里可以再含 {@code {{username}}} 这类值占位符，由第二段一并补齐。
     */
    private String applyLocalPlaceholders(String text, Locale locale, Map<String, String> values) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String afterI18n = substitute(text, key -> {
            if (key.startsWith(I18N_PREFIX)) {
                String code = key.substring(I18N_PREFIX.length());
                return messages.get(locale, code);
            }
            return null; // 留给第二段处理
        });
        return applyValuePlaceholders(afterI18n, values);
    }

    private static String applyValuePlaceholders(String text, Map<String, String> values) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        return substitute(text, key -> values.getOrDefault(key, ""));
    }

    /** 通用占位符替换；resolver 返回 null 时保留原 {@code {{...}}} 等下一段处理。 */
    private static String substitute(String text, Function<String, String> resolver) {
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        while (matcher.find()) {
            String key = matcher.group(1);
            String replacement = resolver.apply(key);
            if (replacement == null) {
                matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group(0)));
            } else {
                matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
            }
        }
        matcher.appendTail(out);
        return out.toString();
    }
}

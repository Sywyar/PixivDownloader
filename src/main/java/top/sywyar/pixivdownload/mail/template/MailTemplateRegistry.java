package top.sywyar.pixivdownload.mail.template;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.i18n.AppMessages;

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
 * 邮件模板注册中心。
 * <p>
 * 单一事实源：登记每个模板 id 及其元数据，按 {@code (id, locale)} 取模板 HTML、做占位符替换，返回
 * {@link RenderedMail}（subject + htmlBody）。
 * <ul>
 *   <li>每个模板**只有一份 HTML 文件**：{@code classpath:/mail/templates/{id}.html}（UTF-8）。
 *       不再按 locale 拆 {@code _en.html}；文案改走 i18n。</li>
 *   <li>HTML 与 subject 中的占位符有两种：
 *     <ul>
 *       <li>{@code {{i18n:key}}} —— 用当前 locale 在 {@code messages.properties} 中查值（先解析）。</li>
 *       <li>{@code {{key}}} —— 用调用方传入的 placeholders map 替换（后解析，可承接 i18n 值里的二级占位符）。</li>
 *     </ul>
 *   </li>
 *   <li>subject 走 i18n：{@code mail.template.{id}.subject}，中英两份。</li>
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
    /** 过度访问警告 → 账号级暂停通知。 */
    public static final String TEMPLATE_OVERUSE_PAUSED = "overuse-paused";
    /** cookie 依赖型任务 dead cookie → 任务级挂起通知。 */
    public static final String TEMPLATE_AUTH_EXPIRED = "auth-expired";
    /** 单作品连续失败熔断挂起通知。 */
    public static final String TEMPLATE_CIRCUIT_BREAKER = "circuit-breaker";

    private static final String I18N_PREFIX = "i18n:";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_.\\-:]+)\\s*}}");

    private final AppMessages messages;

    private final Map<String, MailTemplate> templates = new LinkedHashMap<>() {{
        put(TEMPLATE_CONFIG_SUCCESS, MailTemplate.of(TEMPLATE_CONFIG_SUCCESS));
        put(TEMPLATE_OVERUSE_PAUSED, MailTemplate.of(TEMPLATE_OVERUSE_PAUSED));
        put(TEMPLATE_AUTH_EXPIRED, MailTemplate.of(TEMPLATE_AUTH_EXPIRED));
        put(TEMPLATE_CIRCUIT_BREAKER, MailTemplate.of(TEMPLATE_CIRCUIT_BREAKER));
    }};

    /**
     * 渲染指定模板。
     *
     * @param id           模板 id，必须已在 {@link #templates} 中登记
     * @param locale       目标语言；为空时跟随 JVM 默认 locale
     * @param placeholders {@code {{key}}} 值占位符的替换值；模板中出现但 map 中缺失的 key 替换为空串
     * @return 渲染好的 {@link RenderedMail}
     * @throws IllegalArgumentException 模板 id 未登记
     * @throws IOException              HTML 文件读取失败
     */
    public RenderedMail render(String id, Locale locale, Map<String, String> placeholders) throws IOException {
        MailTemplate template = templates.get(id);
        if (template == null) {
            throw new IllegalArgumentException("unknown mail template id: " + id);
        }

        Locale effective = locale == null ? Locale.getDefault() : locale;
        Map<String, String> values = placeholders == null ? Map.of() : placeholders;

        String html = loadHtml(id);
        String resolvedBody = applyPlaceholders(html, effective, values);

        String subject = messages.get(effective, template.subjectKey());
        String resolvedSubject = applyPlaceholders(subject, effective, values);

        return new RenderedMail(resolvedSubject, resolvedBody);
    }

    /** 公开给测试 / 文档生成器用；不可变。 */
    public Map<String, MailTemplate> templates() {
        return Map.copyOf(templates);
    }

    // ── 私有工具 ─────────────────────────────────────────────────────────────

    private static String loadHtml(String id) throws IOException {
        ClassPathResource resource = new ClassPathResource("mail/templates/" + id + ".html");
        if (!resource.exists()) {
            throw new IOException("mail template not found: " + id);
        }
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * 两段式替换：先解析 {@code {{i18n:key}}}（locale-aware），再解析 {@code {{key}}}（运行时值）。
     * 顺序很重要——i18n 值里可以再含 {@code {{username}}} 这类值占位符，由第二段一并补齐。
     */
    private String applyPlaceholders(String text, Locale locale, Map<String, String> values) {
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
        return substitute(afterI18n, key -> values.getOrDefault(key, ""));
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

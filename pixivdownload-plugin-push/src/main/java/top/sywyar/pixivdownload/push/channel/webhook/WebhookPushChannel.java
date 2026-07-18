package top.sywyar.pixivdownload.push.channel.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.push.OutboundRequest;
import top.sywyar.pixivdownload.push.PushChannel;
import top.sywyar.pixivdownload.push.PushChannelSettings;
import top.sywyar.pixivdownload.push.PushChannelType;
import top.sywyar.pixivdownload.push.PushFormat;
import top.sywyar.pixivdownload.push.PushHttpSender;
import top.sywyar.pixivdownload.push.PushResult;
import top.sywyar.pixivdownload.push.RenderedMessage;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 自定义 Webhook（DIY）通道。把已渲染消息套进用户提供的请求体模板后 POST 到用户提供的 URL，
 * 可对接 Discord / Slack / ntfy / Gotify 等任意 webhook。
 * <p>
 * 声明支持 {@link PushFormat#MARKDOWN} 与 {@link PushFormat#PLAIN_TEXT}：正文按协商结果原样注入模板
 * （默认源即 Markdown，注入 Markdown 文本）。模板支持 {@code {{title}}} / {@code {{content}}} 占位符；当内容类型
 * 为 JSON 时，占位符值会做 JSON 字符串转义，避免正文里的引号 / 换行破坏 JSON。模板留空时使用
 * {@code {"title":"{{title}}","content":"{{content}}"}}。只读取 {@link WebhookConfig}，与其它通道解耦；
 * 发送细节委托给 {@link PushHttpSender}。
 */
@Component
public class WebhookPushChannel implements PushChannel {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFAULT_TEMPLATE = "{\"title\":\"{{title}}\",\"content\":\"{{content}}\"}";
    private static final String PLACEHOLDER_TITLE = "{{title}}";
    private static final String PLACEHOLDER_CONTENT = "{{content}}";

    private final WebhookConfig config;
    private final PushHttpSender sender;

    public WebhookPushChannel(WebhookConfig config, PushHttpSender sender) {
        this.config = config;
        this.sender = sender;
    }

    @Override
    public PushChannelType type() {
        return PushChannelType.WEBHOOK;
    }

    @Override
    public boolean isConfigured() {
        return config.isEnabled() && config.toSettings().isComplete();
    }

    @Override
    public List<PushFormat> supportedFormats() {
        return List.of(PushFormat.MARKDOWN, PushFormat.PLAIN_TEXT);
    }

    @Override
    public PushResult send(RenderedMessage message) {
        return deliver(config.toSettings(), message);
    }

    @Override
    public PushResult sendTest(PushChannelSettings settings, RenderedMessage message) {
        if (settings instanceof WebhookSettings webhookSettings) {
            return deliver(webhookSettings, message);
        }
        return PushResult.failed(type(), PushResult.DETAIL_SETTINGS_TYPE_MISMATCH);
    }

    private PushResult deliver(WebhookSettings settings, RenderedMessage message) {
        if (!settings.isComplete()) {
            return PushResult.skipped(type(), PushResult.DETAIL_SETTINGS_INCOMPLETE);
        }
        if (message == null) {
            return PushResult.failed(type(), PushResult.DETAIL_UNEXPECTED_ERROR);
        }
        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(settings.contentType());
        } catch (Exception e) {
            return PushResult.failed(type(), PushResult.DETAIL_INVALID_CONTENT_TYPE);
        }
        boolean json = isJson(mediaType);
        String template = settings.bodyTemplate().isBlank() ? DEFAULT_TEMPLATE : settings.bodyTemplate();
        String title = json ? jsonEscape(message.title()) : message.title();
        String content = json ? jsonEscape(message.body()) : message.body();
        String rendered = template
                .replace(PLACEHOLDER_TITLE, title)
                .replace(PLACEHOLDER_CONTENT, content);

        byte[] body = rendered.getBytes(StandardCharsets.UTF_8);
        OutboundRequest request = new OutboundRequest(
                settings.url(), mediaType, body, List.of(settings.url()), settings.useProxy());
        return sender.send(type(), request);
    }

    private static boolean isJson(MediaType mediaType) {
        String subtype = mediaType.getSubtype().toLowerCase(java.util.Locale.ROOT);
        return subtype.equals("json") || subtype.endsWith("+json");
    }

    /** 把字符串转义为 JSON 字符串字面量的<b>内部</b>内容（去掉外层引号），供安全插入 JSON 模板。 */
    private static String jsonEscape(String value) {
        try {
            String quoted = MAPPER.writeValueAsString(value == null ? "" : value);
            // writeValueAsString 返回带首尾双引号的 JSON 字符串，这里取中间内容。
            return quoted.length() >= 2 ? quoted.substring(1, quoted.length() - 1) : quoted;
        } catch (Exception e) {
            // 退化兜底：最小化手工转义，保证不破坏 JSON 结构。
            return (value == null ? "" : value).replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n").replace("\r", "\\r");
        }
    }
}

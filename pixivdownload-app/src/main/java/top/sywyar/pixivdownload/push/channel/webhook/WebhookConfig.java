package top.sywyar.pixivdownload.push.channel.webhook;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 自定义 Webhook（DIY）通道配置，映射 {@code config.yaml} 中的 {@code push.webhook.*}。
 * <p>
 * 万能逃生通道：用户自填 URL + 内容类型 + 带 {@code {{title}}} / {@code {{content}}} 占位符的请求体模板，
 * 可对接 Discord / Slack / ntfy / Gotify 等任意 webhook。
 */
@Data
@Component
@ConfigurationProperties(prefix = "push.webhook")
public class WebhookConfig {

    public static final String KEY_ENABLED = "push.webhook.enabled";
    public static final String KEY_URL = "push.webhook.url";
    public static final String KEY_CONTENT_TYPE = "push.webhook.content-type";
    public static final String KEY_BODY_TEMPLATE = "push.webhook.body-template";
    public static final String KEY_USE_PROXY = "push.webhook.use-proxy";

    /** 是否启用本通道。 */
    private volatile boolean enabled = false;

    /** 目标 URL（http/https）。可能含 token，整条不写入日志。 */
    private volatile String url = "";

    /** 请求内容类型；默认 {@code application/json}。 */
    private volatile String contentType = "application/json";

    /**
     * 请求体模板，支持 {@code {{title}}} / {@code {{content}}} 占位符。留空时使用
     * {@code {"title":"{{title}}","content":"{{content}}"}}。当内容类型为 JSON 时，占位符值会自动做 JSON 转义。
     */
    private volatile String bodyTemplate = "";

    /** 是否走 HTTP 代理；默认关闭（海外 webhook 可按需开启）。 */
    private volatile boolean useProxy = false;

    /** 打包成不可变设置快照，供 {@link WebhookPushChannel} 请求时读取。 */
    public WebhookSettings toSettings() {
        return new WebhookSettings(url, contentType, bodyTemplate, useProxy);
    }
}

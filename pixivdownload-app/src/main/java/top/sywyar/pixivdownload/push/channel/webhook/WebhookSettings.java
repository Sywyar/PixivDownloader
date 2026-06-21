package top.sywyar.pixivdownload.push.channel.webhook;

import top.sywyar.pixivdownload.push.PushChannelSettings;
import top.sywyar.pixivdownload.push.PushChannelType;

import java.util.Locale;

/**
 * 自定义 Webhook 通道的不可变设置快照。由 {@link WebhookConfig#toSettings()} 或 GUI 测试表单构造。
 */
public record WebhookSettings(String url, String contentType, String bodyTemplate, boolean useProxy)
        implements PushChannelSettings {

    public WebhookSettings {
        url = url == null ? "" : url.trim();
        contentType = contentType == null || contentType.isBlank() ? "application/json" : contentType.trim();
        bodyTemplate = bodyTemplate == null ? "" : bodyTemplate;
    }

    @Override
    public PushChannelType type() {
        return PushChannelType.WEBHOOK;
    }

    @Override
    public boolean isComplete() {
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }
}

package top.sywyar.pixivdownload.push.channel.wecom;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.push.OutboundRequest;
import top.sywyar.pixivdownload.push.PushChannel;
import top.sywyar.pixivdownload.push.PushChannelSettings;
import top.sywyar.pixivdownload.push.PushChannelType;
import top.sywyar.pixivdownload.push.PushFormat;
import top.sywyar.pixivdownload.push.PushHttpSender;
import top.sywyar.pixivdownload.push.PushLevel;
import top.sywyar.pixivdownload.push.PushResult;
import top.sywyar.pixivdownload.push.RenderedMessage;

import java.util.List;

/**
 * 企业微信群机器人通道。{@code POST https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=...}。
 * <p>
 * 声明支持 {@link PushFormat#MARKDOWN}（{@code markdown} 消息，标题按 {@link PushLevel} 套
 * {@code <font color>} 着色）与 {@link PushFormat#PLAIN_TEXT}（{@code text} 消息）。无签名机制（key 在 URL）。
 * 只读取 {@link WecomConfig}，与其它通道解耦；发送细节委托给 {@link PushHttpSender}。
 */
@Component
public class WecomPushChannel implements PushChannel {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String WEBHOOK_BASE = "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=";

    private final WecomConfig config;
    private final PushHttpSender sender;

    public WecomPushChannel(WecomConfig config, PushHttpSender sender) {
        this.config = config;
        this.sender = sender;
    }

    @Override
    public PushChannelType type() {
        return PushChannelType.WECOM;
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
        if (settings instanceof WecomSettings wecomSettings) {
            return deliver(wecomSettings, message);
        }
        return PushResult.failed(type(), PushResult.DETAIL_SETTINGS_TYPE_MISMATCH);
    }

    private PushResult deliver(WecomSettings settings, RenderedMessage message) {
        if (!settings.isComplete()) {
            return PushResult.skipped(type(), PushResult.DETAIL_SETTINGS_INCOMPLETE);
        }
        if (message == null) {
            return PushResult.failed(type(), PushResult.DETAIL_UNEXPECTED_ERROR);
        }
        String url = WEBHOOK_BASE + settings.key();
        Object payload = message.format() == PushFormat.MARKDOWN
                ? markdownPayload(message)
                : textPayload(message);

        byte[] body;
        try {
            body = MAPPER.writeValueAsBytes(payload);
        } catch (Exception e) {
            return PushResult.failed(type(), PushResult.DETAIL_SERIALIZATION_FAILED);
        }
        OutboundRequest request = OutboundRequest.json(url, body, List.of(settings.key()), settings.useProxy());
        return sender.send(type(), request);
    }

    private static MarkdownPayload markdownPayload(RenderedMessage message) {
        String content = message.title().isBlank()
                ? message.body()
                : "# <font color=\"" + wecomColor(message.level()) + "\">" + message.title() + "</font>\n\n"
                        + message.body();
        return new MarkdownPayload("markdown", new Markdown(content));
    }

    private static TextPayload textPayload(RenderedMessage message) {
        String content = message.title().isBlank()
                ? message.body()
                : message.title() + "\n\n" + message.body();
        return new TextPayload("text", new Text(content));
    }

    /** 严重级别映射到企业微信 markdown 字体色（仅 info=绿 / comment=灰 / warning=红 三色）。 */
    private static String wecomColor(PushLevel level) {
        return level == PushLevel.INFO ? "info" : "warning";
    }

    private record MarkdownPayload(String msgtype, Markdown markdown) {
    }

    private record Markdown(String content) {
    }

    private record TextPayload(String msgtype, Text text) {
    }

    private record Text(String content) {
    }
}

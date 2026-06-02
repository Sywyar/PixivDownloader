package top.sywyar.pixivdownload.push.channel.wecom;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.push.OutboundRequest;
import top.sywyar.pixivdownload.push.PushChannel;
import top.sywyar.pixivdownload.push.PushChannelSettings;
import top.sywyar.pixivdownload.push.PushChannelType;
import top.sywyar.pixivdownload.push.PushHttpSender;
import top.sywyar.pixivdownload.push.PushMessage;
import top.sywyar.pixivdownload.push.PushResult;

import java.util.List;

/**
 * 企业微信群机器人通道。{@code POST https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=...}，markdown 消息。
 * <p>
 * 无签名机制（key 在 URL）。只读取 {@link WecomConfig}，与其它通道解耦；发送细节委托给 {@link PushHttpSender}。
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
    public PushResult send(PushMessage message) {
        return deliver(config.toSettings(), message);
    }

    @Override
    public PushResult sendTest(PushChannelSettings settings, PushMessage message) {
        if (settings instanceof WecomSettings wecomSettings) {
            return deliver(wecomSettings, message);
        }
        return PushResult.failed(type(), "settings type mismatch");
    }

    private PushResult deliver(WecomSettings settings, PushMessage message) {
        if (!settings.isComplete()) {
            return PushResult.skipped(type(), "incomplete settings");
        }
        String url = WEBHOOK_BASE + settings.key();
        String content = message.title().isBlank()
                ? message.content()
                : "# " + message.title() + "\n\n" + message.content();
        Payload payload = new Payload("markdown", new Markdown(content));

        byte[] body;
        try {
            body = MAPPER.writeValueAsBytes(payload);
        } catch (Exception e) {
            return PushResult.failed(type(), "serialize error");
        }
        OutboundRequest request = OutboundRequest.json(url, body, List.of(settings.key()), settings.useProxy());
        return sender.send(type(), request);
    }

    private record Payload(String msgtype, Markdown markdown) {
    }

    private record Markdown(String content) {
    }
}

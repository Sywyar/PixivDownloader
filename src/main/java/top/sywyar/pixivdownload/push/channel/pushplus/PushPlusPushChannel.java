package top.sywyar.pixivdownload.push.channel.pushplus;

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
 * PushPlus（推送加）通道。{@code POST https://www.pushplus.plus/send}，JSON 体携带 token / title / content。
 * <p>
 * 无签名机制（token 在 body）。只读取 {@link PushPlusConfig}，与其它通道解耦；发送细节委托给 {@link PushHttpSender}。
 */
@Component
public class PushPlusPushChannel implements PushChannel {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SEND_URL = "https://www.pushplus.plus/send";

    private final PushPlusConfig config;
    private final PushHttpSender sender;

    public PushPlusPushChannel(PushPlusConfig config, PushHttpSender sender) {
        this.config = config;
        this.sender = sender;
    }

    @Override
    public PushChannelType type() {
        return PushChannelType.PUSHPLUS;
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
        if (settings instanceof PushPlusSettings pushPlusSettings) {
            return deliver(pushPlusSettings, message);
        }
        return PushResult.failed(type(), "settings type mismatch");
    }

    private PushResult deliver(PushPlusSettings settings, PushMessage message) {
        if (!settings.isComplete()) {
            return PushResult.skipped(type(), "incomplete settings");
        }
        Payload payload = new Payload(settings.token(), message.title(), message.content());

        byte[] body;
        try {
            body = MAPPER.writeValueAsBytes(payload);
        } catch (Exception e) {
            return PushResult.failed(type(), "serialize error");
        }
        OutboundRequest request = OutboundRequest.json(
                SEND_URL, body, List.of(settings.token()), settings.useProxy());
        return sender.send(type(), request);
    }

    private record Payload(String token, String title, String content) {
    }
}

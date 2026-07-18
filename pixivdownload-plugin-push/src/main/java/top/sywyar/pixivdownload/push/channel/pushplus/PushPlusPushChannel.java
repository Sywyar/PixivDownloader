package top.sywyar.pixivdownload.push.channel.pushplus;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.push.OutboundRequest;
import top.sywyar.pixivdownload.push.PushChannel;
import top.sywyar.pixivdownload.push.PushChannelSettings;
import top.sywyar.pixivdownload.push.PushChannelType;
import top.sywyar.pixivdownload.push.PushFormat;
import top.sywyar.pixivdownload.push.PushHttpSender;
import top.sywyar.pixivdownload.push.PushResult;
import top.sywyar.pixivdownload.push.RenderedMessage;

import java.util.List;

/**
 * PushPlus（推送加）通道。{@code POST https://www.pushplus.plus/send}，JSON 体携带 token / title / content / template。
 * <p>
 * 声明支持 {@link PushFormat#MARKDOWN}（{@code template=markdown}）与 {@link PushFormat#PLAIN_TEXT}
 * （{@code template=txt}）。注意 PushPlus 不传 {@code template} 时默认 {@code html} 模板会折叠 {@code \n}
 * 换行，故这里显式按格式下发 {@code template}。无签名机制（token 在 body）。只读取 {@link PushPlusConfig}，
 * 与其它通道解耦；发送细节委托给 {@link PushHttpSender}。
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
    public List<PushFormat> supportedFormats() {
        return List.of(PushFormat.MARKDOWN, PushFormat.PLAIN_TEXT);
    }

    @Override
    public PushResult send(RenderedMessage message) {
        return deliver(config.toSettings(), message);
    }

    @Override
    public PushResult sendTest(PushChannelSettings settings, RenderedMessage message) {
        if (settings instanceof PushPlusSettings pushPlusSettings) {
            return deliver(pushPlusSettings, message);
        }
        return PushResult.failed(type(), PushResult.DETAIL_SETTINGS_TYPE_MISMATCH);
    }

    private PushResult deliver(PushPlusSettings settings, RenderedMessage message) {
        if (!settings.isComplete()) {
            return PushResult.skipped(type(), PushResult.DETAIL_SETTINGS_INCOMPLETE);
        }
        if (message == null) {
            return PushResult.failed(type(), PushResult.DETAIL_UNEXPECTED_ERROR);
        }
        String template = message.format() == PushFormat.MARKDOWN ? "markdown" : "txt";
        Payload payload = new Payload(settings.token(), message.title(), message.body(), template);

        byte[] body;
        try {
            body = MAPPER.writeValueAsBytes(payload);
        } catch (Exception e) {
            return PushResult.failed(type(), PushResult.DETAIL_SERIALIZATION_FAILED);
        }
        OutboundRequest request = OutboundRequest.json(
                SEND_URL, body, List.of(settings.token()), settings.useProxy());
        return sender.send(type(), request);
    }

    private record Payload(String token, String title, String content, String template) {
    }
}

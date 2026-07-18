package top.sywyar.pixivdownload.push.channel.bark;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
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
 * Bark（iOS 推送）通道。{@code POST {server}/push}，JSON 体携带 device_key / title / body / sound / level。
 * <p>
 * iOS 通知正文不渲染 Markdown / HTML，故只声明支持 {@link PushFormat#PLAIN_TEXT}；
 * {@link PushLevel#ERROR} 映射为 Bark 的 {@code timeSensitive} 中断级别（可在专注模式下展示）。
 * 渲染逻辑集中在 {@link #deliver}，{@link #send}（已保存配置）与 {@link #sendTest}（GUI 临时设置）共用它。
 * 只读取 {@link BarkConfig}，与其它通道解耦；发送细节委托给 {@link PushHttpSender}。
 */
@Component
public class BarkPushChannel implements PushChannel {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final BarkConfig config;
    private final PushHttpSender sender;

    public BarkPushChannel(BarkConfig config, PushHttpSender sender) {
        this.config = config;
        this.sender = sender;
    }

    @Override
    public PushChannelType type() {
        return PushChannelType.BARK;
    }

    @Override
    public boolean isConfigured() {
        return config.isEnabled() && config.toSettings().isComplete();
    }

    @Override
    public List<PushFormat> supportedFormats() {
        return List.of(PushFormat.PLAIN_TEXT);
    }

    @Override
    public PushResult send(RenderedMessage message) {
        return deliver(config.toSettings(), message);
    }

    @Override
    public PushResult sendTest(PushChannelSettings settings, RenderedMessage message) {
        if (settings instanceof BarkSettings barkSettings) {
            return deliver(barkSettings, message);
        }
        return PushResult.failed(type(), PushResult.DETAIL_SETTINGS_TYPE_MISMATCH);
    }

    private PushResult deliver(BarkSettings settings, RenderedMessage message) {
        if (!settings.isComplete()) {
            return PushResult.skipped(type(), PushResult.DETAIL_SETTINGS_INCOMPLETE);
        }
        if (message == null) {
            return PushResult.failed(type(), PushResult.DETAIL_UNEXPECTED_ERROR);
        }
        String url = stripTrailingSlash(settings.server()) + "/push";
        String sound = settings.sound().isBlank() ? null : settings.sound();
        Payload payload = new Payload(
                settings.deviceKey(), message.title(), message.body(), sound, barkLevel(message.level()));

        byte[] body;
        try {
            body = MAPPER.writeValueAsBytes(payload);
        } catch (Exception e) {
            return PushResult.failed(type(), PushResult.DETAIL_SERIALIZATION_FAILED);
        }
        OutboundRequest request = OutboundRequest.json(
                url, body, List.of(settings.deviceKey()), settings.useProxy());
        return sender.send(type(), request);
    }

    /** 严重级别映射到 Bark 中断级别；非 ERROR 用默认（返回 {@code null} 则不下发该字段）。 */
    private static String barkLevel(PushLevel level) {
        return level == PushLevel.ERROR ? "timeSensitive" : null;
    }

    private static String stripTrailingSlash(String s) {
        String t = s;
        while (t.endsWith("/")) {
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record Payload(
            @JsonProperty("device_key") String deviceKey,
            String title,
            String body,
            String sound,
            String level
    ) {
    }
}

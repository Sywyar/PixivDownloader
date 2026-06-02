package top.sywyar.pixivdownload.push.channel.telegram;

import com.fasterxml.jackson.annotation.JsonProperty;
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
 * Telegram Bot 通道。{@code POST https://api.telegram.org/bot{token}/sendMessage}，JSON 体携带 chat_id / text。
 * <p>
 * 正文按纯文本发送（不设 parse_mode），避免对正文做 HTML/Markdown 转义。渲染逻辑集中在 {@link #deliver}，
 * {@link #send}（已保存配置）与 {@link #sendTest}（GUI 临时设置）共用它。只读取 {@link TelegramConfig}，
 * 与其它通道解耦；发送细节委托给 {@link PushHttpSender}。
 */
@Component
public class TelegramPushChannel implements PushChannel {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TelegramConfig config;
    private final PushHttpSender sender;

    public TelegramPushChannel(TelegramConfig config, PushHttpSender sender) {
        this.config = config;
        this.sender = sender;
    }

    @Override
    public PushChannelType type() {
        return PushChannelType.TELEGRAM;
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
        if (settings instanceof TelegramSettings telegramSettings) {
            return deliver(telegramSettings, message);
        }
        return PushResult.failed(type(), "settings type mismatch");
    }

    private PushResult deliver(TelegramSettings settings, PushMessage message) {
        if (!settings.isComplete()) {
            return PushResult.skipped(type(), "incomplete settings");
        }
        String token = settings.botToken();
        String url = "https://api.telegram.org/bot" + token + "/sendMessage";
        String text = message.title().isBlank()
                ? message.content()
                : message.title() + "\n\n" + message.content();
        Payload payload = new Payload(settings.chatId(), text);

        byte[] body;
        try {
            body = MAPPER.writeValueAsBytes(payload);
        } catch (Exception e) {
            return PushResult.failed(type(), "serialize error");
        }
        OutboundRequest request = OutboundRequest.json(url, body, List.of(token), settings.useProxy());
        return sender.send(type(), request);
    }

    private record Payload(@JsonProperty("chat_id") String chatId, String text) {
    }
}

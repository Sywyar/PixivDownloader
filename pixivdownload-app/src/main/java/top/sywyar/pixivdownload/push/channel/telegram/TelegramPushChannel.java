package top.sywyar.pixivdownload.push.channel.telegram;

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
import top.sywyar.pixivdownload.push.PushResult;
import top.sywyar.pixivdownload.push.RenderedMessage;

import java.util.List;

/**
 * Telegram Bot 通道。{@code POST https://api.telegram.org/bot{token}/sendMessage}，JSON 体携带
 * chat_id / text / parse_mode。
 * <p>
 * 声明支持 {@link PushFormat#HTML}（{@code parse_mode=HTML}，标题加粗）与 {@link PushFormat#PLAIN_TEXT}
 * （不设 parse_mode）。正文已由框架渲染为目标格式，本通道仅在 HTML 模式下额外转义并加粗标题。
 * 渲染逻辑集中在 {@link #deliver}；只读取 {@link TelegramConfig}，与其它通道解耦；发送细节委托给
 * {@link PushHttpSender}。
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
    public List<PushFormat> supportedFormats() {
        return List.of(PushFormat.HTML, PushFormat.PLAIN_TEXT);
    }

    @Override
    public PushResult send(RenderedMessage message) {
        return deliver(config.toSettings(), message);
    }

    @Override
    public PushResult sendTest(PushChannelSettings settings, RenderedMessage message) {
        if (settings instanceof TelegramSettings telegramSettings) {
            return deliver(telegramSettings, message);
        }
        return PushResult.failed(type(), "settings type mismatch");
    }

    private PushResult deliver(TelegramSettings settings, RenderedMessage message) {
        if (!settings.isComplete()) {
            return PushResult.skipped(type(), "incomplete settings");
        }
        String token = settings.botToken();
        String url = "https://api.telegram.org/bot" + token + "/sendMessage";

        boolean html = message.format() == PushFormat.HTML;
        String parseMode = html ? "HTML" : null;
        String header = message.title().isBlank()
                ? ""
                : (html ? "<b>" + escapeHtml(message.title()) + "</b>" : message.title()) + "\n\n";
        String text = header + message.body();
        Payload payload = new Payload(settings.chatId(), text, parseMode);

        byte[] body;
        try {
            body = MAPPER.writeValueAsBytes(payload);
        } catch (Exception e) {
            return PushResult.failed(type(), "serialize error");
        }
        OutboundRequest request = OutboundRequest.json(url, body, List.of(token), settings.useProxy());
        return sender.send(type(), request);
    }

    /** Telegram HTML 模式下标题须转义 {@code & < >}（正文已由框架转义）。 */
    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record Payload(@JsonProperty("chat_id") String chatId, String text,
                           @JsonProperty("parse_mode") String parseMode) {
    }
}

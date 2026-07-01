package top.sywyar.pixivdownload.push.channel.feishu;

import com.fasterxml.jackson.annotation.JsonInclude;
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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 飞书自定义机器人通道。{@code POST https://open.feishu.cn/open-apis/bot/v2/hook/{webhookKey}}。
 * <p>
 * 声明支持 {@link PushFormat#CARD}（{@code interactive} 消息卡片：header 颜色按 {@link PushLevel} 着色、
 * 正文用 {@code lark_md} 渲染 Markdown）与 {@link PushFormat#PLAIN_TEXT}（{@code text} 消息）。
 * 「签名校验」安全设置算法与钉钉<b>不同</b>——以 {@code timestamp(秒) + "\n" + secret} 作为 HMAC-SHA256 的
 * <b>密钥</b>对<b>空串</b>签名、Base64（不再 URL 编码），并把 {@code timestamp}/{@code sign} 放进<b>请求体</b>。
 * 只读取 {@link FeishuConfig}，与其它通道解耦；发送细节委托给 {@link PushHttpSender}。
 */
@Component
public class FeishuPushChannel implements PushChannel {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String HOOK_BASE = "https://open.feishu.cn/open-apis/bot/v2/hook/";

    private final FeishuConfig config;
    private final PushHttpSender sender;

    public FeishuPushChannel(FeishuConfig config, PushHttpSender sender) {
        this.config = config;
        this.sender = sender;
    }

    @Override
    public PushChannelType type() {
        return PushChannelType.FEISHU;
    }

    @Override
    public boolean isConfigured() {
        return config.isEnabled() && config.toSettings().isComplete();
    }

    @Override
    public List<PushFormat> supportedFormats() {
        return List.of(PushFormat.CARD, PushFormat.PLAIN_TEXT);
    }

    @Override
    public PushResult send(RenderedMessage message) {
        return deliver(config.toSettings(), message);
    }

    @Override
    public PushResult sendTest(PushChannelSettings settings, RenderedMessage message) {
        if (settings instanceof FeishuSettings feishuSettings) {
            return deliver(feishuSettings, message);
        }
        return PushResult.failed(type(), "settings type mismatch");
    }

    private PushResult deliver(FeishuSettings settings, RenderedMessage message) {
        if (!settings.isComplete()) {
            return PushResult.skipped(type(), "incomplete settings");
        }
        String url = HOOK_BASE + settings.webhookKey();

        String timestamp = null;
        String sign = null;
        if (!settings.secret().isBlank()) {
            timestamp = String.valueOf(System.currentTimeMillis() / 1000L);
            try {
                sign = sign(settings.secret(), timestamp);
            } catch (Exception e) {
                return PushResult.failed(type(), "sign error");
            }
        }

        Object payload = message.format() == PushFormat.CARD
                ? cardPayload(timestamp, sign, message)
                : textPayload(timestamp, sign, message);

        byte[] body;
        try {
            body = MAPPER.writeValueAsBytes(payload);
        } catch (Exception e) {
            return PushResult.failed(type(), "serialize error");
        }
        OutboundRequest request = OutboundRequest.json(
                url, body, List.of(settings.webhookKey(), settings.secret()), settings.useProxy());
        return sender.send(type(), request);
    }

    private static CardPayload cardPayload(String timestamp, String sign, RenderedMessage message) {
        CardHeader header = message.title().isBlank()
                ? null
                : new CardHeader(new CardTitle("plain_text", message.title()), feishuColor(message.level()));
        List<CardElement> elements = List.of(new CardElement("div", new LarkText("lark_md", message.body())));
        Card card = new Card(new CardConfig(true), header, elements);
        return new CardPayload(timestamp, sign, "interactive", card);
    }

    private static TextPayload textPayload(String timestamp, String sign, RenderedMessage message) {
        String text = message.title().isBlank()
                ? message.body()
                : message.title() + "\n\n" + message.body();
        return new TextPayload(timestamp, sign, "text", new TextContent(text));
    }

    /** 严重级别映射到飞书卡片 header 颜色。 */
    private static String feishuColor(PushLevel level) {
        return switch (level) {
            case ERROR -> "red";
            case WARNING -> "orange";
            case INFO -> "blue";
        };
    }

    /**
     * 飞书「签名校验」算法：以 {@code timestamp + "\n" + secret} 为 HMAC-SHA256 的<b>密钥</b>，对<b>空串</b>签名后
     * Base64（不 URL 编码）。{@code timestamp} 为<b>秒</b>，且须与请求体里的一致，故由调用方传入同一值。
     * <p>纯算法、不含任何已保存凭证（secret 由调用方传入），{@code public} 以便在父包中直接做算法单元测试。
     */
    public static String sign(String secret, String timestamp) throws Exception {
        String stringToSign = timestamp + "\n" + secret;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(stringToSign.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] signData = mac.doFinal(new byte[0]);
        return java.util.Base64.getEncoder().encodeToString(signData);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record TextPayload(String timestamp, String sign, String msg_type, TextContent content) {
    }

    private record TextContent(String text) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record CardPayload(String timestamp, String sign, String msg_type, Card card) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record Card(CardConfig config, CardHeader header, List<CardElement> elements) {
    }

    private record CardConfig(boolean wide_screen_mode) {
    }

    private record CardHeader(CardTitle title, String template) {
    }

    private record CardTitle(String tag, String content) {
    }

    private record CardElement(String tag, LarkText text) {
    }

    private record LarkText(String tag, String content) {
    }
}

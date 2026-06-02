package top.sywyar.pixivdownload.push.channel.feishu;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.push.OutboundRequest;
import top.sywyar.pixivdownload.push.PushChannel;
import top.sywyar.pixivdownload.push.PushChannelSettings;
import top.sywyar.pixivdownload.push.PushChannelType;
import top.sywyar.pixivdownload.push.PushHttpSender;
import top.sywyar.pixivdownload.push.PushMessage;
import top.sywyar.pixivdownload.push.PushResult;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 飞书自定义机器人通道。{@code POST https://open.feishu.cn/open-apis/bot/v2/hook/{webhookKey}}，text 消息。
 * <p>
 * 「签名校验」安全设置：算法与钉钉<b>不同</b>——以 {@code timestamp(秒) + "\n" + secret} 作为 HMAC-SHA256 的
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
    public PushResult send(PushMessage message) {
        return deliver(config.toSettings(), message);
    }

    @Override
    public PushResult sendTest(PushChannelSettings settings, PushMessage message) {
        if (settings instanceof FeishuSettings feishuSettings) {
            return deliver(feishuSettings, message);
        }
        return PushResult.failed(type(), "settings type mismatch");
    }

    private PushResult deliver(FeishuSettings settings, PushMessage message) {
        if (!settings.isComplete()) {
            return PushResult.skipped(type(), "incomplete settings");
        }
        String url = HOOK_BASE + settings.webhookKey();
        String text = message.title().isBlank()
                ? message.content()
                : message.title() + "\n\n" + message.content();

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
        Payload payload = new Payload(timestamp, sign, "text", new Content(text));

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
    private record Payload(String timestamp, String sign, String msg_type, Content content) {
    }

    private record Content(String text) {
    }
}

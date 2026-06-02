package top.sywyar.pixivdownload.push.channel.dingtalk;

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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 钉钉自定义机器人通道。{@code POST https://oapi.dingtalk.com/robot/send?access_token=...}。
 * <p>
 * 声明支持 {@link PushFormat#MARKDOWN}（{@code markdown} 消息体，标题作 {@code ####} 标题）与
 * {@link PushFormat#PLAIN_TEXT}（{@code text} 消息体）。渲染逻辑集中在 {@link #deliver}，
 * {@link #send}（已保存配置）与 {@link #sendTest}（GUI 临时设置）共用它。只读取 {@link DingTalkConfig}，
 * 与其它通道解耦；发送细节委托给 {@link PushHttpSender}。
 */
@Component
public class DingTalkPushChannel implements PushChannel {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String WEBHOOK_BASE = "https://oapi.dingtalk.com/robot/send?access_token=";

    private final DingTalkConfig config;
    private final PushHttpSender sender;

    public DingTalkPushChannel(DingTalkConfig config, PushHttpSender sender) {
        this.config = config;
        this.sender = sender;
    }

    @Override
    public PushChannelType type() {
        return PushChannelType.DINGTALK;
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
        if (settings instanceof DingTalkSettings dingTalkSettings) {
            return deliver(dingTalkSettings, message);
        }
        return PushResult.failed(type(), "settings type mismatch");
    }

    private PushResult deliver(DingTalkSettings settings, RenderedMessage message) {
        if (!settings.isComplete()) {
            return PushResult.skipped(type(), "incomplete settings");
        }
        String token = settings.accessToken();
        String secret = settings.secret();

        // 「加签」安全设置：URL 追加 timestamp + HMAC-SHA256 签名（Base64 后再 URL 编码一次）。
        // 签名已在此处完成唯一一次 URL 编码，因此发送层必须以 URI 直发、不得再次编码（见 PushHttpSender）。
        String url = WEBHOOK_BASE + token;
        List<String> secrets = new ArrayList<>();
        secrets.add(token);
        if (!secret.isBlank()) {
            long timestamp = System.currentTimeMillis();
            String sign;
            try {
                sign = sign(secret, timestamp);
            } catch (Exception e) {
                return PushResult.failed(type(), "sign error");
            }
            url = url + "&timestamp=" + timestamp + "&sign=" + sign;
            secrets.add(secret);
        }

        Object payload = message.format() == PushFormat.MARKDOWN
                ? markdownPayload(message)
                : textPayload(message);

        byte[] body;
        try {
            body = MAPPER.writeValueAsBytes(payload);
        } catch (Exception e) {
            return PushResult.failed(type(), "serialize error");
        }
        OutboundRequest request = OutboundRequest.json(url, body, secrets, settings.useProxy());
        return sender.send(type(), request);
    }

    private static MarkdownPayload markdownPayload(RenderedMessage message) {
        String text = message.title().isBlank()
                ? message.body()
                : "#### " + message.title() + "\n\n" + message.body();
        // markdown 消息的 title 仅用于通知摘要、不展示，缺省用正文兜底。
        String title = message.title().isBlank() ? message.body() : message.title();
        return new MarkdownPayload("markdown", new Markdown(title, text));
    }

    private static TextPayload textPayload(RenderedMessage message) {
        String content = message.title().isBlank()
                ? message.body()
                : message.title() + "\n\n" + message.body();
        return new TextPayload("text", new Text(content));
    }

    /**
     * 钉钉「加签」算法：以 {@code timestamp + "\n" + secret} 为待签名串，HMAC-SHA256 后 Base64，再 URL 编码一次。
     * {@code timestamp} 必须与拼入 URL 的完全一致，否则签名校验失败，故由调用方传入同一个值。
     * <p>纯算法、不含任何已保存凭证（secret 由调用方传入），{@code public} 以便在父包中直接做算法单元测试。
     */
    public static String sign(String secret, long timestamp) throws Exception {
        String stringToSign = timestamp + "\n" + secret;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] signData = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
        return URLEncoder.encode(Base64.getEncoder().encodeToString(signData), StandardCharsets.UTF_8);
    }

    private record MarkdownPayload(String msgtype, Markdown markdown) {
    }

    private record Markdown(String title, String text) {
    }

    private record TextPayload(String msgtype, Text text) {
    }

    private record Text(String content) {
    }
}

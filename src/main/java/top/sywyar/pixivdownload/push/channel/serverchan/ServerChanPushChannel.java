package top.sywyar.pixivdownload.push.channel.serverchan;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.push.OutboundRequest;
import top.sywyar.pixivdownload.push.PushChannel;
import top.sywyar.pixivdownload.push.PushChannelSettings;
import top.sywyar.pixivdownload.push.PushChannelType;
import top.sywyar.pixivdownload.push.PushFormat;
import top.sywyar.pixivdownload.push.PushHttpSender;
import top.sywyar.pixivdownload.push.PushResult;
import top.sywyar.pixivdownload.push.RenderedMessage;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Server 酱（Turbo / ³）通道。表单 {@code application/x-www-form-urlencoded} POST，参数 {@code title} / {@code desp}。
 * <p>
 * {@code desp} 渲染 Markdown，故声明支持 {@link PushFormat#MARKDOWN} 与 {@link PushFormat#PLAIN_TEXT}
 * （两者均直接作为 {@code desp} 文本）。端点按官方 SDK 自适应：{@code sctp} 前缀的 SendKey 走
 * {@code https://{key}.push.ft07.com/send}（Server 酱³），否则走 {@code https://sctapi.ftqq.com/{key}.send}
 * （Turbo）。只读取 {@link ServerChanConfig}，与其它通道解耦；发送细节委托给 {@link PushHttpSender}。
 */
@Component
public class ServerChanPushChannel implements PushChannel {

    private final ServerChanConfig config;
    private final PushHttpSender sender;

    public ServerChanPushChannel(ServerChanConfig config, PushHttpSender sender) {
        this.config = config;
        this.sender = sender;
    }

    @Override
    public PushChannelType type() {
        return PushChannelType.SERVERCHAN;
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
        if (settings instanceof ServerChanSettings serverChanSettings) {
            return deliver(serverChanSettings, message);
        }
        return PushResult.failed(type(), "settings type mismatch");
    }

    private PushResult deliver(ServerChanSettings settings, RenderedMessage message) {
        if (!settings.isComplete()) {
            return PushResult.skipped(type(), "incomplete settings");
        }
        String key = settings.sendKey();
        String url = key.startsWith("sctp")
                ? "https://" + key + ".push.ft07.com/send"
                : "https://sctapi.ftqq.com/" + key + ".send";

        String form = "title=" + enc(message.title()) + "&desp=" + enc(message.body());
        byte[] body = form.getBytes(StandardCharsets.UTF_8);

        OutboundRequest request = new OutboundRequest(
                url, MediaType.APPLICATION_FORM_URLENCODED, body, List.of(key), settings.useProxy());
        return sender.send(type(), request);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}

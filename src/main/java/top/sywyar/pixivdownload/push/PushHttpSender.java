package top.sywyar.pixivdownload.push;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.i18n.AppMessages;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 推送框架的<b>共享传输层</b>：把 {@link OutboundRequest} 发出去，统一处理代理选择、错误归类、脱敏与日志。
 * <p>
 * 以组合（而非继承）方式被各 {@link PushChannel} 复用——通道只负责把消息渲染成 {@link OutboundRequest}，
 * 发送细节全在这里收敛。任何失败都收敛为 {@link PushResult#failed}（best-effort），错误详情按
 * {@link OutboundRequest#secrets()} 脱敏后截断，绝不写入 token / device-key。
 */
@Component
@Slf4j
public class PushHttpSender {

    private static final int DETAIL_MAX_LEN = 500;

    private final RestTemplate directRestTemplate;
    private final RestTemplate proxyRestTemplate;
    private final AppMessages messages;

    public PushHttpSender(@Qualifier("pushRestTemplate") RestTemplate directRestTemplate,
                          @Qualifier("pushProxyRestTemplate") RestTemplate proxyRestTemplate,
                          AppMessages messages) {
        this.directRestTemplate = directRestTemplate;
        this.proxyRestTemplate = proxyRestTemplate;
        this.messages = messages;
    }

    /**
     * 发送一次调用。HTTP 2xx 记为成功；非 2xx / 网络异常归为失败并返回脱敏详情。<b>不抛异常。</b>
     */
    public PushResult send(PushChannelType type, OutboundRequest request) {
        // 以 URI 直发，绝不让 RestTemplate 把 URL 当作模板再编码一次——通道（如钉钉「加签」）可能已经把
        // 签名等参数做过唯一一次 URL 编码（%2B / %2F / %3D），再编码会把 % 变成 %25 而破坏请求。
        URI uri;
        try {
            uri = URI.create(request.url());
        } catch (IllegalArgumentException e) {
            log.warn(messages.getForLog("push.log.send.failed", type.id(), 0L, "invalid url"));
            return PushResult.failed(type, "invalid url");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(request.contentType());
        HttpEntity<byte[]> entity = new HttpEntity<>(request.body(), headers);
        RestTemplate restTemplate = request.useProxy() ? proxyRestTemplate : directRestTemplate;

        long startedAtNs = System.nanoTime();
        try {
            ResponseEntity<byte[]> response =
                    restTemplate.exchange(uri, HttpMethod.POST, entity, byte[].class);
            long elapsedMs = elapsedMs(startedAtNs);
            log.info(messages.getForLog("push.log.send.success", type.id(),
                    response.getStatusCode().value(), elapsedMs));
            return PushResult.ok(type);
        } catch (RestClientResponseException e) {
            // 已连通但返回非 2xx：附状态码 + 脱敏 / 截断后的响应正文摘要。
            long elapsedMs = elapsedMs(startedAtNs);
            String body = e.getResponseBodyAsString(StandardCharsets.UTF_8);
            String detail = truncate("HTTP " + e.getRawStatusCode()
                    + (body == null || body.isBlank() ? "" : ": " + redact(body, request.secrets())));
            log.warn(messages.getForLog("push.log.send.failed", type.id(), elapsedMs, detail));
            return PushResult.failed(type, detail);
        } catch (RestClientException e) {
            long elapsedMs = elapsedMs(startedAtNs);
            String detail = truncate(redact(safeMessage(e), request.secrets()));
            log.warn(messages.getForLog("push.log.send.failed", type.id(), elapsedMs, detail));
            return PushResult.failed(type, detail);
        }
    }

    private static long elapsedMs(long startedAtNs) {
        return (System.nanoTime() - startedAtNs) / 1_000_000;
    }

    /** 把每个密钥原文逐字替换为 {@code ***}（过短的不替换以免误伤普通文本）。 */
    private static String redact(String text, List<String> secrets) {
        if (text == null) {
            return "";
        }
        String s = text;
        for (String secret : secrets) {
            if (secret != null && secret.length() >= 4) {
                s = s.replace(secret, "***");
            }
        }
        return s;
    }

    private static String safeMessage(Throwable t) {
        if (t == null) {
            return "unknown";
        }
        String msg = t.getMessage();
        return msg == null || msg.isBlank() ? t.getClass().getSimpleName() : msg;
    }

    private static String truncate(String msg) {
        if (msg == null) {
            return "";
        }
        String oneLine = msg.replaceAll("\\s+", " ").trim();
        return oneLine.length() > DETAIL_MAX_LEN ? oneLine.substring(0, DETAIL_MAX_LEN) + "…" : oneLine;
    }
}

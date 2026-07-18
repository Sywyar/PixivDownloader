package top.sywyar.pixivdownload.push;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import top.sywyar.pixivdownload.i18n.MessageResolver;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
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
    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestTemplate directRestTemplate;
    private final RestTemplate proxyRestTemplate;
    private final MessageResolver messages;

    public PushHttpSender(@Qualifier("pushRestTemplate") RestTemplate directRestTemplate,
                          @Qualifier("pushProxyRestTemplate") RestTemplate proxyRestTemplate,
                          MessageResolver messages) {
        this.directRestTemplate = directRestTemplate;
        this.proxyRestTemplate = proxyRestTemplate;
        this.messages = messages;
    }

    /**
     * 发送一次调用。HTTP 2xx 且业务响应未报错记为成功；非 2xx / 业务失败 / 网络异常归为失败并返回脱敏详情。<b>不抛异常。</b>
     */
    public PushResult send(PushChannelType type, OutboundRequest request) {
        long startedAtNs = System.nanoTime();
        try {
            return sendInternal(type, request, startedAtNs);
        } catch (RuntimeException e) {
            PushResult result = PushResult.failed(type, PushResult.DETAIL_UNEXPECTED_ERROR);
            log.warn(PushPluginMessages.forLog(messages, "push.log.send.failed",
                    channelId(type), elapsedMs(startedAtNs),
                    PushPluginMessages.detailForLog(messages, result)));
            return result;
        }
    }

    private PushResult sendInternal(PushChannelType type, OutboundRequest request, long startedAtNs) {
        // 以 URI 直发，绝不让 RestTemplate 把 URL 当作模板再编码一次——通道（如钉钉「加签」）可能已经把
        // 签名等参数做过唯一一次 URL 编码（%2B / %2F / %3D），再编码会把 % 变成 %25 而破坏请求。
        URI uri;
        try {
            uri = URI.create(request.url());
        } catch (IllegalArgumentException e) {
            PushResult result = PushResult.failed(type, PushResult.DETAIL_INVALID_URL);
            log.warn(PushPluginMessages.forLog(messages, "push.log.send.failed",
                    channelId(type), elapsedMs(startedAtNs),
                    PushPluginMessages.detailForLog(messages, result)));
            return result;
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(request.contentType());
        HttpEntity<byte[]> entity = new HttpEntity<>(request.body(), headers);
        RestTemplate restTemplate = request.useProxy() ? proxyRestTemplate : directRestTemplate;

        try {
            ResponseEntity<byte[]> response =
                    restTemplate.exchange(uri, HttpMethod.POST, entity, byte[].class);
            long elapsedMs = elapsedMs(startedAtNs);
            BusinessStatus businessStatus =
                    inspectBusinessStatus(type, response.getBody(), request.secrets());
            if (!businessStatus.ok()) {
                String detail = businessStatus.detail();
                log.warn(PushPluginMessages.forLog(
                        messages, "push.log.send.failed", channelId(type), elapsedMs, detail));
                return PushResult.failed(type, detail);
            }
            log.info(PushPluginMessages.forLog(messages, "push.log.send.success", channelId(type),
                    response.getStatusCode().value(), elapsedMs));
            return PushResult.ok(type);
        } catch (RestClientResponseException e) {
            // 已连通但返回非 2xx：附状态码 + 脱敏 / 截断后的响应正文摘要。
            long elapsedMs = elapsedMs(startedAtNs);
            String body = e.getResponseBodyAsString(StandardCharsets.UTF_8);
            String detail = truncate("HTTP " + e.getRawStatusCode()
                    + (body == null || body.isBlank() ? "" : ": " + redact(body, request.secrets())));
            log.warn(PushPluginMessages.forLog(
                    messages, "push.log.send.failed", channelId(type), elapsedMs, detail));
            return PushResult.failed(type, detail);
        } catch (RestClientException e) {
            long elapsedMs = elapsedMs(startedAtNs);
            String detail = truncate(redact(safeMessage(e), request.secrets()));
            log.warn(PushPluginMessages.forLog(
                    messages, "push.log.send.failed", channelId(type), elapsedMs, detail));
            return PushResult.failed(type, detail);
        }
    }

    private String channelId(PushChannelType type) {
        return type == null
                ? PushPluginMessages.forLog(messages, "push.log.value.unknown")
                : type.id();
    }

    private static BusinessStatus inspectBusinessStatus(PushChannelType type, byte[] body, List<String> secrets) {
        if (type == PushChannelType.WEBHOOK || body == null || body.length == 0) {
            return BusinessStatus.success();
        }

        String text = new String(body, StandardCharsets.UTF_8);
        if (text.isBlank()) {
            return BusinessStatus.success();
        }

        JsonNode root;
        try {
            root = JSON.readTree(text);
        } catch (Exception ignored) {
            return BusinessStatus.success();
        }
        if (root == null || !root.isObject()) {
            return BusinessStatus.success();
        }

        return switch (type) {
            case DINGTALK, WECOM -> errcodeStatus(root, secrets);
            case TELEGRAM -> telegramStatus(root, secrets);
            case FEISHU -> feishuStatus(root, secrets);
            case PUSHPLUS -> codeStatus(root, "code", List.of("200"),
                    List.of("msg", "message"), secrets);
            case SERVERCHAN -> codeStatus(root, "code", List.of("0", "200"),
                    List.of("message", "msg"), secrets);
            case BARK -> codeStatus(root, "code", List.of("200"),
                    List.of("message", "msg"), secrets);
            case WEBHOOK -> BusinessStatus.success();
        };
    }

    private static BusinessStatus errcodeStatus(JsonNode root, List<String> secrets) {
        JsonNode errcode = field(root, "errcode");
        if (errcode == null || matches(errcode, "0")) {
            return BusinessStatus.success();
        }
        return BusinessStatus.failed(failureDetail("errcode", errcode,
                firstText(root, "errmsg", "message", "msg"), secrets));
    }

    private static BusinessStatus telegramStatus(JsonNode root, List<String> secrets) {
        JsonNode okNode = field(root, "ok");
        Boolean ok = bool(okNode);
        if (ok == null || ok) {
            return BusinessStatus.success();
        }

        String code = scalarText(field(root, "error_code"));
        String detail = "ok=false";
        if (code != null && !code.isBlank()) {
            detail += ", error_code=" + code;
        }
        String message = firstText(root, "description", "message");
        if (message != null && !message.isBlank()) {
            detail += ": " + message;
        }
        return BusinessStatus.failed(truncate(redact(detail, secrets)));
    }

    private static BusinessStatus feishuStatus(JsonNode root, List<String> secrets) {
        JsonNode statusCode = field(root, "StatusCode");
        if (statusCode != null) {
            return codeStatus(root, "StatusCode", List.of("0"),
                    List.of("StatusMessage", "msg", "message"), secrets);
        }
        return codeStatus(root, "code", List.of("0"),
                List.of("msg", "message", "StatusMessage"), secrets);
    }

    private static BusinessStatus codeStatus(JsonNode root, String codeField, List<String> successValues,
                                             List<String> messageFields, List<String> secrets) {
        JsonNode code = field(root, codeField);
        if (code == null || successValues.stream().anyMatch(value -> matches(code, value))) {
            return BusinessStatus.success();
        }
        return BusinessStatus.failed(failureDetail(codeField, code,
                firstText(root, messageFields), secrets));
    }

    private static JsonNode field(JsonNode root, String name) {
        if (root == null || name == null) {
            return null;
        }
        JsonNode exact = root.get(name);
        if (exact != null && !exact.isNull()) {
            return exact;
        }
        Iterator<String> names = root.fieldNames();
        while (names.hasNext()) {
            String candidate = names.next();
            if (name.equalsIgnoreCase(candidate)) {
                JsonNode node = root.get(candidate);
                return node == null || node.isNull() ? null : node;
            }
        }
        return null;
    }

    private static boolean matches(JsonNode node, String expected) {
        String value = scalarText(node);
        return value != null && value.trim().equals(expected);
    }

    private static Boolean bool(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isTextual()) {
            String value = node.asText().trim();
            if ("true".equalsIgnoreCase(value)) {
                return Boolean.TRUE;
            }
            if ("false".equalsIgnoreCase(value)) {
                return Boolean.FALSE;
            }
        }
        return null;
    }

    private static String firstText(JsonNode root, List<String> names) {
        return firstText(root, names.toArray(String[]::new));
    }

    private static String firstText(JsonNode root, String... names) {
        for (String name : names) {
            String value = scalarText(field(root, name));
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String failureDetail(String codeField, JsonNode code, String message, List<String> secrets) {
        String detail = codeField + "=" + scalarText(code);
        if (message != null && !message.isBlank()) {
            detail += ": " + message;
        }
        return truncate(redact(detail, secrets));
    }

    private static String scalarText(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual() || node.isNumber() || node.isBoolean()) {
            return node.asText();
        }
        return node.toString();
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

    private record BusinessStatus(boolean ok, String detail) {
        private static BusinessStatus success() {
            return new BusinessStatus(true, null);
        }

        private static BusinessStatus failed(String detail) {
            return new BusinessStatus(false, detail);
        }
    }
}

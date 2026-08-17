package top.sywyar.pixivdownload.plugin.api.http;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * 传输中立的出站 HTTP 响应。非成功状态仍按普通响应表示。
 *
 * @param statusCode 三位 HTTP 状态码
 * @param statusText 不含换行符的状态说明
 * @param headers 响应头；名称按大小写不敏感语义处理
 * @param body 响应体字节；构造和读取时均执行防御性复制
 */
public record OutboundHttpResponse(
        int statusCode,
        String statusText,
        Map<String, List<String>> headers,
        byte[] body
) {

    private static final Pattern HEADER_NAME_TOKEN =
            Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");

    /**
     * 校验状态与响应头，并对响应体执行防御性复制。
     *
     * @param statusCode 状态码
     * @param statusText 状态文本
     * @param headers 请求头
     * @param body 请求体
     */
    public OutboundHttpResponse {
        if (statusCode < 100 || statusCode > 999) {
            throw new IllegalArgumentException("statusCode must be a three-digit HTTP status");
        }
        statusText = normalizedStatusText(statusText);
        headers = immutableHeaders(headers);
        body = body == null ? new byte[0] : body.clone();
    }

    /**
     * 返回响应体的防御性副本。
     *
     * @return 响应体副本
     */
    @Override
    public byte[] body() {
        return body.clone();
    }

    @Override
    public String toString() {
        return "OutboundHttpResponse[statusCode=" + statusCode
                + ", statusText=<redacted>"
                + ", headerCount=" + headers.size()
                + ", bodyLength=" + body.length + "]";
    }

    static Map<String, List<String>> immutableHeaders(Map<String, List<String>> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        source.forEach((name, values) -> {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("header name must not be blank");
            }
            rejectLineBreak(name, "header name");
            if (!HEADER_NAME_TOKEN.matcher(name).matches()) {
                throw new IllegalArgumentException("header name must be a valid HTTP token");
            }
            if (values == null) {
                throw new IllegalArgumentException("header values must not be null");
            }
            List<String> valueCopy = List.copyOf(values);
            valueCopy.forEach(value -> rejectLineBreak(value, "header value"));
            List<String> mergedValues = new ArrayList<>(
                    copy.getOrDefault(name, List.of()));
            mergedValues.addAll(valueCopy);
            copy.put(name, List.copyOf(mergedValues));
        });
        return Collections.unmodifiableMap(copy);
    }

    static String normalizedStatusText(String value) {
        String normalized = value == null ? "" : value;
        rejectLineBreak(normalized, "statusText");
        return normalized;
    }

    private static void rejectLineBreak(String value, String field) {
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(field + " must not contain CR or LF");
        }
    }
}

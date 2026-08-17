package top.sywyar.pixivdownload.plugin.api.http;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 传输中立的出站 HTTP 请求。
 *
 * @param uri 不含用户信息的绝对 HTTP(S) URI
 * @param method HTTP 方法 token
 * @param headers 请求头；名称按大小写不敏感语义处理
 * @param body 请求体字节；构造和读取时均执行防御性复制
 */
public record OutboundHttpRequest(
        URI uri,
        String method,
        Map<String, List<String>> headers,
        byte[] body
) {

    private static final Pattern METHOD_TOKEN =
            Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");

    /**
     * 校验请求目标、方法与请求头，并对请求体执行防御性复制。
     *
     * @param uri URI
     * @param method HTTP 方法
     * @param headers 请求头
     * @param body 请求体
     */
    public OutboundHttpRequest {
        uri = validatedUri(uri);
        method = validatedMethod(method);
        headers = OutboundHttpResponse.immutableHeaders(headers);
        body = body == null ? new byte[0] : body.clone();
    }

    /**
     * 返回请求体的防御性副本。
     *
     * @return 请求体副本
     */
    @Override
    public byte[] body() {
        return body.clone();
    }

    @Override
    public String toString() {
        return "OutboundHttpRequest[uri=<redacted>, method=" + method
                + ", headerCount=" + headers.size()
                + ", bodyLength=" + body.length + "]";
    }

    private static URI validatedUri(URI value) {
        Objects.requireNonNull(value, "uri");
        String scheme = value.getScheme();
        if (!value.isAbsolute()
                || scheme == null
                || (!"http".equals(scheme.toLowerCase(Locale.ROOT))
                && !"https".equals(scheme.toLowerCase(Locale.ROOT)))
                || value.getRawAuthority() == null
                || value.getRawAuthority().isBlank()
                || value.getRawUserInfo() != null) {
            throw new IllegalArgumentException("uri must be an absolute HTTP(S) URI without user info");
        }
        return value;
    }

    private static String validatedMethod(String value) {
        Objects.requireNonNull(value, "method");
        if (!METHOD_TOKEN.matcher(value).matches()) {
            throw new IllegalArgumentException("method must be a valid HTTP token");
        }
        return value;
    }

}

package top.sywyar.pixivdownload.plugin.api.http;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Transport-neutral outbound HTTP request.
 */
public record OutboundHttpRequest(
        URI uri,
        String method,
        Map<String, List<String>> headers,
        byte[] body
) {

    private static final Pattern METHOD_TOKEN =
            Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");

    public OutboundHttpRequest {
        uri = validatedUri(uri);
        method = validatedMethod(method);
        headers = OutboundHttpResponse.immutableHeaders(headers);
        body = body == null ? new byte[0] : body.clone();
    }

    @Override
    public byte[] body() {
        return body.clone();
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

package top.sywyar.pixivdownload.plugin.api.http.websocket;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Transport-neutral outbound WebSocket handshake request.
 */
public record OutboundWebSocketRequest(
        URI uri,
        Map<String, List<String>> headers
) {

    private static final Pattern HEADER_NAME_TOKEN =
            Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");

    public OutboundWebSocketRequest {
        uri = validatedUri(uri);
        headers = immutableHeaders(headers);
    }

    @Override
    public String toString() {
        return "OutboundWebSocketRequest[uri=<redacted>, headerCount="
                + headers.size() + "]";
    }

    private static URI validatedUri(URI value) {
        Objects.requireNonNull(value, "uri");
        String scheme = value.getScheme();
        if (!value.isAbsolute()
                || scheme == null
                || (!"ws".equals(scheme.toLowerCase(Locale.ROOT))
                && !"wss".equals(scheme.toLowerCase(Locale.ROOT)))
                || value.getRawAuthority() == null
                || value.getRawAuthority().isBlank()
                || value.getRawUserInfo() != null
                || value.getRawFragment() != null) {
            throw new IllegalArgumentException(
                    "uri must be an absolute WS(S) URI with authority and without user info or fragment");
        }
        return value;
    }

    private static Map<String, List<String>> immutableHeaders(
            Map<String, List<String>> source
    ) {
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
            List<String> valueCopy = new ArrayList<>(values.size());
            for (String value : values) {
                if (value == null) {
                    throw new IllegalArgumentException("header value must not be null");
                }
                rejectLineBreak(value, "header value");
                valueCopy.add(value);
            }
            List<String> mergedValues = new ArrayList<>(
                    copy.getOrDefault(name, List.of()));
            mergedValues.addAll(valueCopy);
            copy.put(name, List.copyOf(mergedValues));
        });
        return Collections.unmodifiableMap(copy);
    }

    private static void rejectLineBreak(String value, String field) {
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(field + " must not contain CR or LF");
        }
    }
}

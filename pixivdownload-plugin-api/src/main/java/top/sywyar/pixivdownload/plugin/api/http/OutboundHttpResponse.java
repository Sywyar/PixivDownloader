package top.sywyar.pixivdownload.plugin.api.http;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Transport-neutral outbound HTTP response. Non-success statuses remain ordinary responses.
 */
public record OutboundHttpResponse(
        int statusCode,
        String statusText,
        Map<String, List<String>> headers,
        byte[] body
) {

    private static final Pattern HEADER_NAME_TOKEN =
            Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");

    public OutboundHttpResponse {
        if (statusCode < 100 || statusCode > 999) {
            throw new IllegalArgumentException("statusCode must be a three-digit HTTP status");
        }
        statusText = normalizedStatusText(statusText);
        headers = immutableHeaders(headers);
        body = body == null ? new byte[0] : body.clone();
    }

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

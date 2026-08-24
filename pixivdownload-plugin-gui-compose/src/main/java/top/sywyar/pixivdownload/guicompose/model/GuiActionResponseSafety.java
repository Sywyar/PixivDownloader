package top.sywyar.pixivdownload.guicompose.model;

import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;

import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigActionResultSource;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigActionResultSummary;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 配置动作响应的路径白名单、文本净化与有界摘要。
 */
final class GuiActionResponseSafety {
    static final int MAX_ACTION_TEXT_CODE_POINTS = 512;
    private static final int MAX_ACTION_SUMMARY_CODE_POINTS = 2_048;
    private static final int MAX_ACTION_SUMMARY_ITEMS = 20;
    private static final Pattern SAFE_JSON_SEGMENT = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Set<String> SENSITIVE_RESULT_MARKERS = Set.of(
            "password",
            "passwd",
            "passphrase",
            "secret",
            "token",
            "cookie",
            "session",
            "sessid",
            "bearer",
            "authorization",
            "credential",
            "privatekey",
            "apikey",
            "accesskey",
            "authkey",
            "signingkey",
            "encryptionkey",
            "decryptionkey",
            "clientsecret",
            "rawbody",
            "error",
            "exception",
            "stacktrace",
            "traceback",
            "html"
    );

    private GuiActionResponseSafety() {
    }

    static boolean safeJsonPath(
            String path,
            boolean allowBlank,
            boolean rejectSensitive
    ) {
        if (path == null || path.isBlank()) return allowBlank;
        String normalized = path.trim();
        if (normalized.length() > 256) return false;
        String[] parts = normalized.split("\\.", -1);
        if (parts.length == 0 || parts.length > 8) return false;
        for (String part : parts) {
            if (!SAFE_JSON_SEGMENT.matcher(part).matches()) return false;
            if (rejectSensitive) {
                String folded = part.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
                if (SENSITIVE_RESULT_MARKERS.stream().anyMatch(folded::contains)) return false;
            }
        }
        return true;
    }

    static String sanitizeActionText(String value) {
        return sanitizeActionText(value, MAX_ACTION_TEXT_CODE_POINTS);
    }

    private static String sanitizeActionText(String value, int maximumCodePoints) {
        if (value == null || value.isBlank()) return "";
        StringBuilder safe = new StringBuilder(Math.min(
                value.length(),
                maximumCodePoints
        ));
        boolean whitespace = false;
        int accepted = 0;
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isISOControl(codePoint) && !Character.isWhitespace(codePoint)) continue;
            if (Character.isWhitespace(codePoint)) {
                if (!safe.isEmpty() && !whitespace) {
                    safe.append(' ');
                    accepted++;
                }
                whitespace = true;
                continue;
            }
            whitespace = false;
            if (accepted >= maximumCodePoints) {
                safe.append('…');
                break;
            }
            if (codePoint == '<') safe.append('‹');
            else if (codePoint == '>') safe.append('›');
            else safe.appendCodePoint(codePoint);
            accepted++;
        }
        return safe.toString().trim();
    }

    static String responseDetail(DesktopUiHost.GuiResponse response) {
        if (!response.reachable()) return "unreachable";
        if (!response.rawBody().isBlank()) return response.status() + " " + response.rawBody();
        return Integer.toString(response.status());
    }

    record ActionResult(
            boolean reachable,
            boolean http2xx,
            int status,
            DesktopUiHost.GuiValue body,
            String summary
    ) {
        static ActionResult from(
                DesktopUiHost.GuiResponse response,
                GuiConfigActionResultSummary summarySpec
        ) {
            DesktopUiHost.GuiValue parsed = response.bodyLimitExceeded() ? null : response.body();
            return new ActionResult(
                    response.reachable(),
                    response.is2xx(),
                    response.status(),
                    parsed,
                    buildSummary(parsed, summarySpec)
            );
        }

        String value(GuiConfigActionResultSource source, String path) {
            return switch (source) {
                case REACHABLE -> Boolean.toString(reachable);
                case HTTP_2XX -> Boolean.toString(http2xx);
                case HTTP_STATUS -> Integer.toString(status);
                case HTTP_STATUS_TEXT -> status <= 0 ? "" : "HTTP " + status;
                case JSON -> jsonText(path);
                case SUMMARY -> summary;
            };
        }

        private String jsonText(String path) {
            if (!safeJsonPath(path, false, true)) return "";
            DesktopUiHost.GuiValue node = nodeAt(body, path);
            if (node == null || node.isMissingNode() || node.isNull() || !node.isValueNode())
                return "";
            return node.isBoolean() ? Boolean.toString(node.asBoolean()) : sanitizeActionText(node.asText(
                    ""));
        }

        private static String buildSummary(
                DesktopUiHost.GuiValue body,
                GuiConfigActionResultSummary spec
        ) {
            if (body == null || spec == null || !safeJsonPath(
                    spec.arrayPath(),
                    false,
                    true
            ) || !safeJsonPath(
                    spec.labelPath(),
                    false,
                    true
            ) || !safeJsonPath(spec.statusPath(), true, true) || !safeJsonPath(
                    spec.detailPath(),
                    true,
                    true
            )) {
                return "";
            }
            DesktopUiHost.GuiValue array = nodeAt(body, spec.arrayPath());
            if (array == null || !array.isArray() || array.isEmpty()) return "";
            StringBuilder summary = new StringBuilder();
            int count = 0;
            for (DesktopUiHost.GuiValue item : array) {
                if (count >= MAX_ACTION_SUMMARY_ITEMS) break;
                String status = textAt(item, spec.statusPath());
                if (!spec.statusPath().isBlank() && status.equals(spec.successStatus())) continue;
                String label = textAt(item, spec.labelPath());
                String detail = textAt(item, spec.detailPath());
                if (label.isBlank() && status.isBlank() && detail.isBlank()) continue;
                if (!summary.isEmpty()) summary.append("; ");
                summary.append(label.isBlank() ? "-" : label);
                if (!spec.statusPath().isBlank()) {
                    summary.append(": ").append(status);
                    if (!detail.isBlank()) summary.append(" (").append(detail).append(')');
                } else if (!detail.isBlank()) {
                    summary.append(": ").append(detail);
                }
                count++;
                if (summary.codePointCount(
                        0,
                        summary.length()
                ) >= MAX_ACTION_SUMMARY_CODE_POINTS)
                    break;
            }
            return sanitizeActionText(summary.toString(), MAX_ACTION_SUMMARY_CODE_POINTS);
        }

        private static String textAt(DesktopUiHost.GuiValue value, String path) {
            if (!safeJsonPath(path, true, true)) return "";
            DesktopUiHost.GuiValue found = nodeAt(value, path);
            return found == null || found.isMissingNode() || found.isNull() || !found.isValueNode() ? "" : sanitizeActionText(
                    found.asText(""));
        }

        private static DesktopUiHost.GuiValue nodeAt(
                DesktopUiHost.GuiValue root,
                String path
        ) {
            if (root == null) return null;
            DesktopUiHost.GuiValue current = root;
            if (path == null || path.isBlank()) return current;
            for (String part : path.split("\\.")) {
                current = current.path(part);
                if (current.isMissingNode() || current.isNull()) break;
            }
            return current;
        }
    }
}

package top.sywyar.pixivdownload.gui.config;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Host-owned safety policy for values projected from plugin GUI action responses.
 */
public final class GuiConfigActionResultSafety {

    public static final int MAX_DISPLAY_TEXT_CODE_POINTS = 512;
    public static final int MAX_SUMMARY_CODE_POINTS = 2_048;
    public static final int MAX_SUMMARY_ITEMS = 20;

    private static final int MAX_PATH_LENGTH = 256;
    private static final int MAX_PATH_SEGMENTS = 8;
    private static final Pattern PATH_SEGMENT = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Set<String> SENSITIVE_MARKERS = Set.of(
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

    private GuiConfigActionResultSafety() {
    }

    /**
     * Accepts a bounded dot-separated JSON path that cannot address a credential-like or raw-error key.
     */
    public static boolean isSafeJsonPath(String path, boolean allowBlank) {
        if (path == null || path.isBlank()) {
            return allowBlank;
        }
        String normalizedPath = path.trim();
        if (normalizedPath.length() > MAX_PATH_LENGTH) {
            return false;
        }
        String[] parts = normalizedPath.split("\\.", -1);
        if (parts.length == 0 || parts.length > MAX_PATH_SEGMENTS) {
            return false;
        }
        for (String part : parts) {
            if (!PATH_SEGMENT.matcher(part).matches() || isSensitiveSegment(part)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Converts an untrusted scalar to bounded plain text. Angle brackets are neutralized so Swing cannot treat a
     * plugin response as an HTML label, and control characters cannot alter the surrounding notice.
     */
    public static String sanitizeDisplayText(String value) {
        return sanitizeDisplayText(value, MAX_DISPLAY_TEXT_CODE_POINTS);
    }

    public static String sanitizeSummary(String value) {
        return sanitizeDisplayText(value, MAX_SUMMARY_CODE_POINTS);
    }

    private static String sanitizeDisplayText(String value, int maxCodePoints) {
        if (value == null || value.isBlank()) {
            return "";
        }
        StringBuilder safe = new StringBuilder(Math.min(value.length(), maxCodePoints));
        boolean previousWhitespace = false;
        int acceptedCodePoints = 0;
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isISOControl(codePoint) && !Character.isWhitespace(codePoint)) {
                continue;
            }
            if (Character.isWhitespace(codePoint)) {
                if (safe.length() > 0 && !previousWhitespace) {
                    safe.append(' ');
                    acceptedCodePoints++;
                }
                previousWhitespace = true;
                continue;
            }
            previousWhitespace = false;
            if (acceptedCodePoints >= maxCodePoints) {
                safe.append('…');
                break;
            }
            if (codePoint == '<') {
                safe.append('‹');
            } else if (codePoint == '>') {
                safe.append('›');
            } else {
                safe.appendCodePoint(codePoint);
            }
            acceptedCodePoints++;
        }
        return safe.toString().trim();
    }

    private static boolean isSensitiveSegment(String segment) {
        String folded = segment.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return SENSITIVE_MARKERS.stream().anyMatch(folded::contains);
    }
}

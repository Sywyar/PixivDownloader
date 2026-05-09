package top.sywyar.pixivdownload.common;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PixivDescriptionHtml {

    private static final URI PIXIV_BASE_URI = URI.create("https://www.pixiv.net/");
    private static final Pattern ANCHOR_HREF_PATTERN = Pattern.compile(
            "(?i)(<a\\b[^>]*?\\bhref\\s*=\\s*)(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))");

    private PixivDescriptionHtml() {
    }

    public static String normalizeLinks(String html) {
        if (html == null || html.isEmpty()) {
            return html;
        }
        Matcher matcher = ANCHOR_HREF_PATTERN.matcher(html);
        StringBuffer out = new StringBuffer(html.length());
        while (matcher.find()) {
            String href = extractHref(matcher);
            String normalized = resolvePixivHref(href);
            String replacement = matcher.group(1) + "\"" + escapeDoubleQuotedAttribute(normalized) + "\"";
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    public static String resolvePixivHref(String href) {
        if (href == null) {
            return "";
        }
        String value = href.trim();
        if (value.isEmpty() || value.startsWith("#")) {
            return value;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.startsWith("javascript:")
                || lower.startsWith("data:")
                || lower.startsWith("vbscript:")) {
            return "#";
        }
        if (lower.startsWith("mailto:") || lower.startsWith("tel:")) {
            return value;
        }
        try {
            URI uri = URI.create(value);
            String jumpTarget = extractPixivJumpTarget(uri);
            if (jumpTarget != null) {
                return resolvePixivHref(jumpTarget);
            }
            if (value.startsWith("//")) {
                return "https:" + value;
            }
            if (uri.isAbsolute()) {
                return value;
            }
            URI resolved = PIXIV_BASE_URI.resolve(uri);
            jumpTarget = extractPixivJumpTarget(resolved);
            if (jumpTarget != null) {
                return resolvePixivHref(jumpTarget);
            }
            return resolved.toString();
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    private static String extractPixivJumpTarget(URI uri) {
        if (!isPixivJumpUri(uri)) {
            return null;
        }
        String rawQuery = uri.getRawQuery();
        if (rawQuery == null || rawQuery.isBlank()) {
            return null;
        }
        return URLDecoder.decode(rawQuery, StandardCharsets.UTF_8);
    }

    private static boolean isPixivJumpUri(URI uri) {
        String path = uri.getPath();
        if (!"/jump.php".equals(path)) {
            return false;
        }
        String host = uri.getHost();
        return host == null || "www.pixiv.net".equalsIgnoreCase(host) || "pixiv.net".equalsIgnoreCase(host);
    }

    private static String extractHref(Matcher matcher) {
        if (matcher.group(3) != null) {
            return matcher.group(3);
        }
        if (matcher.group(4) != null) {
            return matcher.group(4);
        }
        return matcher.group(5) == null ? "" : matcher.group(5);
    }

    private static String escapeDoubleQuotedAttribute(String value) {
        return value.replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}

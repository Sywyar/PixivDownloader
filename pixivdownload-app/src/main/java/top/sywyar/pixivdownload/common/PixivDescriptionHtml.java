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
    private static final Pattern TAG_PATTERN = Pattern.compile(
            "(?i)<\\s*(/?)\\s*([a-z][a-z0-9:-]*)([^>]*)>");
    private static final Pattern HREF_ATTR_PATTERN = Pattern.compile(
            "(?i)(?<=\\s|^)href\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s\"'>`]+))");

    private PixivDescriptionHtml() {
    }

    public static String normalizeLinks(String html) {
        if (html == null || html.isEmpty()) {
            return html;
        }
        return sanitizeHtml(normalizeAnchorHrefs(html));
    }

    private static String normalizeAnchorHrefs(String html) {
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

    private static String sanitizeHtml(String html) {
        Matcher matcher = TAG_PATTERN.matcher(html);
        StringBuilder out = new StringBuilder(html.length());
        int last = 0;
        while (matcher.find()) {
            out.append(escapeText(html.substring(last, matcher.start())));
            out.append(sanitizeTag(matcher.group(1), matcher.group(2), matcher.group(3)));
            last = matcher.end();
        }
        out.append(escapeText(html.substring(last)));
        return out.toString();
    }

    private static String sanitizeTag(String slash, String name, String attrs) {
        String tag = name == null ? "" : name.toLowerCase(Locale.ROOT);
        boolean closing = slash != null && !slash.isBlank();
        if ("br".equals(tag)) {
            return closing ? "" : "<br>";
        }
        if (!"a".equals(tag)) {
            return "";
        }
        if (closing) {
            return "</a>";
        }
        String href = extractHref(attrs);
        String normalized = resolvePixivHref(href);
        return "<a href=\"" + escapeDoubleQuotedAttribute(normalized)
                + "\" target=\"_blank\" rel=\"noopener noreferrer\">";
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

    private static String extractHref(String attrs) {
        if (attrs == null || attrs.isBlank()) {
            return "";
        }
        Matcher matcher = HREF_ATTR_PATTERN.matcher(attrs);
        if (!matcher.find()) {
            return "";
        }
        if (matcher.group(2) != null) {
            return matcher.group(2);
        }
        if (matcher.group(3) != null) {
            return matcher.group(3);
        }
        return matcher.group(4) == null ? "" : matcher.group(4);
    }

    private static String escapeText(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private static String escapeDoubleQuotedAttribute(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}

package top.sywyar.pixivdownload.core.metadata;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 仅用于核对历史持久化结果；输出不得作为修复后的 HTML 使用。
 */
final class LegacyDescriptionHtml {

    private static final URI PIXIV_BASE_URI = URI.create("https://www.pixiv.net/");
    private static final Pattern ANCHOR_START_PATTERN = Pattern.compile("(?i)<a\\b");
    private static final Pattern ANCHOR_HREF_PATTERN = Pattern.compile(
            "(?i)(\\bhref\\s*=\\s*)(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))");
    private static final Pattern TAG_START_PATTERN = Pattern.compile(
            "(?i)<\\s*+(/?)\\s*+([a-z][a-z0-9:-]*+)");
    private static final Pattern HREF_ATTR_PATTERN = Pattern.compile(
            "(?i)(?<=\\s|^)href\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s\"'>`]+))");

    private LegacyDescriptionHtml() {
    }

    /**
     * 规范化链接并清理不受支持的 HTML。
     *
     * @param html 原始作品描述 HTML
     * @return 清理后的 HTML；输入为 {@code null} 或空字符串时原样返回
     */
    public static String normalizeLinks(String html) {
        if (html == null || html.isEmpty()) {
            return html;
        }
        return sanitizeHtml(normalizeAnchorHrefs(html));
    }

    private static String normalizeAnchorHrefs(String html) {
        Matcher anchor = ANCHOR_START_PATTERN.matcher(html);
        Matcher href = ANCHOR_HREF_PATTERN.matcher(html);
        StringBuilder out = new StringBuilder(html.length());
        int last = 0;
        int cursor = 0;
        int hrefStart = -1;
        int tagEnd = html.indexOf('>');
        while (anchor.find(cursor)) {
            if (hrefStart < anchor.end()) {
                if (!href.find(anchor.end())) break;
                hrefStart = href.start();
            }
            if (tagEnd >= 0 && tagEnd < anchor.end()) tagEnd = html.indexOf('>', anchor.end());
            if (tagEnd >= 0 && tagEnd < hrefStart) {
                cursor = tagEnd + 1;
                continue;
            }
            // 旧匹配允许未闭合标签，也允许 href 的引号值跨过 >，必须保留。
            out.append(html, last, href.start(2));
            out.append('"').append(escapeDoubleQuotedAttribute(resolvePixivHref(extractHref(href)))).append('"');
            last = cursor = href.end();
        }
        out.append(html, last, html.length());
        return out.toString();
    }

    private static String sanitizeHtml(String html) {
        Matcher matcher = TAG_START_PATTERN.matcher(html);
        StringBuilder out = new StringBuilder(html.length());
        int last = 0;
        int cursor = 0;
        while (matcher.find(cursor)) {
            int end = html.indexOf('>', matcher.end());
            if (end < 0) break;
            out.append(escapeText(html.substring(last, matcher.start())));
            out.append(sanitizeTag(matcher.group(1), matcher.group(2), html.substring(matcher.end(), end)));
            last = cursor = end + 1;
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

    /**
     * 将 Pixiv 相对链接和跳转链接解析为可安全展示的目标地址。
     *
     * @param href 原始链接
     * @return 解析后的链接；危险协议返回 {@code #}
     */
    public static String resolvePixivHref(String href) {
        if (href == null) {
            return "";
        }
        String value = href.trim();
        for (int depth = 0; depth < 32; depth++) {
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
                    value = jumpTarget.trim();
                    continue;
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
                    value = jumpTarget.trim();
                    continue;
                }
                return resolved.toString();
            } catch (IllegalArgumentException e) {
                return value;
            }
        }
        throw new IllegalArgumentException("LEGACY_DESCRIPTION_JUMP_DEPTH");
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
        return escapeText(value).replace("\"", "&quot;");
    }
}

package top.sywyar.pixivdownload.core.pixiv;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 规范化并清理 Pixiv 作品描述 HTML，仅保留安全的链接和换行标签。
 */
public final class PixivDescriptionHtml {

    private static final URI PIXIV_BASE_URI = URI.create("https://www.pixiv.net/");
    private static final Pattern TAG_START_PATTERN = Pattern.compile(
            "(?i)<\\s*+(/?)\\s*+([a-z][a-z0-9:-]*+)");
    private static final Pattern ATTRIBUTE_PATTERN = Pattern.compile(
            "([^\\s=/\"'<>`]+)(?:\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s\"'<>`]+)))?");

    private PixivDescriptionHtml() {
    }

    /**
     * 规范化链接并清理不受支持的 HTML；重复调用保持相同的安全 HTML 片段。
     *
     * @param html 原始作品描述 HTML
     * @return 清理后的 HTML；输入为 {@code null} 或空字符串时原样返回
     */
    public static String normalizeLinks(String html) {
        if (html == null || html.isEmpty()) {
            return html;
        }
        return sanitizeHtml(html);
    }

    private static String sanitizeHtml(String html) {
        if (html.indexOf('<') < 0 || html.indexOf('>') < 0) {
            return escapeText(HtmlCharacterReferences.decode(html, false));
        }
        int[] ends = tagEnds(html);
        Matcher matcher = TAG_START_PATTERN.matcher(html);
        StringBuilder out = new StringBuilder(html.length());
        int last = 0;
        int cursor = 0;
        while (matcher.find(cursor)) {
            int end = ends[matcher.end()];
            cursor = end < 0 ? matcher.end() : end + 1;
            if (end < 0) continue;
            out.append(escapeText(HtmlCharacterReferences.decode(html.substring(last, matcher.start()), false)));
            out.append(sanitizeTag(matcher.group(1), matcher.group(2), html.substring(matcher.end(), end)));
            last = cursor;
        }
        out.append(escapeText(HtmlCharacterReferences.decode(html.substring(last), false)));
        return out.toString();
    }

    private static int[] tagEnds(String html) {
        // 从右向左复用引号后的终点，避免未闭合标签从每个起点重新扫描尾部。
        int[] ends = new int[html.length() + 1];
        ends[html.length()] = -1;
        int single = -1;
        int doubleQuote = -1;
        for (int i = html.length() - 1; i >= 0; i--) {
            char ch = html.charAt(i);
            if (ch == '\'') {
                ends[i] = single < 0 ? -1 : ends[single + 1];
                single = i;
            } else if (ch == '"') {
                ends[i] = doubleQuote < 0 ? -1 : ends[doubleQuote + 1];
                doubleQuote = i;
            } else {
                ends[i] = ch == '>' ? i : ends[i + 1];
            }
        }
        return ends;
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
        String href = HtmlCharacterReferences.decode(extractHref(attrs), true);
        String normalized = resolvePixivHref(href);
        return "<a href=\"" + escapeDoubleQuotedAttribute(normalized)
                + "\" target=\"_blank\" rel=\"noopener noreferrer\">";
    }

    /**
     * 将 Pixiv 相对链接和跳转链接解析为可安全展示的目标地址。
     *
     * @param href 原始链接
     * @return 解析后的链接；危险、无效或超过 32 层跳转的链接返回 {@code #}
     */
    public static String resolvePixivHref(String href) {
        if (href == null) {
            return "";
        }
        String value = href.replace("\t", "").replace("\n", "").replace("\r", "").trim();
        for (int depth = 0; depth < 32; depth++) {
            if (value.isEmpty()) {
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
                value = encodeUrlComponents(value);
                int fragment = value.indexOf('#');
                int fragmentHash = fragment < 0 ? -1 : value.indexOf('#', fragment + 1);
                // URI 校验需要引用片段内的 #，但最终链接必须保留它与 %23 的区别。
                String uriValue = fragmentHash < 0 ? value
                        : value.substring(0, fragmentHash) + value.substring(fragmentHash).replace("#", "%23");
                URI uri = URI.create(uriValue);
                if (value.startsWith("#")) return value;
                String jumpTarget = extractPixivJumpTarget(uri);
                if (jumpTarget != null) {
                    value = jumpTarget.replace("\t", "").replace("\n", "").replace("\r", "").trim();
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
                    value = jumpTarget.replace("\t", "").replace("\n", "").replace("\r", "").trim();
                    continue;
                }
                String target = resolved.toString();
                return fragmentHash < 0 ? target
                        : target.substring(0, target.indexOf('#')) + value.substring(fragment);
            } catch (IllegalArgumentException e) {
                return "#";
            }
        }
        return "#";
    }

    private static String encodeUrlComponents(String value) {
        int fragment = value.indexOf('#');
        if (fragment < 0) fragment = value.length();
        int query = value.indexOf('?');
        if (query < 0 || query > fragment) query = fragment;
        int colon = value.indexOf(':');
        int slash = value.indexOf('/');
        boolean hasScheme = colon >= 0 && colon < query && (slash < 0 || colon < slash);
        int path = hasScheme ? colon + 1 : 0;
        if (value.startsWith("//", path)) {
            int authorityEnd = value.indexOf('/', path + 2);
            path = authorityEnd < 0 ? query : Math.min(authorityEnd, query);
        } else if (hasScheme && !value.startsWith("/", path)) {
            // 不把 opaque URI 的前缀误当成路径，也不通过编码修正协议或主机。
            path = query;
        }
        String scheme = hasScheme ? value.substring(0, colon).toLowerCase(Locale.ROOT) : "https";
        boolean specialQuery = switch (scheme) {
            case "http", "https", "ftp", "file", "ws", "wss" -> true;
            default -> false;
        };
        StringBuilder encoded = null;
        for (int i = path; i < value.length(); i++) {
            char ch = value.charAt(i);
            int codePoint = value.codePointAt(i);
            if (Character.isSurrogate(ch) && Character.charCount(codePoint) == 1) {
                throw new IllegalArgumentException("INVALID_URL_SURROGATE");
            }
            boolean inPath = i < query;
            boolean inQuery = i > query && i < fragment;
            boolean inFragment = i > fragment;
            // 百分号与分隔符保留；只引用可打印字符，控制字符和非法转义仍由 URI 拒绝。
            boolean quote = switch (ch) {
                case ' ', '"', '<', '>', '`', '^', '{', '}', '|' -> inPath || inQuery || inFragment;
                case '[', ']' -> inPath;
                case '\\' -> inQuery || inFragment;
                case '\'' -> inQuery && specialQuery;
                default -> ch > 127 && !Character.isISOControl(ch) && (inPath || inQuery || inFragment);
            };
            if (quote) {
                if (encoded == null) encoded = new StringBuilder(value.length()).append(value, 0, i);
                if (ch < 128) {
                    appendPercentByte(encoded, ch);
                } else {
                    for (byte octet : new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8)) {
                        appendPercentByte(encoded, octet & 255);
                    }
                    i += Character.charCount(codePoint) - 1;
                }
            } else if (encoded != null) {
                encoded.append(ch);
            }
        }
        return encoded == null ? value : encoded.toString();
    }

    private static void appendPercentByte(StringBuilder out, int octet) {
        out.append('%').append("0123456789ABCDEF".charAt(octet >>> 4))
                .append("0123456789ABCDEF".charAt(octet & 15));
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

    private static String extractHref(String attrs) {
        if (attrs == null || attrs.isBlank()) {
            return "";
        }
        Matcher matcher = ATTRIBUTE_PATTERN.matcher(attrs);
        while (matcher.find()) {
            if (!"href".equalsIgnoreCase(matcher.group(1))) continue;
            if (matcher.group(3) != null) return matcher.group(3);
            if (matcher.group(4) != null) return matcher.group(4);
            return matcher.group(5) == null ? "" : matcher.group(5);
        }
        return "";
    }

    private static String escapeText(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private static String escapeDoubleQuotedAttribute(String value) {
        return escapeText(value).replace("\"", "&quot;");
    }

    /** HTML 字符引用按文本或属性上下文只解码一层。 */
    private static final class HtmlCharacterReferences {
        private static final Map<String, String> NAMED = loadNames();
        private static final int MAX_NAME_LENGTH = NAMED.keySet().stream().mapToInt(String::length).max().orElseThrow();
        private static final int[] C1_REPLACEMENTS = {
                0x20ac, 0x81, 0x201a, 0x192, 0x201e, 0x2026, 0x2020, 0x2021,
                0x2c6, 0x2030, 0x160, 0x2039, 0x152, 0x8d, 0x17d, 0x8f,
                0x90, 0x2018, 0x2019, 0x201c, 0x201d, 0x2022, 0x2013, 0x2014,
                0x2dc, 0x2122, 0x161, 0x203a, 0x153, 0x9d, 0x17e, 0x178
        };

        private HtmlCharacterReferences() { }

        static String decode(String input, boolean attribute) {
            int first = input.indexOf('&');
            if (first < 0) return input;
            StringBuilder out = new StringBuilder(input.length());
            out.append(input, 0, first);
            for (int i = first; i < input.length();) {
                char ch = input.charAt(i);
                if (ch == '&' && i + 1 < input.length()) {
                    int start = i + 1;
                    if (input.charAt(start) == '#') {
                        int end = start + 1;
                        boolean hex = end < input.length() && (input.charAt(end) == 'x' || input.charAt(end) == 'X');
                        if (hex) end++;
                        int digits = end;
                        int number = 0;
                        while (end < input.length()) {
                            char digit = input.charAt(end);
                            int value = digit <= 127 ? Character.digit(digit, hex ? 16 : 10) : -1;
                            if (value < 0) break;
                            number = Math.min(0x110000, number * (hex ? 16 : 10) + value);
                            end++;
                        }
                        if (end > digits) {
                            if (end < input.length() && input.charAt(end) == ';') end++;
                            if (number == 0 || number > 0x10ffff || (number >= 0xd800 && number <= 0xdfff)) number = 0xfffd;
                            if (number >= 0x80 && number <= 0x9f) number = C1_REPLACEMENTS[number - 0x80];
                            out.appendCodePoint(number);
                            i = end;
                            continue;
                        }
                    } else {
                        int end = start;
                        int limit = Math.min(input.length(), start + MAX_NAME_LENGTH);
                        while (end < limit && isAsciiAlphanumeric(input.charAt(end))) end++;
                        if (end < limit && input.charAt(end) == ';') end++;
                        for (; end > start; end--) {
                            String name = input.substring(start, end);
                            String value = NAMED.get(name);
                            if (value == null) continue;
                            if (attribute && !name.endsWith(";") && end < input.length()
                                    && (input.charAt(end) == '=' || isAsciiAlphanumeric(input.charAt(end)))) break;
                            out.append(value);
                            i = end;
                            break;
                        }
                        if (i >= start) continue;
                    }
                }
                out.append(ch);
                i++;
            }
            return out.toString();
        }

        private static boolean isAsciiAlphanumeric(char ch) {
            return ch >= 'a' && ch <= 'z' || ch >= 'A' && ch <= 'Z' || ch >= '0' && ch <= '9';
        }

        private static Map<String, String> loadNames() {
            Properties entries = new Properties();
            try (var reader = new InputStreamReader(Objects.requireNonNull(
                    HtmlCharacterReferences.class.getResourceAsStream("html-entities.properties")), StandardCharsets.UTF_8)) {
                entries.load(reader);
            } catch (IOException e) {
                throw new ExceptionInInitializerError(e);
            }
            Map<String, String> names = new HashMap<>();
            entries.forEach((name, codes) -> {
                StringBuilder value = new StringBuilder();
                for (String code : codes.toString().split(",")) value.appendCodePoint(Integer.parseInt(code, 16));
                names.put(name.toString(), value.toString());
            });
            return Map.copyOf(names);
        }
    }
}

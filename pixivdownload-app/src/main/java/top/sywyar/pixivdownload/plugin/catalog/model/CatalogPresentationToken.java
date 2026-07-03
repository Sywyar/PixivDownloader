package top.sywyar.pixivdownload.plugin.catalog.model;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 受控展示 token 净化器：清单里的图标 / 颜色只能是<b>受控 token</b>（如 Font Awesome 字形 id、设计系统颜色名），
 * <b>绝不允许任意 HTML / SVG / CSS / 远程脚本进入页面</b>。本类把清单声明的图标 / 颜色规整为安全 token——只接受
 * 小写字母开头、由小写字母 / 数字 / 连字符组成、长度受限的串；任何越界字符（{@code < > " ' / : ( ) ; # 空白} 等）
 * 或未知 / 空值一律回退到稳定的默认 token，使页面渲染既稳定又无注入面。
 *
 * <p>纯 JDK 工具类，<b>不入 {@code plugin-api}</b>。
 */
public final class CatalogPresentationToken {

    /** 图标缺省 / 非法 / 未知时的回退 token（与 {@code PixivFeaturePlugin.DEFAULT_ICON_KEY} 对齐）。 */
    public static final String DEFAULT_ICON = "puzzle";

    /** 颜色缺省 / 非法 / 未知时的回退 token（与 {@code PixivFeaturePlugin.DEFAULT_COLOR_TOKEN} 对齐）。 */
    public static final String DEFAULT_COLOR = "neutral";

    /** 安全 token：小写字母开头，其后为小写字母 / 数字 / 连字符，长度 1..40。 */
    private static final Pattern SAFE_TOKEN = Pattern.compile("[a-z][a-z0-9-]{0,39}");

    private static final Set<String> ICON_TOKENS = Set.of(
            "puzzle", "puzzle-piece", "store", "language", "bolt", "rotate", "bell", "bell-ring", "cloud",
            "shield", "shield-halved", "palette", "screwdriver-wrench", "grip", "hashtag", "paper-plane",
            "film", "book", "duplicate", "clone", "file-signature", "heart", "download", "upload", "users",
            "globe", "gear", "image", "images", "gallery", "chart", "chart-line", "layer-group",
            "cloud-arrow-down", "cloud-arrow-up", "cube", "wand-magic-sparkles", "sparkles", "robot", "music",
            "microphone", "audio-lines", "mail", "envelope", "lock", "key", "tag", "tags", "folder", "box",
            "plug", "code", "scroll", "filter", "wrench", "gauge", "magnifying-glass", "star", "fire",
            "bookmark", "comments", "database", "wifi", "compass", "feather", "pen", "brush", "eye", "clock");

    private static final Set<String> COLOR_TOKENS = Set.of(
            "neutral", "gray", "pixiv", "blue", "teal", "amber", "purple", "orange", "red", "green");

    private CatalogPresentationToken() {
    }

    /** 净化图标 token，非法 / 空 / 未知 → {@link #DEFAULT_ICON}。 */
    public static String sanitizeIcon(String raw) {
        return sanitize(raw, ICON_TOKENS, DEFAULT_ICON);
    }

    /** 净化颜色 token，非法 / 空 / 未知 → {@link #DEFAULT_COLOR}。 */
    public static String sanitizeColor(String raw) {
        return sanitize(raw, COLOR_TOKENS, DEFAULT_COLOR);
    }

    /** 给定原始 token 是否为合法的受控 token（不区分大小写前先 trim+lowercase）。 */
    public static boolean isSafe(String raw) {
        return raw != null && SAFE_TOKEN.matcher(raw.trim().toLowerCase(Locale.ROOT)).matches();
    }

    private static String sanitize(String raw, Set<String> knownTokens, String fallback) {
        if (raw == null) {
            return fallback;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return SAFE_TOKEN.matcher(normalized).matches() && knownTokens.contains(normalized) ? normalized : fallback;
    }
}

package top.sywyar.pixivdownload.plugin.catalog.model;

import java.util.Locale;
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

    /** 图标缺省 / 非法时的回退字形（与设计的通用插件字形一致）。 */
    public static final String DEFAULT_ICON = "puzzle-piece";

    /** 颜色缺省 / 非法时的回退色名。 */
    public static final String DEFAULT_COLOR = "gray";

    /** 安全 token：小写字母开头，其后为小写字母 / 数字 / 连字符，长度 1..40。 */
    private static final Pattern SAFE_TOKEN = Pattern.compile("[a-z][a-z0-9-]{0,39}");

    private CatalogPresentationToken() {
    }

    /** 净化图标 token，非法 / 空 → {@link #DEFAULT_ICON}。 */
    public static String sanitizeIcon(String raw) {
        return sanitize(raw, DEFAULT_ICON);
    }

    /** 净化颜色 token，非法 / 空 → {@link #DEFAULT_COLOR}。 */
    public static String sanitizeColor(String raw) {
        return sanitize(raw, DEFAULT_COLOR);
    }

    /** 给定原始 token 是否为合法的受控 token（不区分大小写前先 trim+lowercase）。 */
    public static boolean isSafe(String raw) {
        return raw != null && SAFE_TOKEN.matcher(raw.trim().toLowerCase(Locale.ROOT)).matches();
    }

    private static String sanitize(String raw, String fallback) {
        if (raw == null) {
            return fallback;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return SAFE_TOKEN.matcher(normalized).matches() ? normalized : fallback;
    }
}

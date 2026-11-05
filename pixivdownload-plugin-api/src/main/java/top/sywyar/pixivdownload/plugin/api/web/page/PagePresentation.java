package top.sywyar.pixivdownload.plugin.api.web.page;

import java.util.Locale;
import java.util.Set;

/** 页面投影与编辑器共用的纯值呈现边界，不接受可执行 HTML、脚本或任意资源 URL。 */
public final class PagePresentation {
    private PagePresentation() { }
    private static final Set<String> PROPERTIES = Set.of("display", "flex-direction", "flex-wrap", "justify-content", "align-items", "gap",
            "padding", "padding-top", "padding-bottom", "padding-left", "padding-right", "margin", "margin-top", "margin-bottom", "margin-left", "margin-right",
            "width", "max-width", "min-width", "min-height", "font-size", "font-weight", "line-height", "text-align", "color",
            "background-color", "border", "border-color", "border-width", "border-style", "border-radius", "box-shadow", "grid-template-columns", "flex", "order");

    /** {@return 允许编辑的 CSS 属性，按名称排序} */
    public static java.util.List<String> properties() { return PROPERTIES.stream().sorted().toList(); }

    /**
     * 校验单条呈现声明，非法值抛出 PAGE_STYLE_INVALID。
     * @param name CSS 属性
     * @param value CSS 值
     */
    public static void style(String name, String value) {
        if (!PROPERTIES.contains(name) || value == null || value.length() > 128
                || !value.matches("[a-zA-Z0-9 #.,()%_-]+") || value.toLowerCase(Locale.ROOT).contains("url")
                || value.toLowerCase(Locale.ROOT).contains("expression") || value.strip().equalsIgnoreCase("none") || value.contains("-999"))
            throw new IllegalArgumentException("PAGE_STYLE_INVALID");
    }

    /**
     * 校验用户导航链接，非法值抛出 PAGE_LINK_INVALID。
     * @param value 同源绝对路径或 HTTP(S) 地址
     * @return 通过校验的原地址
     */
    public static String link(String value) {
        if (value == null || value.length() > 2048 || value.isBlank() || value.contains("\\")
                || value.chars().anyMatch(c -> c <= 32 || c == 127)) throw new IllegalArgumentException("PAGE_LINK_INVALID");
        try {
            var uri = new java.net.URI(value);
            boolean relative = value.startsWith("/") && !value.startsWith("//") && uri.getRawAuthority() == null;
            boolean external = ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    && uri.getHost() != null && uri.getRawUserInfo() == null;
            if (!relative && !external) throw new IllegalArgumentException("PAGE_LINK_INVALID");
            return value;
        } catch (java.net.URISyntaxException failure) { throw new IllegalArgumentException("PAGE_LINK_INVALID"); }
    }
}

package top.sywyar.pixivdownload.plugin.catalog.model;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * 受控外链净化器：清单里的项目主页 / 文档等链接只允许 {@code http} / {@code https}，<b>绝不允许</b>
 * {@code javascript:} / {@code data:} / {@code file:} 等可在页面执行脚本或读取本地资源的 scheme。页面把这类链接渲染成
 * {@code <a href>} 前必须经此净化，杜绝点击即执行的注入面。与 {@link CatalogPresentationToken} 同属「受控展示」工具。
 *
 * <p>纯 JDK 工具类，<b>不入 {@code plugin-api}</b>。仅作展示净化，<b>不</b>做联网 / SSRF 校验（那是拉取层的职责，
 * 且这类链接只用于页面跳转、不参与后端下载）。
 */
public final class CatalogLink {

    private CatalogLink() {
    }

    /**
     * 净化一个展示用外链：trim 后必须能解析出 {@code http} / {@code https} scheme 且带主机，否则返回 {@code null}
     * （由调用方按「无链接」处理、不渲染）。原值不做其它改写（前向兼容、不丢查询串）。
     */
    public static String sanitizeHttpUrl(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            return null;
        }
        String scheme = uri.getScheme();
        if (scheme == null) {
            return null;
        }
        scheme = scheme.toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return null;
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            return null;
        }
        return trimmed;
    }

    /** 给定原始链接是否为安全的可展示 http/https 外链。 */
    public static boolean isHttpUrl(String raw) {
        return sanitizeHttpUrl(raw) != null;
    }
}

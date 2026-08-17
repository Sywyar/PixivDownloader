package top.sywyar.pixivdownload.plugin.api.web;

/**
 * 自有 HTTP 方法枚举。contribution 保持框架中立，
 * 不引用任何 Web 框架的 HTTP method 枚举。
 */
public enum HttpMethod {
    /**
     * 表示 {@code GET} 状态。
     */
    GET,
    /**
     * 表示 {@code HEAD} 状态。
     */
    HEAD,
    /**
     * 表示 {@code POST} 状态。
     */
    POST,
    /**
     * 表示 {@code PUT} 状态。
     */
    PUT,
    /**
     * 表示 {@code PATCH} 状态。
     */
    PATCH,
    /**
     * 表示 {@code DELETE} 状态。
     */
    DELETE,
    /**
     * 表示 {@code OPTIONS}。
     */
    OPTIONS
}

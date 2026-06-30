package top.sywyar.pixivdownload.plugin.api.web;

/**
 * 自有 HTTP 方法枚举。contribution 保持框架中立，
 * 不引用任何 Web 框架的 HTTP method 枚举。
 */
public enum HttpMethod {
    GET,
    HEAD,
    POST,
    PUT,
    PATCH,
    DELETE,
    OPTIONS
}

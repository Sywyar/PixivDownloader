package top.sywyar.pixivdownload.plugin.api;

/**
 * 自有 HTTP 方法枚举。contribution 保持框架中立，
 * 不使用 {@code org.springframework.http.HttpMethod}。
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

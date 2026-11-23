package top.sywyar.pixivdownload.core.web;

/**
 * 客户端可定位并修正的 HTTP 参数错误。
 *
 * @param code 可由程序识别的错误码
 * @param error 本地化的错误说明
 * @param in 参数位置，如 query、path、header、part 或 body
 * @param field 请求中的参数名，请求体字段使用 JSON Pointer
 * @param reason 可由程序识别的校验失败原因
 */
public record HttpParameterError(String code, String error, String in, String field, String reason) {}

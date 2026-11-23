package top.sywyar.pixivdownload.core.web;

/** 校验失败的 HTTP 参数，包含调用方可用于定位问题的位置、字段和原因。 */
public final class HttpParameterException extends RuntimeException {
    /** 参数在 HTTP 请求中的位置。 */
    private final String in;
    /** 参数名或请求体内的 JSON Pointer。 */
    private final String field;
    /** 稳定的校验失败原因码。 */
    private final String reason;

    /**
     * 记录失败参数的位置及原因。
     *
     * @param in 参数位置，如 query、path、header、part 或 body
     * @param field 请求中的参数名，请求体字段使用 JSON Pointer
     * @param reason 稳定的校验失败原因码
     */
    public HttpParameterException(String in, String field, String reason) {
        super("Invalid " + in + " field " + field + " (" + reason + ")");
        this.in = in;
        this.field = field;
        this.reason = reason;
    }

    /** {@return 参数在请求中的位置} */
    public String in() { return in; }

    /** {@return 请求中的参数名或请求体字段的 JSON Pointer} */
    public String field() { return field; }

    /** {@return 校验失败的原因码} */
    public String reason() { return reason; }
}

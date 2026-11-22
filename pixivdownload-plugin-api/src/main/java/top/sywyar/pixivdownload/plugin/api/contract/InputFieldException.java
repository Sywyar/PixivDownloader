package top.sywyar.pixivdownload.plugin.api.contract;

/** 请求字段校验失败，仅携带 JSON Pointer 与稳定原因码，不携带提交值。 */
public final class InputFieldException extends IllegalArgumentException {
    /** 指向无效字段的 JSON Pointer。 */
    private final String field;
    /** 字段校验的机器原因码。 */
    private final String reason;

    /**
     * 创建可安全传递给调用方的字段诊断。
     * @param field 公开请求字段的 JSON Pointer
     * @param reason 稳定的校验关键字或业务原因码
     */
    public InputFieldException(String field, String reason) {
        super(reason);
        this.field = java.util.Objects.requireNonNull(field);
        this.reason = java.util.Objects.requireNonNull(reason);
    }

    /** {@return 指向请求字段的 JSON Pointer} */
    public String field() { return field; }
    /** {@return 与语言无关的原因码} */
    public String reason() { return reason; }
}

package top.sywyar.pixivdownload.sdk.community.format;

import java.util.Map;

/** 数据工具的稳定领域错误；界面按 code 本地化，不保存验证器的自然语言消息。 */
public final class ContractException extends IllegalArgumentException {
    private final String code;
    private final String field;
    private final Map<String, String> details;

    public ContractException(String code, String field, Map<String, String> details) {
        super(code + ": " + field);
        this.code = code;
        this.field = field;
        this.details = Map.copyOf(details);
    }

    public ContractException(String code, String field) { this(code, field, Map.of()); }
    public static ContractException invalid(String reason, String field) {
        return new ContractException("SCHEMA_INVALID", field, Map.of("reason", reason));
    }
    public String code() { return code; }
    public String field() { return field; }
    public Map<String, String> details() { return details; }
}

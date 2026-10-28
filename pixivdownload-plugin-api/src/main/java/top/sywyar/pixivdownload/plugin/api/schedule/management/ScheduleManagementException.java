package top.sywyar.pixivdownload.plugin.api.schedule.management;

import java.util.Objects;

/** 计划管理的受控拒绝；仅携带安全文案参数，不保留凭据或插件异常 cause。 */
public final class ScheduleManagementException extends RuntimeException {
    public enum Code { INVALID, CONFLICT }

    private final Code code;
    private final String messageCode;
    private final String defaultMessage;
    private final Object[] messageArgs;

    public ScheduleManagementException(Code code, String messageCode, String defaultMessage, Object... messageArgs) {
        super(defaultMessage == null ? messageCode : defaultMessage);
        this.code = Objects.requireNonNull(code, "code");
        this.messageCode = Objects.requireNonNull(messageCode, "messageCode");
        this.defaultMessage = defaultMessage;
        this.messageArgs = messageArgs == null ? new Object[0] : messageArgs.clone();
    }

    public static ScheduleManagementException badRequest(String messageCode, String defaultMessage, Object... args) {
        return new ScheduleManagementException(Code.INVALID, messageCode, defaultMessage, args);
    }

    public Code code() { return code; }
    public String messageCode() { return messageCode; }
    public String defaultMessage() { return defaultMessage; }
    public Object[] messageArgs() { return messageArgs.clone(); }
}

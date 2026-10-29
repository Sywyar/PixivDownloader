package top.sywyar.pixivdownload.plugin.api.schedule.management;

import java.util.Objects;

/** 计划管理的受控拒绝；仅携带安全文案参数，不保留凭据或插件异常 cause。 */
public final class ScheduleManagementException extends RuntimeException {
    /** 管理入口可据此区分参数拒绝与当前状态冲突。 */
    public enum Code {
        /** 请求参数或操作内容不合法。 */
        INVALID,
        /** 任务状态、publication 或并发版本已不满足操作条件。 */
        CONFLICT
    }

    /** @serial 与界面语言无关的失败分类。 */
    private final Code code;
    /** @serial 由宿主消息 owner 解析的本地化键。 */
    private final String messageCode;
    /** @serial 缺失翻译时使用的安全说明。 */
    private final String defaultMessage;
    /** @serial 不含凭据的本地化参数。 */
    private final Object[] messageArgs;

    /**
     * 创建受控拒绝；参数必须由调用方提供为非敏感纯值。
     * @param code 拒绝类别，不能为空
     * @param messageCode 本地化消息键，不能为空
     * @param defaultMessage 缺少翻译时使用的安全文案，可为空
     * @param messageArgs 非敏感文案参数；复制数组，空数组引用按无参数处理
     */
    public ScheduleManagementException(Code code, String messageCode, String defaultMessage, Object... messageArgs) {
        super(defaultMessage == null ? messageCode : defaultMessage);
        this.code = Objects.requireNonNull(code, "code");
        this.messageCode = Objects.requireNonNull(messageCode, "messageCode");
        this.defaultMessage = defaultMessage;
        this.messageArgs = messageArgs == null ? new Object[0] : messageArgs.clone();
    }

    /**
     * 创建参数无效的拒绝，不改变任何任务状态。
     * @param messageCode 本地化消息键
     * @param defaultMessage 缺少翻译时的安全文案
     * @param args 非敏感文案参数
     * @return 类别为 {@link Code#INVALID} 的异常
     */
    public static ScheduleManagementException badRequest(String messageCode, String defaultMessage, Object... args) {
        return new ScheduleManagementException(Code.INVALID, messageCode, defaultMessage, args);
    }

    /** @return 稳定的拒绝类别 */
    public Code code() { return code; }
    /** @return 本地化消息键 */
    public String messageCode() { return messageCode; }
    /** @return 缺少翻译时的安全文案，可为空 */
    public String defaultMessage() { return defaultMessage; }
    /** @return 非敏感文案参数的数组副本 */
    public Object[] messageArgs() { return messageArgs.clone(); }
}

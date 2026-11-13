package top.sywyar.pixivdownload.core.work.directory;

/** 目录模板的受控失败；不在异常消息中携带配置路径或作品标题。 */
public final class DirectoryTemplateException extends IllegalArgumentException {
    /** 稳定原因码。 */
    private final String reason;
    /** 相关变量名；不涉及变量时为空。 */
    private final String variable;
    /** 相关配置项；不涉及配置校验时为空。 */
    private final String configurationKey;

    /**
     * 创建不含配置路径或作品标题的校验异常。
     * @param reason 稳定原因码
     * @param variable 相关变量名；无变量时可为空
     */
    public DirectoryTemplateException(String reason, String variable) {
        super("directory template rejected: " + reason
                + (variable == null || variable.isEmpty() ? "" : " (variable: {" + variable + "})"));
        this.reason = reason;
        this.variable = variable == null ? "" : variable;
        this.configurationKey = "";
    }

    DirectoryTemplateException(String configurationKey, DirectoryTemplateException cause) {
        super("Invalid directory template setting " + configurationKey + ": " + cause.getMessage(), cause);
        this.reason = cause.reason;
        this.variable = cause.variable;
        this.configurationKey = configurationKey;
    }

    /** {@return 可用于本地化和 API 投影的稳定原因} */
    public String reason() { return reason; }

    /** {@return 引起失败的变量名称；非变量错误为空} */
    public String variable() { return variable; }

    /** {@return 失败的配置项；非配置校验时为空} */
    public String configurationKey() { return configurationKey; }
}

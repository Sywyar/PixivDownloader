package top.sywyar.pixivdownload.core.work.directory;

/** 目录模板的受控失败；不在异常消息中携带配置路径或作品标题。 */
public final class DirectoryTemplateException extends IllegalArgumentException {
    private final String reason;
    private final String variable;

    public DirectoryTemplateException(String reason, String variable) {
        super("directory template rejected: " + reason);
        this.reason = reason;
        this.variable = variable == null ? "" : variable;
    }

    /** @return 可用于本地化和 API 投影的稳定原因 */
    public String reason() { return reason; }

    /** @return 引起失败的变量名称；非变量错误为空 */
    public String variable() { return variable; }
}

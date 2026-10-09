package top.sywyar.pixivdownload.plugin.api.download.submission;

/** 下载提交的受控失败，不携带插件异常、路径或凭据。 */
public final class DownloadTaskException extends RuntimeException {
    /** 机器可判定的失败原因。 */
    public enum Code {
        /** 类型不存在或精确 publication 已撤回。 */ UNAVAILABLE,
        /** 幂等键已用于不同命令。 */ CONFLICT,
        /** 有界任务存储已满。 */ CAPACITY_EXCEEDED,
        /** 类型校验或准入规则拒绝。 */ REJECTED,
        /** 当前调用者不是管理员。 */ FORBIDDEN
    }
    /** 可序列化的稳定失败原因，不保存原始插件异常。 */
    private final Code code;
    /**
     * 使用稳定失败码创建下载任务异常。
     *
     * @param code 失败原因
     */
    public DownloadTaskException(Code code) {
        super(code.name());
        this.code = java.util.Objects.requireNonNull(code, "code");
    }
    /**
     * {@return 失败原因}
     */
    public Code code() { return code; }
}

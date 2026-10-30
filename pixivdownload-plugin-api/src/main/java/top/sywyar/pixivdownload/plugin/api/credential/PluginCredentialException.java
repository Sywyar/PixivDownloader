package top.sywyar.pixivdownload.plugin.api.credential;

/** 只携带机器码，不包含凭据值、文件路径、插件异常或 cause。 */
public final class PluginCredentialException extends RuntimeException {
    /** 不含凭据内容的失败分类。 */
    public enum Reason {
        /** 所属资源 generation 已停止接纳。 */
        CLOSED,
        /** 键未由当前 owner 声明。 */
        UNDECLARED,
        /** 声明不符合 owner 或唯一性约束。 */
        INVALID_DEFINITION,
        /** 更新含非法键或值。 */
        INVALID_UPDATE,
        /** 更新超出数量或字节预算。 */
        CAPACITY,
        /** 加密存储操作失败。 */
        STORAGE_FAILED
    }
    /** 稳定错误分类，不保留原异常。 */
    private final Reason reason;

    /** @param reason 失败分类 */
    public PluginCredentialException(Reason reason) {
        super("PLUGIN_CREDENTIAL_" + reason.name());
        this.reason = reason;
    }

    /** @return 稳定失败分类 */
    public Reason reason() { return reason; }
}

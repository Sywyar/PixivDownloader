package top.sywyar.pixivdownload.plugin.runtime.install.transaction;

import java.util.Objects;

/**
 * 删除调用方持有的事务回执。installer 必须在重抛任何失败前写入已复验的磁盘结论，避免上层把
 * 「REMOVED 后的终态失败」误当成可恢复旧运行时的删除失败。
 */
public final class PluginRemovalAttempt {

    public enum Outcome {
        /** 尚未发布删除事务，磁盘安装态未被本次调用改变。 */
        UNCHANGED,
        /** 已发布事务，但 installer 已确认旧 artifact 完整恢复。 */
        ROLLED_BACK,
        /** 已确认 artifact 与事务清单均按删除终态收敛。 */
        REMOVED,
        /** 已发布事务但无法确认安全终态；恢复 gate 已封闭。 */
        UNSAFE
    }

    private final String packageId;
    private volatile Outcome outcome = Outcome.UNCHANGED;

    public PluginRemovalAttempt(String packageId) {
        this.packageId = Objects.requireNonNull(packageId, "packageId");
    }

    public String packageId() {
        return packageId;
    }

    public Outcome outcome() {
        return outcome;
    }

    /** 仅由安装器在同一目录锁内、复验磁盘状态后写入。 */
    public void confirm(Outcome confirmed) {
        outcome = Objects.requireNonNull(confirmed, "confirmed");
    }
}

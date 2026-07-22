package top.sywyar.pixivdownload.plugin.runtime.install.transaction;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * 新包已放入规范路径、旧包仍保存在事务 backup 中的提交回执。
 *
 * <p>除冻结的事务绑定外，回执还保存 installer 已从权威清单确认的持久化终态。这样调用方即使收到
 * {@link VirtualMachineError} / {@link ThreadDeath}，也能区分「仍应回滚 NEW_PLACED」与「ACTIVATED 之后
 * 必须保留新代」，不能再根据方法是否抛出异常猜测磁盘事实。</p>
 */
public final class CommittedPluginTransaction {

    public enum DurableState {
        NEW_PLACED,
        ACTIVATED,
        COMMITTED,
        RETIRED,
        ROLLED_BACK;

        public boolean keepsCommittedArtifact() {
            return this == ACTIVATED || this == COMMITTED || this == RETIRED;
        }
    }

    private final PreparedPluginTransaction prepared;
    private final List<BackupArtifact> backups;
    private volatile DurableState durableState = DurableState.NEW_PLACED;
    private volatile boolean recoveryBlocked;

    public CommittedPluginTransaction(PreparedPluginTransaction prepared, List<BackupArtifact> backups) {
        this.prepared = Objects.requireNonNull(prepared, "prepared");
        this.backups = List.copyOf(Objects.requireNonNull(backups, "backups"));
    }

    public PreparedPluginTransaction prepared() {
        return prepared;
    }

    public List<BackupArtifact> backups() {
        return backups;
    }

    public DurableState durableState() {
        return durableState;
    }

    public boolean recoveryBlocked() {
        return recoveryBlocked;
    }

    /** 仅由安装器在同一目录锁内、复验权威磁盘状态后推进。 */
    public synchronized void confirmDurableState(DurableState confirmed) {
        Objects.requireNonNull(confirmed, "confirmed");
        if (confirmed == durableState) {
            return;
        }
        boolean allowed = switch (durableState) {
            case NEW_PLACED -> confirmed == DurableState.ACTIVATED
                    || confirmed == DurableState.COMMITTED
                    || confirmed == DurableState.ROLLED_BACK;
            case ACTIVATED -> confirmed == DurableState.COMMITTED;
            case COMMITTED -> confirmed == DurableState.RETIRED;
            case RETIRED, ROLLED_BACK -> false;
        };
        if (!allowed) {
            throw new IllegalStateException("plugin transaction durable state cannot move backwards from "
                    + durableState + " to " + confirmed);
        }
        durableState = confirmed;
    }

    /** 仅由安装器在同一目录锁内确认恢复面不安全后标记。 */
    public void markRecoveryBlocked() {
        recoveryBlocked = true;
    }

    public record BackupArtifact(Path origin, Path backup) {
    }
}

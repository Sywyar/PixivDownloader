package top.sywyar.pixivdownload.plugin.runtime.install.transaction;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginInstallResult;

/** 已完成安全校验的 staged artifact，以及 installer 对提交尝试确认的最终处置。 */
public final class PreparedPluginTransaction {

    public enum CommitState {
        PREPARED,
        COMMITTED,
        ROLLED_BACK,
        DISCARDED,
        UNSAFE
    }

    private final String transactionId;
    private final PluginInstallResult result;
    private final Path transactionDirectory;
    private final Path stagedArtifact;
    private final Path target;
    private final List<Path> expectedCurrentArtifacts;
    private volatile CommitState commitState = CommitState.PREPARED;

    public PreparedPluginTransaction(
            String transactionId,
            PluginInstallResult result,
            Path transactionDirectory,
            Path stagedArtifact,
            Path target,
            List<Path> expectedCurrentArtifacts) {
        this.transactionId = transactionId;
        this.result = result;
        this.transactionDirectory = transactionDirectory;
        this.stagedArtifact = stagedArtifact;
        this.target = target;
        this.expectedCurrentArtifacts = expectedCurrentArtifacts != null
                ? List.copyOf(expectedCurrentArtifacts) : List.of();
    }

    public String transactionId() {
        return transactionId;
    }

    public PluginInstallResult result() {
        return result;
    }

    public Path transactionDirectory() {
        return transactionDirectory;
    }

    public Path stagedArtifact() {
        return stagedArtifact;
    }

    public Path target() {
        return target;
    }

    public List<Path> expectedCurrentArtifacts() {
        return expectedCurrentArtifacts;
    }

    public CommitState commitState() {
        return commitState;
    }

    /** 仅由 installer 在同一目录锁内、确认事务处置后写入。 */
    public synchronized void confirmCommitState(CommitState confirmed) {
        Objects.requireNonNull(confirmed, "confirmed");
        if (confirmed == commitState) {
            return;
        }
        boolean allowed = commitState == CommitState.PREPARED
                || commitState == CommitState.COMMITTED && confirmed == CommitState.ROLLED_BACK;
        if (!allowed) {
            throw new IllegalStateException("prepared transaction outcome is already " + commitState);
        }
        commitState = confirmed;
    }

    public boolean readyToCommit() {
        return result != null && result.accepted() && stagedArtifact != null && target != null;
    }
}

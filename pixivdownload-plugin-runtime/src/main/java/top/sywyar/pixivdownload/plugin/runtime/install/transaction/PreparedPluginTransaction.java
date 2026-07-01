package top.sywyar.pixivdownload.plugin.runtime.install.transaction;

import java.nio.file.Path;
import java.util.List;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginInstallResult;

/** 已完成安全校验、尚未触碰旧包的 staged artifact。 */
public record PreparedPluginTransaction(
        String transactionId,
        PluginInstallResult result,
        Path transactionDirectory,
        Path stagedArtifact,
        Path target,
        List<Path> expectedCurrentArtifacts) {

    public PreparedPluginTransaction {
        expectedCurrentArtifacts = expectedCurrentArtifacts != null
                ? List.copyOf(expectedCurrentArtifacts) : List.of();
    }

    public boolean readyToCommit() {
        return result != null && result.accepted() && stagedArtifact != null && target != null;
    }
}

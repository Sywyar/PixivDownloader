package top.sywyar.pixivdownload.plugin.runtime.install;

import java.nio.file.Path;
import java.util.List;

/** 新包已放入规范路径、旧包仍保存在事务 backup 中的可回滚提交。 */
public record CommittedPluginTransaction(
        PreparedPluginTransaction prepared,
        List<BackupArtifact> backups) {

    public CommittedPluginTransaction {
        backups = List.copyOf(backups);
    }

    public record BackupArtifact(Path origin, Path backup) {
    }
}

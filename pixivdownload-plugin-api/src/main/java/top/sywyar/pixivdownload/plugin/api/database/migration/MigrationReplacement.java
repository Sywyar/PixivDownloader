package top.sywyar.pixivdownload.plugin.api.database.migration;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 用户确认的目标替换意图；由目标提供者核验旧内容并保留一致备份。
 * @param revision 只读预检取得的不含秘密的目标变化凭据
 * @param backup 宿主为此库分配的全新耐久备份位置
 */
public record MigrationReplacement(String revision, Path backup) {
    public MigrationReplacement {
        if (revision == null || revision.isBlank()) throw new IllegalArgumentException("MIGRATION_TARGET_REVISION_INVALID");
        backup = Objects.requireNonNull(backup).toAbsolutePath().normalize();
    }
}

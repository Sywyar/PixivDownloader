package top.sywyar.pixivdownload.plugin.runtime.install.recovery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PluginTransactionRecoveryReport.FailureKind;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("非权威插件事务工作区清理")
class PluginRecoveryWorkspaceCleanerTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("删除预算内的普通隐藏工作区")
    void deletesPlainWorkspacesWithinBudget() throws Exception {
        Path plugins = Files.createDirectory(tempDir.resolve("plugins"));
        Path workspaceRoot = plugins.resolve(".preparing");
        Path nested = Files.createDirectories(workspaceRoot.resolve("tx-1/nested"));
        Files.writeString(nested.resolve("artifact.jar"), "artifact", StandardCharsets.UTF_8);

        new PluginRecoveryWorkspaceCleaner(4, 16).cleanup(plugins, ".preparing");

        assertThat(workspaceRoot).doesNotExist();
    }

    @Test
    @DisplayName("累计条目超出预算时保留工作区")
    void retainsWorkspaceWhenEntryBudgetIsExceeded() throws IOException {
        Path plugins = Files.createDirectory(tempDir.resolve("plugins-budget"));
        Path workspaceRoot = plugins.resolve(".preparing");
        Path transaction = Files.createDirectories(workspaceRoot.resolve("tx-1"));
        Files.writeString(transaction.resolve("artifact.jar"), "artifact", StandardCharsets.UTF_8);
        PluginRecoveryWorkspaceCleaner cleaner = new PluginRecoveryWorkspaceCleaner(2, 1);

        assertThatThrownBy(() -> cleaner.cleanup(plugins, ".preparing"))
                .isInstanceOf(PluginRecoveryValidationException.class)
                .satisfies(error -> assertThat(((PluginRecoveryValidationException) error).kind())
                        .isEqualTo(FailureKind.INVALID_MANIFEST));
        assertThat(workspaceRoot).exists();
    }

    @Test
    @DisplayName("拒绝清理插件根目录之外的路径")
    void rejectsWorkspaceOutsidePluginsRoot() throws IOException {
        Path plugins = Files.createDirectory(tempDir.resolve("plugins-escape"));
        Path outside = Files.createDirectory(tempDir.resolve("outside"));
        PluginRecoveryWorkspaceCleaner cleaner = new PluginRecoveryWorkspaceCleaner(2, 8);

        assertThatThrownBy(() -> cleaner.cleanup(plugins, "../outside"))
                .isInstanceOf(PluginRecoveryValidationException.class)
                .satisfies(error -> assertThat(((PluginRecoveryValidationException) error).kind())
                        .isEqualTo(FailureKind.UNSAFE_PATH));
        assertThat(outside).exists();
    }
}

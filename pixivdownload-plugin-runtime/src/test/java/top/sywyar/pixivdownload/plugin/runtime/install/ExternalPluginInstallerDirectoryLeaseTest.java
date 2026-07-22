package top.sywyar.pixivdownload.plugin.runtime.install;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PluginRecoveryGateState;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("插件安装目录晚到恢复")
class ExternalPluginInstallerDirectoryLeaseTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    @DisplayName("首次恢复时目录缺失但随后出现事务时任一读取入口先补恢复并封闭")
    void lateCreatedRootIsRecoveredBeforeRead() throws Exception {
        Path plugins = temporaryDirectory.resolve("plugins");
        try (ExternalPluginInstaller installer = new ExternalPluginInstaller(plugins)) {
            assertThat(installer.recoverPendingTransactions().safeToScan()).isTrue();
            assertThat(installer.recoveryGateSnapshot().state()).isEqualTo(PluginRecoveryGateState.SAFE);

            Path transaction = Files.createDirectories(plugins.resolve(".staging/unfinished"));
            Files.writeString(transaction.resolve("orphan.bin"), "orphan", StandardCharsets.US_ASCII);

            assertThatThrownBy(installer::listInstalled)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("recovery is unsafe");
            assertThat(installer.recoveryGateSnapshot().state()).isEqualTo(PluginRecoveryGateState.BLOCKED);
            assertThat(Files.exists(transaction)).isTrue();
        }
    }

    @Test
    @DisplayName("目录仍缺失时只读清点返回空且不创建插件根")
    void absentRootReadRemainsSideEffectFree() {
        Path plugins = temporaryDirectory.resolve("plugins");
        try (ExternalPluginInstaller installer = new ExternalPluginInstaller(plugins)) {
            assertThat(installer.recoverPendingTransactions().safeToScan()).isTrue();

            assertThat(installer.listInstalled()).isEmpty();
            assertThat(Files.exists(plugins)).isFalse();
        }
    }

    @Test
    @DisplayName("目录仍缺失时运行时准入不创建生产安装根")
    void absentRootRuntimeScanRemainsSideEffectFree() {
        Path plugins = temporaryDirectory.resolve("plugins");
        try (ExternalPluginInstaller installer = new ExternalPluginInstaller(plugins)) {
            assertThat(installer.recoverPendingTransactions().safeToScan()).isTrue();

            installer.prepareRuntimeScan();

            assertThat(installer.recoveryGateSnapshot().state()).isEqualTo(PluginRecoveryGateState.SAFE);
            assertThat(Files.exists(plugins)).isFalse();
        }
    }
}

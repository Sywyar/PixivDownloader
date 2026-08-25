package top.sywyar.pixivdownload.plugin.runtime.install;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.PluginProvenanceStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import top.sywyar.pixivdownload.plugin.runtime.install.model.InstalledPlugin;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginInstallOutcome;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginInstallResult;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageOrigin;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.CommittedPluginTransaction;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PluginRemovalAttempt;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PluginRecoveryGateState;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PluginTransactionRecoveryReport;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PreparedPluginTransaction;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageFixtures;

@DisplayName("外置插件事务：旧制品与来源记录")
class ExternalPluginTransactionProvenanceTest extends ExternalPluginTransactionTestSupport {

    @Test
    @DisplayName("旧版非规范文件名升级失败后按原名恢复")
    void legacyArtifactNameIsRestoredVerbatimAfterRollback() throws IOException {
        Path plugins = temp.resolve("plugins-legacy-name-rollback");
        ExternalPluginInstaller installer = newInstaller(plugins);
        installFully(installer, packageFile("legacy-name-v1.zip", "1.0.0"));
        Path canonical = plugins.resolve("demo-1.0.0.zip");
        Path legacy = plugins.resolve("my-plugin.zip");
        Files.move(canonical, legacy);
        Files.move(sidecar(plugins, canonical), sidecar(plugins, legacy));

        PreparedPluginTransaction prepared = installer.prepareTransaction(
                packageFile("legacy-name-v2.zip", "2.0.0"),
                false, PluginPackageOrigin.localUpload());
        CommittedPluginTransaction committed = installer.commitTransaction(prepared);

        assertThat(installer.rollbackTransaction(committed)).isTrue();
        assertThat(legacy).exists();
        assertThat(sidecar(plugins, legacy)).exists();
        assertThat(canonical).doesNotExist();
        assertThat(prepared.target()).doesNotExist();
    }

    @Test
    @DisplayName("旧版非规范文件名可按包内身份安全删除")
    void legacyArtifactNameCanBeRemoved() throws IOException {
        Path plugins = temp.resolve("plugins-legacy-name-remove");
        ExternalPluginInstaller installer = newInstaller(plugins);
        installFully(installer, packageFile("legacy-name-remove.zip", "1.0.0"));
        Path canonical = plugins.resolve("demo-1.0.0.zip");
        Path legacy = plugins.resolve("copied-plugin.jar");
        Files.move(canonical, legacy);
        Files.move(sidecar(plugins, canonical), sidecar(plugins, legacy));

        assertThat(installer.removeInstalled("demo")).isTrue();
        assertThat(legacy).doesNotExist();
        assertThat(sidecar(plugins, legacy)).doesNotExist();
        assertThat(plugins.resolve(".staging")).doesNotExist();
    }

    @Test
    @DisplayName("提交窗口前旧 artifact 摘要变化时拒绝过期事务")
    void stalePreparedTransactionIsRejectedBeforeCommit() throws IOException {
        Path plugins = temp.resolve("plugins-stale");
        ExternalPluginInstaller installer = newInstaller(plugins);
        installFully(installer, packageFile("stale-old.zip", "1.0.0"));
        PreparedPluginTransaction prepared = installer.prepareTransaction(
                packageFile("stale-next.zip", "2.0.0"), false, PluginPackageOrigin.localUpload());
        Path current = plugins.resolve("demo-1.0.0.zip");
        Files.writeString(current, "tampered", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> installer.verifyCurrentArtifacts(prepared))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed to verify prepared plugin transaction");
        assertThat(current).exists();
        assertThat(prepared.target()).doesNotExist();
        assertThat(prepared.transactionDirectory()).exists();
    }

    @Test
    @DisplayName("删除已安装插件通过隔离事务完成并清理暂存目录")
    void removalUsesRecoverableTransaction() {
        Path plugins = temp.resolve("plugins-remove");
        ExternalPluginInstaller installer = newInstaller(plugins);
        installFully(installer, packageFile("remove.zip", "1.0.0"));

        assertThat(installer.removeInstalled("demo")).isTrue();
        assertThat(installer.listInstalled()).isEmpty();
        assertThat(sidecar(plugins, plugins.resolve("demo-1.0.0.zip"))).doesNotExist();
        assertThat(plugins.resolve(".staging")).doesNotExist();
    }

    @Test
    @DisplayName("损坏的旧 provenance 可被新包替换且回滚时原样恢复")
    void malformedOldProvenanceCanBeReplacedAndRestoredOpaqueOnRollback() throws IOException {
        Path plugins = temp.resolve("plugins-repair-malformed-provenance");
        ExternalPluginInstaller installer = newInstaller(plugins);
        installFully(installer, packageFile("repair-old.zip", "1.0.0"));
        Path oldArtifact = plugins.resolve("demo-1.0.0.zip");
        Path oldSidecar = sidecar(plugins, oldArtifact);
        String malformed = "formatVersion=broken\n";
        Files.writeString(oldSidecar, malformed, StandardCharsets.UTF_8);

        PreparedPluginTransaction prepared = installer.prepareTransaction(
                packageFile("repair-new.zip", "2.0.0"), false, PluginPackageOrigin.localUpload());
        assertThat(prepared.readyToCommit()).isTrue();
        CommittedPluginTransaction committed = installer.commitTransaction(prepared);

        assertThat(installer.rollbackTransaction(committed)).isTrue();
        assertThat(oldArtifact).exists();
        assertThat(Files.readString(oldSidecar, StandardCharsets.UTF_8)).isEqualTo(malformed);
        assertThat(prepared.target()).doesNotExist();
    }

    @Test
    @DisplayName("损坏 provenance 不阻塞使用可恢复事务删除旧包")
    void malformedProvenanceDoesNotBlockTransactionalRemoval() throws IOException {
        Path plugins = temp.resolve("plugins-remove-malformed-provenance");
        ExternalPluginInstaller installer = newInstaller(plugins);
        installFully(installer, packageFile("remove-malformed-provenance.zip", "1.0.0"));
        Path artifact = plugins.resolve("demo-1.0.0.zip");
        Files.writeString(sidecar(plugins, artifact), "formatVersion=broken\n", StandardCharsets.UTF_8);

        assertThat(installer.removeInstalled("demo")).isTrue();

        assertThat(artifact).doesNotExist();
        assertThat(sidecar(plugins, artifact)).doesNotExist();
        assertThat(installer.recoveryGateSnapshot().state()).isEqualTo(PluginRecoveryGateState.SAFE);
    }

    @Test
    @DisplayName("provenance 最终写入 plugins/provenance，旧根目录 sidecar 读取后迁移")
    void provenanceLivesUnderProvenanceDirectoryAndMigratesLegacySidecar() throws IOException {
        Path plugins = temp.resolve("plugins-provenance");
        ExternalPluginInstaller installer = newInstaller(plugins);
        installFully(installer, packageFile("provenance.zip", "1.0.0"));
        Path artifact = plugins.resolve("demo-1.0.0.zip");
        PluginProvenanceStore store = new PluginProvenanceStore(plugins);
        Path central = store.sidecarPath(artifact);
        Path legacy = legacySidecar(artifact);

        assertThat(central).isEqualTo(plugins.resolve("provenance")
                .resolve("demo-1.0.0.zip.pixiv-plugin-provenance"));
        assertThat(central).exists();
        assertThat(legacy).doesNotExist();

        Files.move(central, legacy);
        assertThat(store.read(artifact)).isPresent();

        assertThat(central).exists();
        assertThat(legacy).doesNotExist();
    }
}

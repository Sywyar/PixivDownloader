package top.sywyar.pixivdownload.plugin.runtime.install;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.PluginProvenanceStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.Comparator;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import top.sywyar.pixivdownload.plugin.runtime.install.model.InstalledPlugin;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginInstallOutcome;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageOrigin;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.CommittedPluginTransaction;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PreparedPluginTransaction;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageException;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageFixtures;

@DisplayName("外置插件文件事务：预校验、提交、回滚与启动恢复")
class ExternalPluginTransactionTest {

    Path temp;

    @BeforeEach
    void createWorkspaceTemp() throws IOException {
        temp = Path.of("target", "test-transactions", UUID.randomUUID().toString());
        Files.createDirectories(temp);
    }

    @AfterEach
    void cleanWorkspaceTemp() throws IOException {
        if (!Files.exists(temp)) {
            return;
        }
        try (var walk = Files.walk(temp)) {
            for (Path path : walk.sorted(Comparator.comparingInt(Path::getNameCount).reversed()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    @DisplayName("prepare 不触碰旧包，commit 后保留 backup，rollback 恢复旧版本")
    void rollbackRestoresPreviousArtifact() {
        Path plugins = temp.resolve("plugins");
        ExternalPluginInstaller installer = new ExternalPluginInstaller(plugins);
        installer.install(packageFile("v1.zip", "1.0.0"));
        Path old = plugins.resolve("demo-1.0.0.zip");
        assertThat(sidecar(plugins, old)).exists();

        PreparedPluginTransaction prepared = installer.prepareTransaction(
                packageFile("v2.zip", "2.0.0"), false, PluginPackageOrigin.localUpload());

        assertThat(prepared.result().outcome()).isEqualTo(PluginInstallOutcome.UPGRADED);
        assertThat(old).exists();
        assertThat(prepared.target()).doesNotExist();
        assertThat(prepared.stagedArtifact()).exists();
        assertThat(sidecar(plugins, prepared.stagedArtifact())).exists();

        CommittedPluginTransaction committed = installer.commitTransaction(prepared);
        assertThat(old).doesNotExist();
        assertThat(sidecar(plugins, old)).doesNotExist();
        assertThat(prepared.target()).exists();
        assertThat(sidecar(plugins, prepared.target())).exists();
        assertThat(committed.backups()).hasSize(1);
        assertThat(committed.backups().get(0).backup()).exists();
        assertThat(sidecar(plugins, committed.backups().get(0).backup())).exists();

        assertThat(installer.rollbackTransaction(committed)).isTrue();
        assertThat(old).exists();
        assertThat(sidecar(plugins, old)).exists();
        assertThat(prepared.target()).doesNotExist();
        assertThat(sidecar(plugins, prepared.target())).doesNotExist();
    }

    @Test
    @DisplayName("新包验证后仅隔离精确替代身份并随回滚恢复其 artifact 与 provenance")
    void replacementTransactionTargetsExactRetiredIdentity() {
        Path plugins = temp.resolve("plugins-replacement");
        ExternalPluginInstaller installer = new ExternalPluginInstaller(plugins);
        installer.install(packageFile("retired.zip", "novel-gallery", "1.0.0", null));
        installer.install(packageFile("third-party.zip", "novel-gallery-plus", "1.0.0", null));
        Path retired = plugins.resolve("novel-gallery-1.0.0.zip");
        Path unrelated = plugins.resolve("novel-gallery-plus-1.0.0.zip");

        PreparedPluginTransaction prepared = installer.prepareTransaction(
                packageFile("novel.zip", "novel", "1.0.0", "novel-gallery"), false,
                PluginPackageOrigin.localUpload());

        assertThat(prepared.readyToCommit()).isTrue();
        assertThat(retired).exists();
        assertThat(sidecar(plugins, retired)).exists();
        assertThat(unrelated).exists();

        CommittedPluginTransaction committed = installer.commitTransaction(prepared);
        assertThat(retired).doesNotExist();
        assertThat(sidecar(plugins, retired)).doesNotExist();
        assertThat(unrelated).exists();
        assertThat(sidecar(plugins, unrelated)).exists();
        assertThat(prepared.target()).exists();

        assertThat(installer.rollbackTransaction(committed)).isTrue();
        assertThat(retired).exists();
        assertThat(sidecar(plugins, retired)).exists();
        assertThat(unrelated).exists();
        assertThat(prepared.target()).doesNotExist();
    }

    @Test
    @DisplayName("替代包描述符校验失败时保留旧 artifact 与 provenance")
    void rejectedReplacementLeavesRetiredArtifactUntouched() {
        Path plugins = temp.resolve("plugins-replacement-rejected");
        ExternalPluginInstaller installer = new ExternalPluginInstaller(plugins);
        installer.install(packageFile("retired-invalid.zip", "novel-gallery", "1.0.0", null));
        Path retired = plugins.resolve("novel-gallery-1.0.0.zip");

        PreparedPluginTransaction prepared = installer.prepareTransaction(
                packageFile("invalid-novel.zip", "novel", "1.0", "novel-gallery"), false,
                PluginPackageOrigin.localUpload());

        assertThat(prepared.readyToCommit()).isFalse();
        assertThat(prepared.result().outcome()).isEqualTo(PluginInstallOutcome.REJECTED_INVALID);
        assertThat(retired).exists();
        assertThat(sidecar(plugins, retired)).exists();
    }

    @Test
    @DisplayName("NEW_PLACED 崩溃恢复优先恢复旧包，避免同 id 新旧包同时暴露")
    void recoverNewPlacedRestoresOld() {
        Path plugins = temp.resolve("plugins-recover-old");
        ExternalPluginInstaller installer = new ExternalPluginInstaller(plugins);
        installer.install(packageFile("old.zip", "1.0.0"));
        PreparedPluginTransaction prepared = installer.prepareTransaction(
                packageFile("new.zip", "2.0.0"), false, PluginPackageOrigin.localUpload());
        installer.commitTransaction(prepared);

        new ExternalPluginInstaller(plugins).recoverPendingTransactions();

        assertThat(plugins.resolve("demo-1.0.0.zip")).exists();
        assertThat(sidecar(plugins, plugins.resolve("demo-1.0.0.zip"))).exists();
        assertThat(plugins.resolve("demo-2.0.0.zip")).doesNotExist();
        assertThat(sidecar(plugins, plugins.resolve("demo-2.0.0.zip"))).doesNotExist();
        assertThat(installer.listInstalled()).extracting(InstalledPlugin::version).containsExactly("1.0.0");
    }

    @Test
    @DisplayName("ACTIVATED 崩溃恢复保留新包并清理 backup")
    void recoverActivatedCommitsNew() {
        Path plugins = temp.resolve("plugins-recover-new");
        ExternalPluginInstaller installer = new ExternalPluginInstaller(plugins);
        installer.install(packageFile("old-a.zip", "1.0.0"));
        PreparedPluginTransaction prepared = installer.prepareTransaction(
                packageFile("new-a.zip", "2.0.0"), false, PluginPackageOrigin.localUpload());
        CommittedPluginTransaction committed = installer.commitTransaction(prepared);
        installer.markActivated(committed);

        new ExternalPluginInstaller(plugins).recoverPendingTransactions();

        assertThat(plugins.resolve("demo-1.0.0.zip")).doesNotExist();
        assertThat(sidecar(plugins, plugins.resolve("demo-1.0.0.zip"))).doesNotExist();
        assertThat(plugins.resolve("demo-2.0.0.zip")).exists();
        assertThat(sidecar(plugins, plugins.resolve("demo-2.0.0.zip"))).exists();
        assertThat(installer.listInstalled()).extracting(InstalledPlugin::version).containsExactly("2.0.0");
        assertThat(plugins.resolve(".staging")).doesNotExist();
    }

    @Test
    @DisplayName("提交窗口前发现安装态变化时拒绝过期事务且不触碰当前版本")
    void stalePreparedTransactionIsRejectedBeforeCommit() {
        Path plugins = temp.resolve("plugins-stale");
        ExternalPluginInstaller installer = new ExternalPluginInstaller(plugins);
        installer.install(packageFile("stale-old.zip", "1.0.0"));
        PreparedPluginTransaction prepared = installer.prepareTransaction(
                packageFile("stale-next.zip", "2.0.0"), false, PluginPackageOrigin.localUpload());
        installer.install(packageFile("stale-current.zip", "3.0.0"), false);

        assertThatThrownBy(() -> installer.verifyCurrentArtifacts(prepared))
                .isInstanceOf(PluginPackageException.class)
                .hasMessageContaining("changed while transaction was staged");
        assertThat(installer.listInstalled()).extracting(InstalledPlugin::version).containsExactly("3.0.0");
    }

    @Test
    @DisplayName("删除已安装插件通过隔离事务完成并清理暂存目录")
    void removalUsesRecoverableTransaction() {
        Path plugins = temp.resolve("plugins-remove");
        ExternalPluginInstaller installer = new ExternalPluginInstaller(plugins);
        installer.install(packageFile("remove.zip", "1.0.0"));

        assertThat(installer.removeInstalled("demo")).isTrue();
        assertThat(installer.listInstalled()).isEmpty();
        assertThat(sidecar(plugins, plugins.resolve("demo-1.0.0.zip"))).doesNotExist();
        assertThat(plugins.resolve(".staging")).doesNotExist();
    }

    @Test
    @DisplayName("provenance 最终写入 plugins/provenance，旧根目录 sidecar 读取后迁移")
    void provenanceLivesUnderProvenanceDirectoryAndMigratesLegacySidecar() throws IOException {
        Path plugins = temp.resolve("plugins-provenance");
        ExternalPluginInstaller installer = new ExternalPluginInstaller(plugins);
        installer.install(packageFile("provenance.zip", "1.0.0"));
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

    private Path packageFile(String name, String version) {
        return PluginPackageFixtures.explodedZip(temp.resolve(name), "demo", version, "1.0", "demo.Plugin");
    }

    private Path packageFile(String name, String id, String version, String replaces) {
        String properties = PluginPackageFixtures.pluginProperties(id, version, "1.0", "demo.Plugin");
        if (replaces != null) {
            properties += "pixiv.replaces=" + replaces + "\n";
        }
        Path file = temp.resolve(name);
        PluginPackageFixtures.writeZip(file, java.util.Map.of(
                PluginPackageFixtures.PLUGIN_PROPERTIES, PluginPackageFixtures.bytes(properties),
                "classes/Marker.class", PluginPackageFixtures.bytes("marker")));
        return file;
    }

    private static Path sidecar(Path plugins, Path artifact) {
        return new PluginProvenanceStore(plugins).sidecarPath(artifact);
    }

    private static Path legacySidecar(Path artifact) {
        return artifact.resolveSibling(artifact.getFileName() + ".pixiv-plugin-provenance");
    }
}

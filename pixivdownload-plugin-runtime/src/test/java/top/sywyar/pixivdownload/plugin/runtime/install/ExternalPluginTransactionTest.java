package top.sywyar.pixivdownload.plugin.runtime.install;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.Comparator;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

        PreparedPluginTransaction prepared = installer.prepareTransaction(
                packageFile("v2.zip", "2.0.0"), false, PluginPackageOrigin.localUpload());

        assertThat(prepared.result().outcome()).isEqualTo(PluginInstallOutcome.UPGRADED);
        assertThat(old).exists();
        assertThat(prepared.target()).doesNotExist();
        assertThat(prepared.stagedArtifact()).exists();

        CommittedPluginTransaction committed = installer.commitTransaction(prepared);
        assertThat(old).doesNotExist();
        assertThat(prepared.target()).exists();
        assertThat(committed.backups()).hasSize(1);
        assertThat(committed.backups().get(0).backup()).exists();

        assertThat(installer.rollbackTransaction(committed)).isTrue();
        assertThat(old).exists();
        assertThat(prepared.target()).doesNotExist();
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
        assertThat(plugins.resolve("demo-2.0.0.zip")).doesNotExist();
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
        assertThat(plugins.resolve("demo-2.0.0.zip")).exists();
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
        assertThat(plugins.resolve(".staging")).doesNotExist();
    }

    private Path packageFile(String name, String version) {
        return PluginPackageFixtures.explodedZip(temp.resolve(name), "demo", version, "1.0", "demo.Plugin");
    }
}

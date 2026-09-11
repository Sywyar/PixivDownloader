package top.sywyar.pixivdownload.plugin.sdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import top.sywyar.pixivdownload.common.Utf8ConsoleStreams;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.i18n.MessageBundles;
import top.sywyar.pixivdownload.plugin.runtime.artifact.PluginDevelopmentArtifacts;
import top.sywyar.pixivdownload.plugin.runtime.artifact.PluginArtifactSnapshot;
import top.sywyar.pixivdownload.plugin.runtime.artifact.PluginRuntimeLayout;
import top.sywyar.pixivdownload.plugin.runtime.install.ExternalPluginInstaller;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginInstallResult;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginInstallOutcome;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageOrigin;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.CommittedPluginTransaction;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PreparedPluginTransaction;
import top.sywyar.pixivdownload.sdk.SdkVersion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/** 固定宿主提供给 SDK 工具的本地部署入口；仅消费本次显式确认的项目产物。 */
public final class SdkPluginInstaller {

    private SdkPluginInstaller() {
    }

    public static void main(String[] args) {
        Utf8ConsoleStreams.install();
        try {
            if (args.length == 3 && args[0].equals("cleanup")) {
                cleanup(Path.of(args[1]), Path.of(args[2]));
                System.out.println("PIXIV_SDK_INSTALL_RESULT={\"schemaVersion\":1,\"cleanup\":true}");
                return;
            }
            if (args.length != 4) {
                throw new IllegalArgumentException("SDK_INSTALL_ARGUMENTS");
            }
            PluginInstallResult result = install(
                    Path.of(args[0]), Path.of(args[1]), Path.of(args[2]), args[3]);
            if (!result.accepted()) {
                String key = "plugin.install.outcome."
                        + result.outcome().name().toLowerCase(Locale.ROOT).replace('_', '-');
                boolean conflict = result.outcome() == PluginInstallOutcome.REJECTED_INVALID
                        && result.descriptor() != null
                        && (result.previousVersion() != null || !result.descriptor().replaces().isEmpty());
                System.err.println(MessageBundles.get(conflict ? "sdk.install.id-conflict" : key, result.pluginId()));
                System.exit(1);
                return;
            }
            // 机器协议行不受宿主诊断日志影响，也不向工具暴露安装器实现类型。
            System.out.println("PIXIV_SDK_INSTALL_RESULT=" + new ObjectMapper().writeValueAsString(Map.of(
                    "schemaVersion", 1,
                    "sdkVersion", SdkVersion.VERSION,
                    "pluginId", result.pluginId(),
                    "pluginVersion", result.version(),
                    "executionMode", result.descriptor().executionMode().descriptorValue(),
                    "artifactSha256", args[3],
                    "installedPath", result.installedPath().toString())));
        } catch (Exception failure) {
            System.err.println(MessageBundles.get("cli.error.unexpected", failure.getMessage()));
            System.exit(1);
        }
    }

    static PluginInstallResult install(Path project, Path run, Path artifact, String confirmedSha256)
            throws IOException {
        if (PluginDevelopmentArtifacts.enabled()
                || confirmedSha256 == null || !confirmedSha256.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("SDK_INSTALL_IDENTITY");
        }
        Path projectRoot = project.toRealPath();
        Path plugins = workspacePlugins(projectRoot, run);
        Path candidate = artifact.toRealPath();
        if (!candidate.startsWith(projectRoot) || candidate.startsWith(projectRoot.resolve(".dev"))) {
            throw new IOException("SDK_INSTALL_ARTIFACT_LOCATION");
        }
        try (ExternalPluginInstaller installer = new ExternalPluginInstaller(plugins)) {
            if (!installer.recoverPendingTransactions().safeToScan()) {
                throw new IOException("SDK_INSTALL_RECOVERY");
            }
            PreparedPluginTransaction prepared = installer.prepareNewTransaction(
                    candidate, PluginPackageOrigin.localUnsignedUpload(confirmedSha256));
            if (!prepared.readyToCommit()) {
                return prepared.result();
            }
            CommittedPluginTransaction committed = null;
            try {
                committed = installer.commitTransaction(prepared);
                installer.verifyCommittedTarget(committed);
                // 沿用正式 process-restart 安装的持久化确认，插件在后续正常宿主启动时执行。
                installer.markActivated(committed);
                installer.completeTransaction(committed);
                if (committed.recoveryBlocked()) {
                    throw new IOException("SDK_INSTALL_RECOVERY");
                }
                return prepared.result();
            } catch (IOException | RuntimeException failure) {
                try {
                    if (committed != null && !committed.durableState().keepsCommittedArtifact()) {
                        if (!installer.rollbackTransaction(committed)) {
                            failure.addSuppressed(new IOException("SDK_INSTALL_ROLLBACK"));
                        }
                    } else if (committed == null) {
                        installer.discardPrepared(prepared);
                    }
                } catch (RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                throw failure;
            }
        }
    }

    static void cleanup(Path project, Path run) throws IOException {
        Path plugins = workspacePlugins(project.toRealPath(), run);
        if (!Files.isRegularFile(run.resolve(".sdk-run"), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("SDK_CLEANUP_OWNER");
        }
        try (ExternalPluginInstaller installer = new ExternalPluginInstaller(plugins)) {
            if (!installer.recoverPendingTransactions().safeToScan()) {
                throw new IOException("SDK_INSTALL_RECOVERY");
            }
            // 安装器持有正式目录 lease；此进程没有加载 generation，仅回收有 owner marker 的遗留快照。
            PluginArtifactSnapshot.cleanupAbandonedWorkspaces(new PluginRuntimeLayout(plugins));
        }
    }

    private static Path workspacePlugins(Path projectRoot, Path run) throws IOException {
        Path runsRoot = projectRoot.resolve(".dev/runs");
        Path runRoot = run.toAbsolutePath().normalize();
        if (!Files.isDirectory(runsRoot, LinkOption.NOFOLLOW_LINKS)
                || !runsRoot.toRealPath().equals(runsRoot)
                || !Files.isDirectory(runRoot, LinkOption.NOFOLLOW_LINKS)
                || !runRoot.toRealPath().equals(runRoot)
                || !runsRoot.equals(runRoot.getParent())) {
            throw new IOException("SDK_INSTALL_WORKSPACE");
        }
        Path plugins = runRoot.resolve(RuntimeFiles.DEFAULT_PLUGINS_DIR);
        if (!Files.isDirectory(plugins, LinkOption.NOFOLLOW_LINKS)
                || !plugins.toRealPath().equals(plugins)) {
            throw new IOException("SDK_INSTALL_PLUGINS_ROOT");
        }
        return plugins;
    }
}

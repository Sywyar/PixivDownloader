package top.sywyar.pixivdownload.plugin.runtime.install;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.plugin.runtime.artifact.PluginDevelopmentArtifacts;
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

abstract class ExternalPluginTransactionTestSupport {

    @TempDir
    Path temp;
    protected final List<ExternalPluginInstaller> installers = new ArrayList<>();
    private String previousDevelopmentMode;

    @BeforeEach
    void enableDevelopmentArtifacts() {
        previousDevelopmentMode = System.getProperty(PluginDevelopmentArtifacts.ENABLED_PROPERTY);
        System.setProperty(PluginDevelopmentArtifacts.ENABLED_PROPERTY, "true");
    }

    @AfterEach
    void closeInstallers() {
        try {
            for (int i = installers.size() - 1; i >= 0; i--) {
                installers.get(i).close();
            }
        } finally {
            if (previousDevelopmentMode == null) {
                System.clearProperty(PluginDevelopmentArtifacts.ENABLED_PROPERTY);
            } else {
                System.setProperty(PluginDevelopmentArtifacts.ENABLED_PROPERTY, previousDevelopmentMode);
            }
        }
    }

    protected ExternalPluginInstaller newInstaller(Path plugins) {
        ExternalPluginInstaller created = new ExternalPluginInstaller(plugins);
        installers.add(created);
        created.recoverPendingTransactions();
        return created;
    }

    protected void assertCommitRecoveryPrimary(
            Path plugins, Error originalFailure, Error recoveryFailure, Error expectedPrimary) {
        AtomicBoolean recovering = new AtomicBoolean();
        ExternalPluginInstaller installer = new ExternalPluginInstaller(plugins) {
            @Override
            void afterOldArtifactsIsolated(Path transaction) {
                recovering.set(true);
                throw originalFailure;
            }

            @Override
            void beforeRecoveryManifestRead(Path manifest) {
                if (recovering.get()) {
                    throw recoveryFailure;
                }
            }
        };
        installers.add(installer);
        assertThat(installer.recoverPendingTransactions().safeToScan()).isTrue();
        PreparedPluginTransaction prepared = installer.prepareTransaction(
                packageFile(plugins.getFileName() + ".zip", "1.0.0"),
                false, PluginPackageOrigin.localUpload());

        assertThatThrownBy(() -> installer.commitTransaction(prepared)).isSameAs(expectedPrimary);

        Error replaced = expectedPrimary == originalFailure ? recoveryFailure : originalFailure;
        assertThat(expectedPrimary.getSuppressed()).containsExactly(replaced);
        assertThat(replaced.getSuppressed()).isEmpty();
        assertThat(prepared.commitState()).isEqualTo(PreparedPluginTransaction.CommitState.UNSAFE);
        assertThat(installer.recoveryGateSnapshot().state()).isEqualTo(PluginRecoveryGateState.BLOCKED);
    }

    protected void assertCompletionRetirementPrimary(
            Path plugins, Throwable persistenceFailure, Error retirementFailure, Throwable expectedPrimary) {
        AtomicBoolean retiring = new AtomicBoolean();
        ExternalPluginInstaller installer = new ExternalPluginInstaller(plugins) {
            @Override
            void afterCommittedManifestPersisted(Path transaction) {
                retiring.set(true);
                if (persistenceFailure instanceof Error error) {
                    throw error;
                }
                throw (RuntimeException) persistenceFailure;
            }

            @Override
            void beforeManagedCleanup(Path root) {
                if (retiring.get()) {
                    throw retirementFailure;
                }
            }
        };
        installers.add(installer);
        assertThat(installer.recoverPendingTransactions().safeToScan()).isTrue();
        PreparedPluginTransaction prepared = installer.prepareTransaction(
                packageFile(plugins.getFileName() + ".zip", "1.0.0"),
                false, PluginPackageOrigin.localUpload());
        CommittedPluginTransaction committed = installer.commitTransaction(prepared);
        installer.markActivated(committed);

        assertThatThrownBy(() -> installer.completeTransaction(committed)).isSameAs(expectedPrimary);

        Throwable replaced = expectedPrimary == persistenceFailure ? retirementFailure : persistenceFailure;
        assertThat(expectedPrimary.getSuppressed()).containsExactly(replaced);
        assertThat(replaced.getSuppressed()).isEmpty();
        assertThat(committed.durableState())
                .isEqualTo(CommittedPluginTransaction.DurableState.RETIRED);
        assertThat(prepared.transactionDirectory()).doesNotExist();
        assertThat(prepared.target()).exists();
    }

    protected static PluginInstallResult installFully(ExternalPluginInstaller installer, Path packagePath) {
        return installFully(installer, packagePath, PluginPackageOrigin.localUpload());
    }

    protected static PluginInstallResult installFully(
            ExternalPluginInstaller installer, Path packagePath, PluginPackageOrigin origin) {
        PreparedPluginTransaction prepared = installer.prepareTransaction(
                packagePath, false, origin);
        if (!prepared.readyToCommit()) {
            return prepared.result();
        }
        CommittedPluginTransaction committed = installer.commitTransaction(prepared);
        installer.verifyCommittedTarget(committed);
        installer.markActivated(committed);
        installer.completeTransaction(committed);
        return prepared.result();
    }

    protected Path packageFile(String name, String version) {
        return PluginPackageFixtures.explodedZip(temp.resolve(name), "demo", version, "1.0", "demo.Plugin");
    }

    protected Path packageFile(String name, String id, String version, String replaces) {
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

    protected static String randomReplacementIds(int count, int idLength) {
        Random random = new Random(0x50_49_58_49_56L);
        StringBuilder result = new StringBuilder(count * (idLength + 1));
        for (int index = 0; index < count; index++) {
            if (index > 0) {
                result.append(',');
            }
            result.append('r');
            for (int character = 1; character < idLength; character++) {
                result.append((char) ('a' + random.nextInt(26)));
            }
        }
        return result.toString();
    }

    protected static Properties manifest(String transactionId, String state, String packageId, String version,
                                       Path target, int backupCount) {
        Properties properties = new Properties();
        properties.setProperty("format.version", "1");
        properties.setProperty("transaction.id", transactionId);
        properties.setProperty("operation", "INSTALL");
        properties.setProperty("state", state);
        properties.setProperty("package.id", packageId);
        properties.setProperty("version", version);
        properties.setProperty("target", target == null ? "" : target.toAbsolutePath().normalize().toString());
        Path staged = target == null ? Path.of("") : target.toAbsolutePath().normalize().getParent()
                .resolve(".staging").resolve(transactionId).resolve("new").resolve(target.getFileName());
        properties.setProperty("staged", target == null ? "" : staged.toString());
        expectedArtifact(properties, "artifact", packageId, version);
        properties.setProperty("replaces.count", "0");
        properties.setProperty("backup.count", Integer.toString(backupCount));
        for (int i = 0; i < backupCount; i++) {
            expectedArtifact(properties, "backup." + i, packageId, "1.0.0");
        }
        return properties;
    }

    protected static void expectedArtifact(Properties properties, String prefix, String id, String version) {
        properties.setProperty(prefix + ".id", id);
        properties.setProperty(prefix + ".version", version);
        properties.setProperty(prefix + ".size", "1");
        properties.setProperty(prefix + ".sha256", "0".repeat(64));
        properties.setProperty(prefix + ".sidecar.sha256", "0".repeat(64));
    }

    protected static Properties readManifest(Path transaction) throws IOException {
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(transaction.resolve("transaction.properties"),
                StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    protected static void writeManifest(Path transaction, Properties properties) throws IOException {
        Files.createDirectories(transaction);
        try (var writer = Files.newBufferedWriter(transaction.resolve("transaction.properties"),
                StandardCharsets.UTF_8)) {
            properties.store(writer, null);
        }
    }

    protected static Path sidecar(Path plugins, Path artifact) {
        return new PluginProvenanceStore(plugins).sidecarPath(artifact);
    }

    protected static Path legacySidecar(Path artifact) {
        return artifact.resolveSibling(artifact.getFileName() + ".pixiv-plugin-provenance");
    }
}

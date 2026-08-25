package top.sywyar.pixivdownload.plugin.runtime.install.recovery;

import top.sywyar.pixivdownload.plugin.runtime.install.provenance.PluginArtifactVerificationService;
import top.sywyar.pixivdownload.plugin.runtime.install.recovery.PluginRecoveryManifestValidator.ExpectedArtifact;
import top.sywyar.pixivdownload.plugin.runtime.install.recovery.PluginRecoveryManifestValidator.RecoveryBackup;
import top.sywyar.pixivdownload.plugin.runtime.install.recovery.PluginRecoveryManifestValidator.RecoveryManifest;
import top.sywyar.pixivdownload.plugin.runtime.install.recovery.PluginRecoveryManifestValidator.RecoveryOperation;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PluginTransactionRecoveryReport.FailureKind;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PluginTransactionState;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;

/** 恢复清单的状态安全校验、schema 序列化与原子发布 owner。 */
public final class PluginRecoveryManifestWriter {

    private final PluginRecoveryArtifactInspector artifactInspector;
    private final PluginRecoveryVisibleInventoryVerifier visibleInventoryVerifier;

    public PluginRecoveryManifestWriter(
            PluginRecoveryArtifactInspector artifactInspector,
            PluginRecoveryVisibleInventoryVerifier visibleInventoryVerifier
    ) {
        this.artifactInspector = Objects.requireNonNull(artifactInspector, "artifactInspector");
        this.visibleInventoryVerifier = Objects.requireNonNull(
                visibleInventoryVerifier, "visibleInventoryVerifier");
    }

    public void persist(
            Path transaction,
            String transactionId,
            RecoveryManifest manifest,
            String comment,
            PluginArtifactVerificationService verificationService
    ) throws IOException {
        persist(transaction, transactionId, manifest, manifest, comment,
                new PluginRecoveryResourceBudget(), verificationService);
    }

    public void persist(
            Path transaction,
            String transactionId,
            RecoveryManifest manifest,
            String comment,
            PluginRecoveryResourceBudget budget,
            PluginArtifactVerificationService verificationService
    ) throws IOException {
        persist(transaction, transactionId, manifest, manifest, comment, budget, verificationService);
    }

    /**
     * 事务发布前，路径按未发布目录校验，但清单必须保存发布后的规范路径。
     */
    public void persist(
            Path transaction,
            String transactionId,
            RecoveryManifest validationCandidate,
            RecoveryManifest storedManifest,
            String comment,
            PluginArtifactVerificationService verificationService
    ) throws IOException {
        persist(transaction, transactionId, validationCandidate, storedManifest, comment,
                new PluginRecoveryResourceBudget(), verificationService);
    }

    private void persist(
            Path transaction,
            String transactionId,
            RecoveryManifest validationCandidate,
            RecoveryManifest storedManifest,
            String comment,
            PluginRecoveryResourceBudget budget,
            PluginArtifactVerificationService verificationService
    ) throws IOException {
        validate(transaction, validationCandidate, budget, verificationService);
        PluginRecoveryManifestStore.persist(
                transaction,
                properties(transactionId, storedManifest),
                comment);
    }

    public void requireInstallStateTransition(
            PluginTransactionState current,
            PluginTransactionState next
    ) throws IOException {
        boolean valid = current == PluginTransactionState.PREPARED
                && (next == PluginTransactionState.PREPARED
                || next == PluginTransactionState.OLD_ISOLATED
                || next == PluginTransactionState.ROLLING_BACK)
                || current == PluginTransactionState.OLD_ISOLATED
                && (next == PluginTransactionState.NEW_PLACED
                || next == PluginTransactionState.ROLLING_BACK)
                || current == PluginTransactionState.NEW_PLACED
                && (next == PluginTransactionState.ACTIVATED
                || next == PluginTransactionState.ROLLING_BACK)
                || current == PluginTransactionState.ROLLING_BACK
                && next == PluginTransactionState.ROLLED_BACK
                || current == PluginTransactionState.ACTIVATED && next == PluginTransactionState.COMMITTED;
        if (!valid) {
            throw new IOException("invalid plugin transaction state transition: " + current + " -> " + next);
        }
    }

    private void validate(
            Path transaction,
            RecoveryManifest candidate,
            PluginRecoveryResourceBudget budget,
            PluginArtifactVerificationService verificationService
    ) throws IOException {
        try {
            if (candidate.replaces().size() > PluginRecoveryManifestValidator.MAX_BACKUPS
                    || candidate.backups().size() > PluginRecoveryManifestValidator.MAX_BACKUPS) {
                throw new PluginRecoveryValidationException(
                        FailureKind.INVALID_MANIFEST,
                        "generated transaction exceeds the supported replacement or backup count");
            }
            artifactInspector.validateTransactionTree(transaction, candidate);
            artifactInspector.validateState(candidate, budget);
            if (candidate.operation() == RecoveryOperation.INSTALL
                    && (candidate.state() == PluginTransactionState.ACTIVATED
                    || candidate.state() == PluginTransactionState.COMMITTED)) {
                visibleInventoryVerifier.verifyActivatedTarget(
                        candidate,
                        visibleInventoryVerifier.inspectVisibleInventory(budget),
                        budget,
                        verificationService);
            } else if (candidate.operation() == RecoveryOperation.REMOVE
                    && candidate.state() == PluginTransactionState.COMMITTED) {
                visibleInventoryVerifier.verifyRemovedIdentityAbsent(
                        candidate,
                        visibleInventoryVerifier.inspectVisibleInventory(budget));
            }
        } catch (PluginRecoveryValidationException e) {
            throw new IOException("plugin transaction state is unsafe: " + e.getMessage(), e);
        }
    }

    private static Properties properties(String transactionId, RecoveryManifest manifest) {
        Properties properties = new Properties();
        properties.setProperty("format.version", PluginRecoveryManifestValidator.FORMAT_VERSION);
        properties.setProperty("transaction.id", transactionId);
        properties.setProperty("operation", manifest.operation().name());
        properties.setProperty("state", manifest.state().name());
        properties.setProperty("package.id", manifest.packageId());
        properties.setProperty("version", Objects.toString(manifest.version(), ""));
        properties.setProperty("target", manifest.target() != null ? manifest.target().toString() : "");
        properties.setProperty("staged", manifest.staged() != null ? manifest.staged().toString() : "");
        if (manifest.newArtifact() != null) {
            writeExpectedArtifact(properties, "artifact", manifest.newArtifact());
        } else {
            properties.setProperty("artifact.id", "");
            properties.setProperty("artifact.version", "");
            properties.setProperty("artifact.size", "");
            properties.setProperty("artifact.sha256", "");
            properties.setProperty("artifact.sidecar.sha256", "");
        }
        properties.setProperty("replaces.count", Integer.toString(manifest.replaces().size()));
        for (int i = 0; i < manifest.replaces().size(); i++) {
            properties.setProperty("replaces." + i, manifest.replaces().get(i));
        }
        properties.setProperty("backup.count", Integer.toString(manifest.backups().size()));
        for (int i = 0; i < manifest.backups().size(); i++) {
            RecoveryBackup backup = manifest.backups().get(i);
            writeExpectedArtifact(properties, "backup." + i, backup.expected());
            properties.setProperty("backup." + i + ".origin", backup.origin().toString());
            properties.setProperty("backup." + i + ".path", backup.backup().toString());
        }
        return properties;
    }

    private static void writeExpectedArtifact(
            Properties properties,
            String prefix,
            ExpectedArtifact artifact
    ) {
        properties.setProperty(prefix + ".id", artifact.pluginId());
        properties.setProperty(prefix + ".version", artifact.version());
        properties.setProperty(prefix + ".size", Long.toString(artifact.size()));
        properties.setProperty(prefix + ".sha256", artifact.sha256());
        properties.setProperty(prefix + ".sidecar.sha256", artifact.sidecarSha256());
    }
}

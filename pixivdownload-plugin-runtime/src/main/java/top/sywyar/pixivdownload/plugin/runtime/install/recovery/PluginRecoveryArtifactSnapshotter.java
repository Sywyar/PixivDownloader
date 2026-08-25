package top.sywyar.pixivdownload.plugin.runtime.install.recovery;

import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageInspection;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageLimits;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.PluginArtifactVerificationService;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.PluginProvenanceStore;
import top.sywyar.pixivdownload.plugin.runtime.install.recovery.PluginRecoveryManifestValidator.ExpectedArtifact;
import top.sywyar.pixivdownload.plugin.runtime.install.recovery.PluginRecoveryManifestValidator.RecoveryBackup;
import top.sywyar.pixivdownload.plugin.runtime.install.recovery.PluginRecoveryManifestValidator.RecoveryManifest;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.CommittedPluginTransaction;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageException;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageIntegrity;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageReader;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageVerifier;
import top.sywyar.pixivdownload.plugin.signature.VerificationResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 恢复清单写入前冻结 artifact 身份、摘要与备份路径集合。 */
public final class PluginRecoveryArtifactSnapshotter {

    private final PluginPackageLimits limits;
    private final PluginProvenanceStore provenanceStore;

    public PluginRecoveryArtifactSnapshotter(
            PluginPackageLimits limits,
            PluginProvenanceStore provenanceStore
    ) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.provenanceStore = Objects.requireNonNull(provenanceStore, "provenanceStore");
    }

    public ExpectedArtifact snapshotInstallArtifact(
            Path first,
            Path second,
            String expectedId,
            String expectedVersion,
            PluginArtifactVerificationService verificationService
    ) throws IOException {
        return snapshotExpectedArtifact(
                first,
                second,
                expectedId,
                expectedVersion,
                true,
                Objects.requireNonNull(verificationService, "verificationService"));
    }

    public List<RecoveryBackup> freezeInstallBackups(
            RecoveryManifest existing,
            List<CommittedPluginTransaction.BackupArtifact> backups
    ) throws IOException {
        List<BackupPath> paths = backups.stream()
                .map(backup -> new BackupPath(backup.origin(), backup.backup()))
                .toList();
        return freezeBackups(existing, paths, null, "plugin transaction");
    }

    public List<RecoveryBackup> freezeRemovalBackups(
            RecoveryManifest existing,
            List<BackupPath> backups,
            String packageId
    ) throws IOException {
        return freezeBackups(existing, backups, packageId, "plugin removal");
    }

    private List<RecoveryBackup> freezeBackups(
            RecoveryManifest existing,
            List<BackupPath> backups,
            String expectedPluginId,
            String role
    ) throws IOException {
        List<RecoveryBackup> frozen = existing != null ? existing.backups() : List.of();
        if (!frozen.isEmpty()) {
            if (frozen.size() != backups.size()) {
                throw new IOException(role + " backup set changed after it was frozen");
            }
            for (int i = 0; i < frozen.size(); i++) {
                Path origin = backups.get(i).origin().toAbsolutePath().normalize();
                Path backup = backups.get(i).backup().toAbsolutePath().normalize();
                if (!frozen.get(i).origin().equals(origin) || !frozen.get(i).backup().equals(backup)) {
                    throw new IOException(role + " backup paths changed after they were frozen");
                }
            }
            return frozen;
        }

        List<RecoveryBackup> result = new ArrayList<>(backups.size());
        for (BackupPath backup : backups) {
            Path origin = backup.origin().toAbsolutePath().normalize();
            Path target = backup.backup().toAbsolutePath().normalize();
            result.add(new RecoveryBackup(
                    snapshotExpectedArtifact(origin, target, expectedPluginId, null, false, null),
                    origin,
                    target));
        }
        return List.copyOf(result);
    }

    private ExpectedArtifact snapshotExpectedArtifact(
            Path first,
            Path second,
            String expectedId,
            String expectedVersion,
            boolean requireSidecar,
            PluginArtifactVerificationService verificationService
    ) throws IOException {
        Path artifact = existingPlainFile(first).orElse(null);
        if (artifact == null) {
            artifact = existingPlainFile(second).orElse(null);
        }
        if (artifact == null) {
            throw new IOException("transaction artifact is missing while writing recovery manifest: " + first);
        }
        try {
            PluginPackageVerifier.verify(artifact, limits);
            PluginPackageInspection inspection = PluginPackageReader.inspect(artifact, limits);
            PluginDescriptor descriptor = inspection.descriptor();
            if (expectedId != null && !expectedId.equals(descriptor.id())) {
                throw new IOException("transaction artifact id changed while writing manifest: " + artifact);
            }
            if (expectedVersion != null && !expectedVersion.equals(descriptor.version())) {
                throw new IOException("transaction artifact version changed while writing manifest: " + artifact);
            }
            Path sidecar = provenanceStore.existingManagedSidecarPathStrict(artifact).orElse(null);
            if (requireSidecar && sidecar == null) {
                throw new IOException("transaction artifact provenance is missing: " + artifact);
            }
            if (sidecar != null && requireSidecar) {
                var provenance = provenanceStore.readRequiredForRecovery(artifact);
                VerificationResult verification = verificationService.verifyInstalled(
                        artifact,
                        descriptor,
                        provenance);
                if (!verification.accepted()) {
                    throw new IOException("transaction artifact provenance verification failed: "
                            + artifact + " (" + verification.status() + ")");
                }
            }
            return new ExpectedArtifact(
                    descriptor.id(),
                    descriptor.version(),
                    Files.size(artifact),
                    PluginPackageIntegrity.sha256Hex(artifact),
                    sidecar != null ? PluginPackageIntegrity.sha256Hex(sidecar) : "");
        } catch (PluginPackageException e) {
            throw new IOException("transaction artifact could not be inspected: " + artifact, e);
        }
    }

    private static Optional<Path> existingPlainFile(Path path) throws IOException {
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            return Optional.empty();
        }
        if (attributes.isSymbolicLink() || attributes.isOther() || !attributes.isRegularFile()) {
            throw new IOException("transaction artifact is not a plain file: " + path);
        }
        return Optional.of(path);
    }

    public record BackupPath(Path origin, Path backup) {
    }
}

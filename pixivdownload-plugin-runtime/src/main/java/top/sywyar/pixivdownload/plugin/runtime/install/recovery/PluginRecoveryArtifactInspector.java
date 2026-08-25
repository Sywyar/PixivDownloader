package top.sywyar.pixivdownload.plugin.runtime.install.recovery;

import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageInspection;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageLimits;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.PluginProvenanceStore;
import top.sywyar.pixivdownload.plugin.runtime.install.recovery.PluginRecoveryManifestValidator.ExpectedArtifact;
import top.sywyar.pixivdownload.plugin.runtime.install.recovery.PluginRecoveryManifestValidator.RecoveryBackup;
import top.sywyar.pixivdownload.plugin.runtime.install.recovery.PluginRecoveryManifestValidator.RecoveryManifest;
import top.sywyar.pixivdownload.plugin.runtime.install.recovery.PluginRecoveryManifestValidator.RecoveryOperation;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PluginTransactionRecoveryReport.FailureKind;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PluginTransactionState;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageException;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageIntegrity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/** 恢复事务目录树与 artifact 状态绑定的安全复核器。 */
public final class PluginRecoveryArtifactInspector {

    /** 单事务允许出现的文件系统 entry 上限，覆盖 manifest/new/removed 与每份 artifact/sidecar。 */
    private static final int MAX_TRANSACTION_ENTRIES = PluginRecoveryManifestValidator.MAX_BACKUPS * 4 + 16;

    private final PluginPackageLimits limits;
    private final PluginProvenanceStore provenanceStore;

    public PluginRecoveryArtifactInspector(
            PluginPackageLimits limits,
            PluginProvenanceStore provenanceStore
    ) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.provenanceStore = Objects.requireNonNull(provenanceStore, "provenanceStore");
    }

    /**
     * 清单声明之外的 transaction entry 一律拒绝；Files.walk 不跟随链接，且每个 entry 再以
     * NOFOLLOW_LINKS 检查，避免 junction / reparse point 越过事务边界。
     */
    public int validateTransactionTree(Path transaction, RecoveryManifest manifest)
            throws IOException, PluginRecoveryValidationException {
        Path normalizedTransaction = transaction.toAbsolutePath().normalize();
        Set<Path> allowedDirectories = new LinkedHashSet<>();
        Set<Path> allowedFiles = new LinkedHashSet<>();
        allowedDirectories.add(normalizedTransaction);
        allowedFiles.add(PluginRecoveryManifestStore.manifestPath(normalizedTransaction));
        allowedFiles.add(PluginRecoveryManifestStore.temporaryPath(normalizedTransaction));
        if (manifest.operation() == RecoveryOperation.INSTALL) {
            allowedDirectories.add(normalizedTransaction.resolve("new"));
            addArtifactAndSidecars(allowedFiles, manifest.staged());
        }
        if (!manifest.backups().isEmpty()) {
            allowedDirectories.add(normalizedTransaction.resolve(
                    PluginRecoveryManifestValidator.BACKUP_SUBDIRECTORY));
            for (RecoveryBackup backup : manifest.backups()) {
                addArtifactAndSidecars(allowedFiles, backup.backup());
            }
        }

        int entries = 0;
        try (Stream<Path> walk = Files.walk(normalizedTransaction)) {
            var iterator = walk.iterator();
            while (iterator.hasNext()) {
                Path entry = iterator.next();
                if (++entries > MAX_TRANSACTION_ENTRIES) {
                    throw invalidManifest("transaction tree exceeds the supported entry count");
                }
                Path normalized = entry.toAbsolutePath().normalize();
                BasicFileAttributes attributes = Files.readAttributes(
                        normalized, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink() || attributes.isOther()) {
                    throw unsafePath("transaction tree contains a symbolic link or reparse/special entry: "
                            + normalized);
                }
                if (attributes.isDirectory()) {
                    if (!allowedDirectories.contains(normalized)) {
                        throw unsafePath("transaction tree contains an undeclared directory: " + normalized);
                    }
                } else if (attributes.isRegularFile()) {
                    if (!allowedFiles.contains(normalized)) {
                        throw unsafePath("transaction tree contains an undeclared file: " + normalized);
                    }
                } else {
                    throw unsafePath("transaction tree contains an unsupported entry: " + normalized);
                }
            }
        }
        return entries;
    }

    /** 复核清单状态与磁盘分布；任何重复、副本缺失或摘要漂移都在第一次写入前失败。 */
    public void validateState(RecoveryManifest manifest)
            throws PluginRecoveryValidationException {
        validateState(manifest, new PluginRecoveryResourceBudget());
    }

    public void validateState(
            RecoveryManifest manifest,
            PluginRecoveryResourceBudget budget
    ) throws PluginRecoveryValidationException {
        for (RecoveryBackup backup : manifest.backups()) {
            LogicalArtifactState observed = inspectLogicalArtifact(
                    backup.expected(), backup.origin(), backup.backup(), budget);
            boolean finalized = manifest.state() == PluginTransactionState.COMMITTED
                    || manifest.operation() == RecoveryOperation.INSTALL
                    && manifest.state() == PluginTransactionState.ACTIVATED;
            if (manifest.state() == PluginTransactionState.ROLLED_BACK) {
                requireBackupRestored(backup, observed);
            } else if (finalized) {
                if (!observed.artifactAt(backup.backup())
                        || backup.expected().hasSidecar() && !observed.sidecarAt(backup.backup())) {
                    throw unsafePath("completed transaction does not retain its declared backup: "
                            + backup.backup());
                }
            } else {
                if (observed.artifactOwner() == null) {
                    throw unsafePath("declared backup artifact is missing: " + backup.origin());
                }
                if (backup.expected().hasSidecar() && observed.sidecarOwner() == null) {
                    throw unsafePath("declared backup provenance is missing: " + backup.origin());
                }
            }
        }

        if (manifest.operation() == RecoveryOperation.INSTALL) {
            LogicalArtifactState newState = inspectLogicalArtifact(
                    manifest.newArtifact(), manifest.staged(), manifest.target(), budget);
            if (newState.inspection() != null
                    && !List.copyOf(newState.inspection().descriptor().replaces()).equals(manifest.replaces())) {
                throw unsafePath("transaction replaces declaration does not match the new artifact descriptor");
            }
            switch (manifest.state()) {
                case PREPARED -> {
                    requireNewArtifactInspection(newState);
                    requireOwners(newState, manifest.staged(), manifest.staged(), "prepared artifact");
                }
                case OLD_ISOLATED -> {
                    requireNewArtifactInspection(newState);
                    if (newState.artifactOwner() == null || newState.sidecarOwner() == null) {
                        throw unsafePath("old-isolated transaction has an incomplete new artifact");
                    }
                }
                case NEW_PLACED, ACTIVATED, COMMITTED -> {
                    requireNewArtifactInspection(newState);
                    requireOwners(newState, manifest.target(), manifest.target(), "placed artifact");
                }
                case ROLLING_BACK -> {
                    // artifact 与 sidecar 可分别位于 staged / target，或已按单调回滚流程被删除。
                }
                case ROLLED_BACK -> {
                    if (newState.artifactOwner() != null || newState.sidecarOwner() != null) {
                        throw unsafePath("rolled-back transaction still owns the new artifact");
                    }
                }
            }
        }
    }

    public void requireBackupRestored(RecoveryBackup backup, LogicalArtifactState state)
            throws PluginRecoveryValidationException {
        if (!state.artifactAt(backup.origin())
                || backup.expected().hasSidecar() && !state.sidecarAt(backup.origin())
                || !backup.expected().hasSidecar() && state.sidecarOwner() != null) {
            throw unsafePath("rolled-back artifact is not fully restored at its origin: " + backup.origin());
        }
    }

    public LogicalArtifactState inspectLogicalArtifact(
            ExpectedArtifact expected,
            Path first,
            Path second,
            PluginRecoveryResourceBudget budget
    ) throws PluginRecoveryValidationException {
        Path artifactOwner = null;
        Path artifactAlias = null;
        Path sidecarOwner = null;
        Path sidecarAlias = null;
        Path sidecarPath = null;
        PluginPackageInspection artifactInspection = null;
        for (Path candidate : List.of(first, second)) {
            try {
                BasicFileAttributes attributes = readAttributesIfPresent(candidate).orElse(null);
                if (attributes != null) {
                    PluginPackageInspection candidateInspection = inspectBoundArtifact(
                            candidate, expected, "transaction artifact", budget);
                    if (artifactOwner == null) {
                        artifactOwner = candidate;
                        artifactInspection = candidateInspection;
                    } else if (!Files.isSameFile(artifactOwner, candidate)) {
                        throw unsafePath("artifact has multiple physical copies: " + first + " and " + second);
                    } else {
                        artifactAlias = candidate;
                    }
                }
                Path sidecar = provenanceStore.existingManagedSidecarPathStrict(candidate).orElse(null);
                if (sidecar != null) {
                    if (!expected.hasSidecar()) {
                        throw unsafePath("unexpected provenance sidecar: " + sidecar);
                    }
                    String digest = PluginPackageIntegrity.sha256Hex(sidecar);
                    if (!expected.sidecarSha256().equals(digest)) {
                        throw unsafePath("provenance digest does not match its manifest binding: " + sidecar);
                    }
                    if (sidecarOwner == null) {
                        sidecarOwner = candidate;
                        sidecarPath = sidecar;
                    } else if (!Files.isSameFile(sidecarPath, sidecar)) {
                        throw unsafePath("provenance has multiple physical copies: " + first + " and " + second);
                    } else {
                        sidecarAlias = candidate;
                    }
                }
            } catch (IOException e) {
                throw unsafePath("transaction artifact state could not be inspected: " + describeFailure(e));
            }
        }
        return new LogicalArtifactState(
                artifactOwner, artifactAlias, sidecarOwner, sidecarAlias, artifactInspection);
    }

    public PluginPackageInspection inspectBoundArtifact(
            Path artifact,
            ExpectedArtifact expected,
            String role,
            PluginRecoveryResourceBudget budget
    ) throws PluginRecoveryValidationException {
        try {
            BasicFileAttributes attributes = readAttributesIfPresent(artifact).orElse(null);
            if (attributes == null || attributes.isSymbolicLink() || attributes.isOther()
                    || !attributes.isRegularFile()) {
                throw unsafePath(role + " is missing or is not a plain regular file: " + artifact);
            }
            if (attributes.size() != expected.size()) {
                throw unsafePath(role + " size does not match its manifest binding: " + artifact);
            }
            if (attributes.size() > limits.maxArchiveBytes()) {
                throw unsafePath(role + " exceeds the configured archive limit before hashing: " + artifact);
            }
            String digest = PluginPackageIntegrity.sha256Hex(artifact);
            if (!expected.sha256().equals(digest)) {
                throw unsafePath(role + " digest does not match its manifest binding: " + artifact);
            }
            PluginPackageInspection inspection = budget.inspectArchive(artifact, digest, limits);
            PluginDescriptor descriptor = inspection.descriptor();
            if (!expected.pluginId().equals(descriptor.id())
                    || !expected.version().equals(descriptor.version())
                    || !descriptor.externalValidationErrors().isEmpty()) {
                throw unsafePath(role + " package identity is not valid or does not match its manifest binding: "
                        + artifact);
            }
            return inspection;
        } catch (IOException | PluginPackageException e) {
            throw unsafePath(role + " could not be verified: " + describeFailure(e));
        }
    }

    private void addArtifactAndSidecars(Set<Path> allowedFiles, Path artifact) {
        allowedFiles.add(artifact.toAbsolutePath().normalize());
        for (Path sidecar : provenanceStore.managedSidecarPaths(artifact)) {
            allowedFiles.add(sidecar.toAbsolutePath().normalize());
        }
    }

    private static void requireNewArtifactInspection(LogicalArtifactState state)
            throws PluginRecoveryValidationException {
        if (state.inspection() == null) {
            throw unsafePath("transaction new artifact is missing or incomplete");
        }
    }

    private static void requireOwners(
            LogicalArtifactState observed,
            Path artifactOwner,
            Path sidecarOwner,
            String role
    ) throws PluginRecoveryValidationException {
        if (!observed.artifactAt(artifactOwner) || !observed.sidecarAt(sidecarOwner)) {
            throw unsafePath(role + " is not at its state-bound path");
        }
    }

    private static Optional<BasicFileAttributes> readAttributesIfPresent(Path path) throws IOException {
        try {
            return Optional.of(Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        }
    }

    private static PluginRecoveryValidationException invalidManifest(String message) {
        return new PluginRecoveryValidationException(FailureKind.INVALID_MANIFEST, message);
    }

    private static PluginRecoveryValidationException unsafePath(String message) {
        return new PluginRecoveryValidationException(FailureKind.UNSAFE_PATH, message);
    }

    private static String describeFailure(Throwable error) {
        return error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getName() : error.getMessage();
    }

    public record LogicalArtifactState(
            Path artifactOwner,
            Path artifactAlias,
            Path sidecarOwner,
            Path sidecarAlias,
            PluginPackageInspection inspection
    ) {

        public boolean artifactAt(Path path) {
            return Objects.equals(path, artifactOwner) || Objects.equals(path, artifactAlias);
        }

        public boolean sidecarAt(Path path) {
            return Objects.equals(path, sidecarOwner) || Objects.equals(path, sidecarAlias);
        }
    }
}

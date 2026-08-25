package top.sywyar.pixivdownload.plugin.runtime.install.recovery;

import top.sywyar.pixivdownload.plugin.runtime.artifact.PluginArtifactScanner;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageInspection;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageLimits;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.PluginArtifactVerificationService;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.PluginProvenanceStore;
import top.sywyar.pixivdownload.plugin.runtime.install.recovery.PluginRecoveryManifestValidator.RecoveryManifest;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PluginTransactionRecoveryReport.FailureKind;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageException;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageIntegrity;
import top.sywyar.pixivdownload.plugin.signature.VerificationResult;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 复核恢复终态可见 artifact 的身份唯一性、物理绑定和 provenance。 */
public final class PluginRecoveryVisibleInventoryVerifier {

    private final Path pluginsDir;
    private final PluginPackageLimits limits;
    private final PluginRecoveryArtifactInspector artifactInspector;
    private final PluginProvenanceStore provenanceStore;

    public PluginRecoveryVisibleInventoryVerifier(
            Path pluginsDir,
            PluginPackageLimits limits,
            PluginRecoveryArtifactInspector artifactInspector,
            PluginProvenanceStore provenanceStore
    ) {
        this.pluginsDir = Objects.requireNonNull(pluginsDir, "pluginsDir").toAbsolutePath().normalize();
        this.limits = Objects.requireNonNull(limits, "limits");
        this.artifactInspector = Objects.requireNonNull(artifactInspector, "artifactInspector");
        this.provenanceStore = Objects.requireNonNull(provenanceStore, "provenanceStore");
    }

    public void verifyActivatedTarget(
            RecoveryManifest manifest,
            PluginArtifactVerificationService verificationService
    ) throws PluginRecoveryValidationException {
        try {
            PluginRecoveryResourceBudget budget = new PluginRecoveryResourceBudget();
            verifyActivatedTarget(manifest, inspectVisibleInventory(budget), budget, verificationService);
        } catch (IOException e) {
            throw unsafePath("visible plugin inventory could not be proven: " + describeFailure(e));
        }
    }

    public void verifyActivatedTarget(
            RecoveryManifest manifest,
            VisibleArtifactInventory inventory,
            PluginRecoveryResourceBudget budget,
            PluginArtifactVerificationService verificationService
    ) throws PluginRecoveryValidationException {
        verifyActivatedTargetBinding(manifest, budget, verificationService);
        verifyNoConflictingVisibleArtifacts(manifest, inventory);
    }

    public void verifyActivatedTargetBinding(
            RecoveryManifest manifest,
            PluginRecoveryResourceBudget budget,
            PluginArtifactVerificationService verificationService
    ) throws PluginRecoveryValidationException {
        PluginArtifactVerificationService verifier = Objects.requireNonNull(
                verificationService, "verificationService");
        try {
            PluginPackageInspection inspection = artifactInspector.inspectBoundArtifact(
                    manifest.target(), manifest.newArtifact(), "activated target", budget);
            if (!List.copyOf(inspection.descriptor().replaces()).equals(manifest.replaces())) {
                throw unsafePath("activated target replaces declaration does not match its recovery manifest");
            }
            var provenance = provenanceStore.readRequiredForRecovery(manifest.target());
            VerificationResult result = verifier.verifyInstalled(
                    manifest.target(), inspection.descriptor(), provenance);
            if (!result.accepted()) {
                throw unsafePath("activated target provenance verification failed: " + result.status());
            }
        } catch (IOException | RuntimeException e) {
            throw unsafePath("activated target provenance could not be proven: " + describeFailure(e));
        }
    }

    public void verifyRemovedIdentityAbsent(
            RecoveryManifest manifest,
            VisibleArtifactInventory inventory
    ) throws PluginRecoveryValidationException {
        verifyRemovedIdentityAbsent(manifest.packageId(), inventory);
    }

    public void verifyRemovedIdentityAbsent(
            String packageId,
            VisibleArtifactInventory inventory
    ) throws PluginRecoveryValidationException {
        boolean stillVisible = inventory.artifacts.stream()
                .anyMatch(plugin -> packageId.equals(plugin.descriptor().id()));
        if (stillVisible) {
            throw unsafePath("completed removal leaves a visible artifact for identity " + packageId);
        }
    }

    public void verifyRemovedIdentityAbsent(RecoveryManifest manifest)
            throws PluginRecoveryValidationException {
        try {
            verifyRemovedIdentityAbsent(manifest, inspectVisibleInventory());
        } catch (IOException e) {
            throw unsafePath("visible plugin inventory could not be proven: " + describeFailure(e));
        }
    }

    public VisibleArtifactInventory inspectVisibleInventory()
            throws IOException, PluginRecoveryValidationException {
        return inspectVisibleInventory(new PluginRecoveryResourceBudget());
    }

    public VisibleArtifactInventory inspectVisibleInventory(PluginRecoveryResourceBudget budget)
            throws IOException, PluginRecoveryValidationException {
        PluginArtifactScanner.ScanResult scan = PluginArtifactScanner.scan(pluginsDir);
        List<VisibleArtifact> artifacts = new ArrayList<>(scan.candidates().size());
        for (Path candidate : scan.candidates()) {
            try {
                String digest = PluginPackageIntegrity.sha256Hex(candidate);
                PluginPackageInspection inspection = budget.inspectArchive(candidate, digest, limits);
                if (!inspection.descriptor().externalValidationErrors().isEmpty()) {
                    throw unsafePath("visible plugin artifact has an invalid identity: " + candidate);
                }
                artifacts.add(new VisibleArtifact(candidate, inspection.descriptor()));
            } catch (PluginPackageException e) {
                throw unsafePath("visible plugin artifact could not be inspected before scan: "
                        + candidate + " (" + describeFailure(e) + ")");
            }
        }
        return new VisibleArtifactInventory(artifacts);
    }

    private static void verifyNoConflictingVisibleArtifacts(
            RecoveryManifest manifest,
            VisibleArtifactInventory inventory
    ) throws PluginRecoveryValidationException {
        Set<String> affectedIds = new LinkedHashSet<>(manifest.replaces());
        affectedIds.add(manifest.packageId());
        boolean targetSeen = inventory.artifacts.stream()
                .anyMatch(candidate -> candidate.path().equals(manifest.target()));
        for (VisibleArtifact candidate : inventory.artifacts) {
            if (candidate.path().equals(manifest.target())) {
                continue;
            }
            if (affectedIds.contains(candidate.descriptor().id())) {
                throw unsafePath("activated transaction leaves another visible artifact for identity "
                        + candidate.descriptor().id() + ": " + candidate.path());
            }
        }
        if (!targetSeen) {
            throw unsafePath("activated target is not the unique visible package artifact");
        }
    }

    private static PluginRecoveryValidationException unsafePath(String message) {
        return new PluginRecoveryValidationException(FailureKind.UNSAFE_PATH, message);
    }

    private static String describeFailure(Throwable error) {
        return error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getName() : error.getMessage();
    }

    private record VisibleArtifact(Path path, PluginDescriptor descriptor) {

        private VisibleArtifact {
            path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
            descriptor = Objects.requireNonNull(descriptor, "descriptor");
        }
    }

    public static final class VisibleArtifactInventory {

        private final List<VisibleArtifact> artifacts;

        private VisibleArtifactInventory(List<VisibleArtifact> artifacts) {
            this.artifacts = List.copyOf(Objects.requireNonNull(artifacts, "artifacts"));
        }

        public static VisibleArtifactInventory empty() {
            return new VisibleArtifactInventory(List.of());
        }
    }
}

package top.sywyar.pixivdownload.plugin.catalog.trust;

import top.sywyar.pixivdownload.plugin.catalog.repository.PluginRepository;
import top.sywyar.pixivdownload.plugin.catalog.repository.PluginRepositoryRegistry;
import top.sywyar.pixivdownload.plugin.runtime.admission.PluginArtifactAdmissionPolicy;
import top.sywyar.pixivdownload.plugin.runtime.admission.PluginArtifactAdmissionRequest;
import top.sywyar.pixivdownload.plugin.runtime.admission.PluginArtifactAdmissionResult;

import java.time.Instant;

/** 启动期只读最后有效吊销快照；REVOKED 在 PF4J load 前阻断，过期非命中只告警。 */
public final class PluginCatalogRevocationAdmissionPolicy implements PluginArtifactAdmissionPolicy {

    private final PluginRepositoryRegistry repositories;
    private final PluginCatalogTrustStateStore stateStore;

    public PluginCatalogRevocationAdmissionPolicy(PluginRepositoryRegistry repositories,
                                                  PluginCatalogTrustStateStore stateStore) {
        this.repositories = repositories;
        this.stateStore = stateStore;
    }

    @Override
    public PluginArtifactAdmissionResult evaluate(PluginArtifactAdmissionRequest request) {
        PluginRepository repository = repositories.find(request.repositoryId()).orElse(null);
        if (repository == null || !repository.revocationsRequired()) return PluginArtifactAdmissionResult.allow();
        PluginCatalogTrustStateStore.RevocationSnapshot snapshot = stateStore
                .revocations(repository.repositoryId()).orElse(null);
        if (snapshot == null) {
            return PluginArtifactAdmissionResult.warn("REVOCATION_SNAPSHOT_MISSING",
                    "no last-good revocation snapshot is available");
        }
        for (PluginCatalogTrustStateStore.RevocationEntry entry : snapshot.entries()) {
            if ("REVOKED".equals(entry.action()) && effective(entry.effectiveTime())
                    && matches(entry, repository, request)) {
                return PluginArtifactAdmissionResult.reject("PLUGIN_REVOKED",
                        entry.scope() + ": " + entry.reasonCode());
            }
        }
        try {
            if (Instant.now().isAfter(Instant.parse(snapshot.nextUpdate()))) {
                return PluginArtifactAdmissionResult.warn("REVOCATION_SNAPSHOT_EXPIRED",
                        "last-good snapshot expired at " + snapshot.nextUpdate());
            }
        } catch (RuntimeException failure) {
            return PluginArtifactAdmissionResult.warn("REVOCATION_SNAPSHOT_TIME_INVALID",
                    "last-good snapshot nextUpdate is invalid");
        }
        return PluginArtifactAdmissionResult.allow();
    }

    private static boolean matches(PluginCatalogTrustStateStore.RevocationEntry entry,
                                   PluginRepository repository, PluginArtifactAdmissionRequest request) {
        return switch (entry.scope()) {
            case "PACKAGE_SHA256" -> equalIgnoreCase(entry.packageSha256(), request.sha256());
            case "PLUGIN_VERSION" -> equal(entry.pluginId(), request.pluginId())
                    && equal(entry.version(), request.version());
            case "SIGNING_KEY" -> equal(entry.keyId(), request.keyId());
            case "PUBLISHER" -> equal(entry.publisherId(), repository.publisherId());
            default -> false;
        };
    }

    private static boolean equal(String left, String right) { return left != null && left.equals(right); }
    private static boolean equalIgnoreCase(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    private static boolean effective(String value) {
        try {
            return !Instant.parse(value).isAfter(Instant.now());
        } catch (RuntimeException failure) {
            return false;
        }
    }
}

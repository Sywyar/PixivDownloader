package top.sywyar.pixivdownload.plugin.catalog.trust;

import top.sywyar.pixivdownload.plugin.catalog.repository.PluginRepository;
import top.sywyar.pixivdownload.plugin.catalog.repository.PluginRepositoryRegistry;
import top.sywyar.pixivdownload.plugin.runtime.admission.PluginArtifactAdmissionPolicy;
import top.sywyar.pixivdownload.plugin.runtime.admission.PluginArtifactAdmissionRequest;
import top.sywyar.pixivdownload.plugin.runtime.admission.PluginArtifactAdmissionResult;
import top.sywyar.pixivdownload.sdk.community.review.CommunityReview;

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
        String publisherId = repository.publisherId();
        if (request.communityEvidence() != null) {
            try {
                var review = CommunityReview.read(request.communityEvidence());
                if (!review.pluginId().equals(request.pluginId()) || !review.version().equals(request.version())
                        || !review.packageSha256().equals(request.sha256())) throw new IllegalArgumentException();
                publisherId = review.owner().publisherId();
            } catch (RuntimeException invalid) {
                return PluginArtifactAdmissionResult.reject("COMMUNITY_REVIEW_INVALID", "community review binding is invalid");
            }
        }
        PluginCatalogTrustStateStore.RevocationSnapshot snapshot = stateStore
                .revocations(repository.repositoryId()).orElse(null);
        if (snapshot == null) {
            return PluginArtifactAdmissionResult.warn("REVOCATION_SNAPSHOT_MISSING",
                    "no last-good revocation snapshot is available");
        }
        for (PluginCatalogTrustStateStore.RevocationEntry entry : snapshot.entries()) {
            if ("REVOKED".equals(entry.action()) && PluginCatalogRevocationService.matches(entry,
                    request.pluginId(), request.version(), request.sha256(), request.keyId(), publisherId)) {
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

}

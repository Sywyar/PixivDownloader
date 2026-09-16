package top.sywyar.pixivdownload.plugin.catalog.community;

import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogTrustStores;
import top.sywyar.pixivdownload.plugin.catalog.error.PluginCatalogErrorCode;
import top.sywyar.pixivdownload.plugin.catalog.error.PluginCatalogException;
import top.sywyar.pixivdownload.plugin.catalog.manifest.PluginCatalogPackage;
import top.sywyar.pixivdownload.plugin.catalog.repository.PluginCatalogClientProvider;
import top.sywyar.pixivdownload.plugin.catalog.repository.PluginRepository;
import top.sywyar.pixivdownload.plugin.catalog.security.PluginCatalogStrictJson;
import top.sywyar.pixivdownload.plugin.runtime.install.model.CommunityPackageEvidence;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageReader;
import top.sywyar.pixivdownload.plugin.signature.ArtifactVerificationRequest;
import top.sywyar.pixivdownload.plugin.signature.PluginSupplyChainVerifier;
import top.sywyar.pixivdownload.plugin.signature.SignatureMetadata;
import top.sywyar.pixivdownload.plugin.signature.VerificationPolicy;
import top.sywyar.pixivdownload.plugin.signature.community.CommunityPackageVerificationRequest;
import top.sywyar.pixivdownload.sdk.community.format.CommunityJson;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues;
import top.sywyar.pixivdownload.sdk.community.identity.Publisher;
import top.sywyar.pixivdownload.sdk.community.project.CommunityPaths;
import top.sywyar.pixivdownload.sdk.community.review.CommunityReview;

import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;

/** 社区发布记录进入同一事务安装链前的双签及审核绑定校验。 */
@Service
public final class CommunityPackageService {
    private final PluginCatalogClientProvider clients;
    private final java.util.function.Function<PluginRepository, PluginSupplyChainVerifier> verifiers;
    @org.springframework.beans.factory.annotation.Autowired
    public CommunityPackageService(PluginCatalogClientProvider clients) {
        this(clients, PluginCatalogTrustStores::verifierForRepository);
    }
    CommunityPackageService(PluginCatalogClientProvider clients,
                            java.util.function.Function<PluginRepository, PluginSupplyChainVerifier> verifiers) {
        this.clients = clients;
        this.verifiers = verifiers;
    }

    public CommunityPackageEvidence review(PluginRepository repository, String pluginId, PluginCatalogPackage pkg) {
        try {
            if (!repository.community() || !"SOURCE_REVIEWED".equals(pkg.assuranceLevel()) || pkg.reviewRef() == null
                    || pkg.expectedSizeBytes() == null || pkg.signature() == null || pkg.historicalOwner() == null) throw rejected();
            var document = referenced(repository, pkg.reviewRef(), CommunityJson.Kind.REVIEW);
            var evidence = new CommunityPackageEvidence(pkg.assuranceLevel(), pkg.sourceCommit(), document.sha256(),
                    PluginCatalogStrictJson.strictUtf8(document.bytes()));
            var review = CommunityReview.read(evidence);
            review.requirePackage(pluginId, pkg.version(), pkg.expectedSizeBytes(), pkg.sha256());
            if (!review.owner().equals(pkg.historicalOwner())) throw rejected();
            return evidence;
        } catch (Exception failure) {
            throw rejected();
        }
    }

    public CommunityPackageEvidence verify(Path artifact, PluginRepository repository, String pluginId, PluginCatalogPackage pkg) {
        var evidence = review(repository, pluginId, pkg);
        var result = verifiers.apply(repository).verifyCommunityPackage(
                new CommunityPackageVerificationRequest(artifact, repository.repositoryId(), pluginId, pkg.version(),
                        pkg.expectedSizeBytes(), pkg.sha256(), evidence.assuranceLevel(), evidence.sourceCommit(),
                        evidence.reviewSha256(), pkg.signature(), false));
        if (!result.accepted()) throw rejected();
        var review = CommunityReview.read(evidence);
        review.requireDescriptor(PluginPackageReader.inspect(artifact).descriptor());
        var submission = referenced(repository, review.submissionRef(), CommunityJson.Kind.SUBMISSION).value();
        if (!submission.path("pluginId").asText().equals(pluginId) || !submission.path("version").asText().equals(pkg.version())
                || !submission.path("publisherId").asText().equals(review.owner().publisherId())
                || !submission.path("source").path("commit").asText().equals(evidence.sourceCommit())
                || submission.path("package").path("expectedSize").asLong() != pkg.expectedSizeBytes()
                || !submission.path("package").path("sha256").asText().equals(pkg.sha256())) throw rejected();
        var originalSignature = CommunityReview.value("signature", submission.path("package").get("signature"), SignatureMetadata.class);
        String publishedPath = "published/" + pluginId + "/" + pkg.version() + ".json";
        var published = document(repository, publishedPath, CommunityJson.Kind.PUBLISHED).value();
        if (!published.path("pluginId").asText().equals(pluginId) || !published.path("version").asText().equals(pkg.version())
                || !published.path("sourceCommit").asText().equals(evidence.sourceCommit())
                || !published.path("assuranceLevel").asText().equals(evidence.assuranceLevel())
                || published.path("package").path("expectedSize").asLong() != pkg.expectedSizeBytes()
                || !published.path("package").path("sha256").asText().equals(pkg.sha256())
                || !CommunityReview.value("signature", published.path("package").get("signature"), SignatureMetadata.class).equals(originalSignature)
                || !CommunityReview.value("signature", published.get("communitySignature"), SignatureMetadata.class).equals(pkg.signature())
                || !Objects.equals(published.get("owner"), CommunityJson.strictTree(CommunityJson.encode(review.owner()), 64 * 1024))
                || !CommunityReview.value("reference", published.get("reviewRef"), CommunityValues.Reference.class).equals(pkg.reviewRef())
                || !CommunityReview.value("reference", published.get("submissionRef"), CommunityValues.Reference.class).equals(review.submissionRef()))
            throw rejected();
        var historicalRef = CommunityReview.value("reference", published.get("historicalPublisherRef"), CommunityValues.Reference.class);
        var publisher = Publisher.read(referenced(repository, historicalRef, CommunityJson.Kind.PUBLISHER));
        if (!publisher.owner().equals(review.owner())) throw rejected();
        var original = new PluginSupplyChainVerifier(publisher.trustStore()).verifyArtifact(new ArtifactVerificationRequest(artifact,
                pluginId, pkg.version(), pkg.expectedSizeBytes(), pkg.sha256(), originalSignature, VerificationPolicy.installedCustom()));
        if (!original.accepted()) throw rejected();
        return evidence;
    }

    private CommunityJson.Document referenced(PluginRepository repository, CommunityValues.Reference reference, CommunityJson.Kind kind) {
        reference.validate();
        if (reference.size() > kind.maximumBytes()) throw rejected();
        var document = document(repository, reference.path(), kind);
        reference.verify(document.bytes());
        return document;
    }
    private CommunityJson.Document document(PluginRepository repository, String path, CommunityJson.Kind kind) {
        CommunityPaths.relative(path, false);
        String url = URI.create(CommunityDirectoryService.BASE_URL).resolve(path).toString();
        return CommunityJson.parse(kind, clients.clientFor(repository).fetchBytes(url, kind.maximumBytes()));
    }
    private static PluginCatalogException rejected() {
        return new PluginCatalogException(PluginCatalogErrorCode.CATALOG_UNAVAILABLE, "community package review binding rejected");
    }
}

package top.sywyar.pixivdownload.plugin.catalog.repository;

import java.util.List;

/** 用户确认前的只读仓库描述符投影；不包含可被客户端回传为保存权威的隐藏字段。 */
public record RepositoryImportPreview(
        String descriptorUrl,
        String descriptorHost,
        String descriptorSha256,
        String repositoryId,
        String displayName,
        String publisherId,
        String publisherDisplayName,
        String publisherHomepageUrl,
        String publisherHomepageHost,
        String catalogProtocol,
        String catalogEndpoint,
        String catalogHost,
        String revocationsUrl,
        String revocationsHost,
        String updateProofUrl,
        String updateProofHost,
        List<String> networkHosts,
        String networkProfile,
        String effectiveProxyPolicy,
        String redirectBoundary,
        List<RepositoryKeyPreview> trustedKeys,
        String communityDirectoryStatus,
        Long directorySequence,
        List<String> directoryCertifiedFingerprints,
        boolean existingRepository,
        boolean repositoryIdConflict,
        boolean keysChanged,
        boolean networkExpanded,
        String updateProofStatus,
        boolean restartRequired,
        String executableCodeWarningKey) {

    public RepositoryImportPreview {
        networkHosts = List.copyOf(networkHosts == null ? List.of() : networkHosts);
        trustedKeys = List.copyOf(trustedKeys == null ? List.of() : trustedKeys);
        directoryCertifiedFingerprints = List.copyOf(
                directoryCertifiedFingerprints == null ? List.of() : directoryCertifiedFingerprints);
    }
}

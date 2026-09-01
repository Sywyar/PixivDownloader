package top.sywyar.pixivdownload.plugin.catalog.repository;

import java.util.List;

/** repository-update-v1 连续性证明。 */
public record RepositoryUpdateDocument(
        Integer schemaVersion,
        String repositoryId,
        Long sequence,
        String previousDescriptorSha256,
        String newDescriptorUrl,
        String newDescriptorSha256,
        List<String> newKeyFingerprints,
        String signedByKeyId) {
}

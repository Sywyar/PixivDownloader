package top.sywyar.pixivdownload.plugin.catalog.repository;

import java.util.List;
import java.util.Map;

/** 用户导入的第三方仓库描述符 repository.json。 */
public record RepositoryDescriptor(
        Integer schemaVersion,
        String repositoryId,
        String displayName,
        Publisher publisher,
        Catalog catalog,
        String networkProfile,
        String revocationsUrl,
        String updateProofUrl,
        List<Key> trustedKeys,
        Map<String, Object> extensions) {

    public record Publisher(String id, String displayName, String homepageUrl) {
    }

    public record Catalog(String protocol, String endpoint) {
    }

    public record Key(
            String keyId,
            String algorithm,
            String publicKeySpkiBase64,
            String state,
            String publisher,
            String trustLabel) {
    }
}

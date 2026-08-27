package top.sywyar.pixivdownload.plugin.catalog.repository;

/** 描述符预览中的完整发布密钥事实。 */
public record RepositoryKeyPreview(
        String keyId,
        String algorithm,
        String state,
        String publisher,
        String trustLabel,
        String fingerprint,
        String fingerprintDisplay) {
}

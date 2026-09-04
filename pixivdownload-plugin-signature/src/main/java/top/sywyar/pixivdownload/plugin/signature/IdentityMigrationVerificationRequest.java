package top.sywyar.pixivdownload.plugin.signature;

/**
 * 验证旧插件身份对一个精确新制品及其新信任身份的迁移授权。
 *
 * @param from              当前已安装制品的持久化身份
 * @param to                候选制品验签得到的新身份
 * @param version           候选插件版本
 * @param artifactSizeBytes 候选制品字节数
 * @param artifactSha256    候选制品 SHA-256 十六进制
 * @param signature         由 {@code from.keyId} 对迁移 envelope 生成的 detached 签名
 * @param policy            旧身份的离线验签策略（允许已退役但未撤销的旧 key）
 */
public record IdentityMigrationVerificationRequest(
        Identity from,
        Identity to,
        String version,
        long artifactSizeBytes,
        String artifactSha256,
        SignatureMetadata signature,
        VerificationPolicy policy) {

    /** 一个插件制品的宿主可信身份；字段均来自已验证 provenance，不接受插件自报。 */
    public record Identity(
            String pluginId,
            String source,
            String repositoryId,
            boolean officialRepository,
            String publisher,
            String keyId) {
    }
}

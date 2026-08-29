package top.sywyar.pixivdownload.plugin.signature;

/**
 * 验证当前来源仓库根对一个精确新制品及其新信任身份的迁移声明。
 *
 * @param from              当前已安装制品的持久化身份
 * @param to                候选制品验签得到的新身份
 * @param version           候选插件版本
 * @param artifactSizeBytes 候选制品字节数
 * @param artifactSha256    候选制品 SHA-256 十六进制
 * @param authorization     仓库根签发的迁移声明
 * @param policy            当前来源仓库的在线验签策略，只接受活动根
 */
public record RepositoryIdentityMigrationVerificationRequest(
        IdentityMigrationVerificationRequest.Identity from,
        IdentityMigrationVerificationRequest.Identity to,
        String version,
        long artifactSizeBytes,
        String artifactSha256,
        RepositoryIdentityMigrationAuthorization authorization,
        VerificationPolicy policy) {
}

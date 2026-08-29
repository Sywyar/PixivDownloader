package top.sywyar.pixivdownload.plugin.signature;

/**
 * 由当前已安装来源仓库的活动信任根签发的插件身份迁移声明。
 *
 * @param reason    旧插件 key 无法继续签发迁移授权的原因
 * @param signature 仓库根对精确迁移 envelope 的 detached 签名
 */
public record RepositoryIdentityMigrationAuthorization(
        String reason,
        SignatureMetadata signature) {

    public static final String KEY_UNAVAILABLE = "KEY_UNAVAILABLE";
    public static final String KEY_REVOKED = "KEY_REVOKED";
}

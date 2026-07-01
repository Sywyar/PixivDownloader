package top.sywyar.pixivdownload.plugin.signature;

/**
 * catalog 条目或 sidecar 文件携带的 detached 签名元数据。
 *
 * @param formatVersion 签名 envelope 格式版本
 * @param algorithm     签名算法，当前只支持 {@code Ed25519}
 * @param keyId         信任根 key 标识，只用于查找信任根
 * @param value         Base64 编码的 detached 签名字节
 */
public record SignatureMetadata(
        int formatVersion,
        String algorithm,
        String keyId,
        String value) {

    public static final int FORMAT_VERSION = 1;
    public static final String ED25519 = "Ed25519";
}

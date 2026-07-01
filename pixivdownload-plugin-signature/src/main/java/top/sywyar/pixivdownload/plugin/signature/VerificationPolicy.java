package top.sywyar.pixivdownload.plugin.signature;

/**
 * 宿主 owned 的单次验签信任策略。
 *
 * @param signatureRequired     未签名 artifact / manifest 必须拒绝
 * @param unsignedAllowed       本地未签名 artifact 可被记录为未验证本地来源
 * @param officialTrustRequired 已接受签名必须链到官方信任根
 * @param retiredKeysAllowed    退役 key 可验证已安装 / 离线复验 artifact
 * @param sourceLabel           诊断来源维度
 */
public record VerificationPolicy(
        boolean signatureRequired,
        boolean unsignedAllowed,
        boolean officialTrustRequired,
        boolean retiredKeysAllowed,
        String sourceLabel) {

    public VerificationPolicy {
        if (signatureRequired && unsignedAllowed) {
            throw new IllegalArgumentException("signatureRequired and unsignedAllowed are mutually exclusive");
        }
    }

    public static VerificationPolicy officialRepository() {
        return new VerificationPolicy(true, false, true, false, "official");
    }

    public static VerificationPolicy customRepository() {
        return new VerificationPolicy(true, false, false, false, "custom");
    }

    public static VerificationPolicy installedOfficial() {
        return new VerificationPolicy(true, false, true, true, "official-installed");
    }

    public static VerificationPolicy installedCustom() {
        return new VerificationPolicy(true, false, false, true, "custom-installed");
    }

    public static VerificationPolicy localUnsignedAllowed() {
        return new VerificationPolicy(false, true, false, false, "local");
    }
}

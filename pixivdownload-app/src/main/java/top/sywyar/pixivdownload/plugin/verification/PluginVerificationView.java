package top.sywyar.pixivdownload.plugin.verification;

/**
 * 插件验签状态的前端 / GUI 稳定投影。前端只消费这些字段，不根据 sha256、HTTPS、仓库名或 keyId 自行推断可信状态。
 *
 * @param status                 稳定状态码
 * @param source                 来源维度
 * @param keyId                  签名 key id
 * @param publisher              发布者标签
 * @param trustLabel             信任根标签
 * @param lastVerifiedAt         最后验签时间
 * @param offlineReverifySuccess 最近离线复验是否成功
 * @param diagnosticCode         失败诊断码
 */
public record PluginVerificationView(
        String status,
        String source,
        String keyId,
        String publisher,
        String trustLabel,
        String lastVerifiedAt,
        boolean offlineReverifySuccess,
        String diagnosticCode) {
}

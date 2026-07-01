package top.sywyar.pixivdownload.push.channel.dingtalk;

import top.sywyar.pixivdownload.push.PushChannelSettings;
import top.sywyar.pixivdownload.push.PushChannelType;

/**
 * 钉钉通道的不可变设置快照。由 {@link DingTalkConfig#toSettings()}（已保存配置）或 GUI 测试表单构造。
 *
 * @param secret 「加签」安全设置密钥（SEC 开头）；可空（用关键词 / IP 白名单安全设置时不填）。非空时按
 *               HMAC-SHA256 计算签名追加到 URL。属于必填项判定之外的可选项。
 */
public record DingTalkSettings(String accessToken, String secret, boolean useProxy)
        implements PushChannelSettings {

    public DingTalkSettings {
        accessToken = accessToken == null ? "" : accessToken.trim();
        secret = secret == null ? "" : secret.trim();
    }

    @Override
    public PushChannelType type() {
        return PushChannelType.DINGTALK;
    }

    @Override
    public boolean isComplete() {
        return !accessToken.isBlank();
    }
}

package top.sywyar.pixivdownload.push.channel.feishu;

import top.sywyar.pixivdownload.push.PushChannelSettings;
import top.sywyar.pixivdownload.push.PushChannelType;

/**
 * 飞书通道的不可变设置快照。由 {@link FeishuConfig#toSettings()}（已保存配置）或 GUI 测试表单构造。
 *
 * @param secret 「签名校验」密钥；可空（用关键词 / IP 白名单安全设置时不填）。非空时按飞书算法在请求体里加
 *               {@code timestamp}/{@code sign}。属于必填项判定之外的可选项。
 */
public record FeishuSettings(String webhookKey, String secret, boolean useProxy)
        implements PushChannelSettings {

    public FeishuSettings {
        webhookKey = webhookKey == null ? "" : webhookKey.trim();
        secret = secret == null ? "" : secret.trim();
    }

    @Override
    public PushChannelType type() {
        return PushChannelType.FEISHU;
    }

    @Override
    public boolean isComplete() {
        return !webhookKey.isBlank();
    }
}

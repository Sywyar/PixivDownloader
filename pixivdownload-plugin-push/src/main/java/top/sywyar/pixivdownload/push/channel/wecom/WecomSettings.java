package top.sywyar.pixivdownload.push.channel.wecom;

import top.sywyar.pixivdownload.push.PushChannelSettings;
import top.sywyar.pixivdownload.push.PushChannelType;

/**
 * 企业微信群机器人通道的不可变设置快照。由 {@link WecomConfig#toSettings()} 或 GUI 测试表单构造。
 */
public record WecomSettings(String key, boolean useProxy) implements PushChannelSettings {

    public WecomSettings {
        key = key == null ? "" : key.trim();
    }

    @Override
    public PushChannelType type() {
        return PushChannelType.WECOM;
    }

    @Override
    public boolean isComplete() {
        return !key.isBlank();
    }
}

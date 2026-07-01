package top.sywyar.pixivdownload.push.channel.bark;

import top.sywyar.pixivdownload.push.PushChannelSettings;
import top.sywyar.pixivdownload.push.PushChannelType;

/**
 * Bark 通道的不可变设置快照。由 {@link BarkConfig#toSettings()}（已保存配置）或 GUI 测试表单构造。
 */
public record BarkSettings(String server, String deviceKey, String sound, boolean useProxy)
        implements PushChannelSettings {

    public BarkSettings {
        server = server == null ? "" : server.trim();
        deviceKey = deviceKey == null ? "" : deviceKey.trim();
        sound = sound == null ? "" : sound.trim();
    }

    @Override
    public PushChannelType type() {
        return PushChannelType.BARK;
    }

    @Override
    public boolean isComplete() {
        return !server.isBlank() && !deviceKey.isBlank();
    }
}

package top.sywyar.pixivdownload.push.channel.serverchan;

import top.sywyar.pixivdownload.push.PushChannelSettings;
import top.sywyar.pixivdownload.push.PushChannelId;
import top.sywyar.pixivdownload.push.PushChannelIds;

/**
 * Server 酱通道的不可变设置快照。由 {@link ServerChanConfig#toSettings()} 或 GUI 测试表单构造。
 */
public record ServerChanSettings(String sendKey, boolean useProxy) implements PushChannelSettings {

    public ServerChanSettings {
        sendKey = sendKey == null ? "" : sendKey.trim();
    }

    @Override
    public PushChannelId type() {
        return PushChannelIds.SERVERCHAN;
    }

    @Override
    public boolean isComplete() {
        return !sendKey.isBlank();
    }
}

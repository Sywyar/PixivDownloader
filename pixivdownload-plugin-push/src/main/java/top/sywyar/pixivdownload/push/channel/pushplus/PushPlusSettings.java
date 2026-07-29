package top.sywyar.pixivdownload.push.channel.pushplus;

import top.sywyar.pixivdownload.push.PushChannelSettings;
import top.sywyar.pixivdownload.push.PushChannelId;
import top.sywyar.pixivdownload.push.PushChannelIds;

/**
 * PushPlus 通道的不可变设置快照。由 {@link PushPlusConfig#toSettings()} 或 GUI 测试表单构造。
 */
public record PushPlusSettings(String token, boolean useProxy) implements PushChannelSettings {

    public PushPlusSettings {
        token = token == null ? "" : token.trim();
    }

    @Override
    public PushChannelId type() {
        return PushChannelIds.PUSHPLUS;
    }

    @Override
    public boolean isComplete() {
        return !token.isBlank();
    }
}

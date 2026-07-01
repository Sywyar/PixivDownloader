package top.sywyar.pixivdownload.push.channel.telegram;

import top.sywyar.pixivdownload.push.PushChannelSettings;
import top.sywyar.pixivdownload.push.PushChannelType;

/**
 * Telegram 通道的不可变设置快照。由 {@link TelegramConfig#toSettings()}（已保存配置）或 GUI 测试表单构造。
 */
public record TelegramSettings(String botToken, String chatId, boolean useProxy)
        implements PushChannelSettings {

    public TelegramSettings {
        botToken = botToken == null ? "" : botToken.trim();
        chatId = chatId == null ? "" : chatId.trim();
    }

    @Override
    public PushChannelType type() {
        return PushChannelType.TELEGRAM;
    }

    @Override
    public boolean isComplete() {
        return !botToken.isBlank() && !chatId.isBlank();
    }
}

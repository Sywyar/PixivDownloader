package top.sywyar.pixivdownload.push.channel.telegram;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Telegram Bot 通道配置，映射 {@code config.yaml} 中的 {@code push.telegram.*}。
 */
@Data
@Component
@ConfigurationProperties(prefix = "push.telegram")
public class TelegramConfig {

    public static final String KEY_ENABLED = "push.telegram.enabled";
    public static final String KEY_BOT_TOKEN = "push.telegram.bot-token";
    public static final String KEY_CHAT_ID = "push.telegram.chat-id";
    public static final String KEY_USE_PROXY = "push.telegram.use-proxy";

    /** 是否启用本通道。 */
    private volatile boolean enabled = false;

    /** Bot token（密钥；出现在请求 URL 中，绝不写入日志）。 */
    private volatile String botToken = "";

    /** 目标会话 id（用户 / 群 / 频道）。 */
    private volatile String chatId = "";

    /** 是否走 HTTP 代理；Telegram 在国内通常需要代理，默认开启。 */
    private volatile boolean useProxy = true;

    /** 打包成不可变设置快照，供 {@link TelegramPushChannel} 请求时读取。 */
    public TelegramSettings toSettings() {
        return new TelegramSettings(botToken, chatId, useProxy);
    }
}

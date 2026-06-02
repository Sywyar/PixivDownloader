package top.sywyar.pixivdownload.push.channel.dingtalk;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 钉钉自定义机器人通道配置，映射 {@code config.yaml} 中的 {@code push.dingtalk.*}。
 */
@Data
@Component
@ConfigurationProperties(prefix = "push.dingtalk")
public class DingTalkConfig {

    public static final String KEY_ENABLED = "push.dingtalk.enabled";
    public static final String KEY_ACCESS_TOKEN = "push.dingtalk.access-token";
    public static final String KEY_SECRET = "push.dingtalk.secret";
    public static final String KEY_USE_PROXY = "push.dingtalk.use-proxy";

    /** 是否启用本通道。 */
    private volatile boolean enabled = false;

    /** 机器人 Webhook 的 access_token（密钥；绝不写入日志）。 */
    private volatile String accessToken = "";

    /**
     * 「加签」安全设置的密钥（SEC 开头；密钥，绝不写入日志）。留空表示机器人用「自定义关键词」或「IP 白名单」
     * 安全设置——钉钉自定义机器人<b>必须</b>三选一配置安全设置，否则消息会被拒收。填写后请求会按 HMAC-SHA256
     * 计算 {@code timestamp}/{@code sign} 追加到 Webhook URL。
     */
    private volatile String secret = "";

    /** 是否走 HTTP 代理；钉钉通常国内直连，默认关闭。 */
    private volatile boolean useProxy = false;

    /** 打包成不可变设置快照，供 {@link DingTalkPushChannel} 请求时读取。 */
    public DingTalkSettings toSettings() {
        return new DingTalkSettings(accessToken, secret, useProxy);
    }
}

package top.sywyar.pixivdownload.push.channel.feishu;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 飞书自定义机器人通道配置，映射 {@code config.yaml} 中的 {@code push.feishu.*}。
 */
@Data
@Component
@ConfigurationProperties(prefix = "push.feishu")
public class FeishuConfig {

    public static final String KEY_ENABLED = "push.feishu.enabled";
    public static final String KEY_WEBHOOK_KEY = "push.feishu.webhook-key";
    public static final String KEY_SECRET = "push.feishu.secret";
    public static final String KEY_USE_PROXY = "push.feishu.use-proxy";

    /** 是否启用本通道。 */
    private volatile boolean enabled = false;

    /** Webhook 地址末段的 hook key（即 {@code .../bot/v2/hook/<这里>}；密钥，绝不写入日志）。 */
    private volatile String webhookKey = "";

    /**
     * 「签名校验」安全设置密钥（密钥，绝不写入日志）。留空表示机器人用「自定义关键词」或「IP 白名单」安全设置。
     * 填写后请求会按飞书算法计算 {@code timestamp}（秒）/{@code sign} 放入<b>请求体</b>。
     */
    private volatile String secret = "";

    /** 是否走 HTTP 代理；飞书通常国内直连，默认关闭。 */
    private volatile boolean useProxy = false;

    /** 打包成不可变设置快照，供 {@link FeishuPushChannel} 请求时读取。 */
    public FeishuSettings toSettings() {
        return new FeishuSettings(webhookKey, secret, useProxy);
    }
}

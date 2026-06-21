package top.sywyar.pixivdownload.push.channel.wecom;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 企业微信群机器人通道配置，映射 {@code config.yaml} 中的 {@code push.wecom.*}。
 */
@Data
@Component
@ConfigurationProperties(prefix = "push.wecom")
public class WecomConfig {

    public static final String KEY_ENABLED = "push.wecom.enabled";
    public static final String KEY_KEY = "push.wecom.key";
    public static final String KEY_USE_PROXY = "push.wecom.use-proxy";

    /** 是否启用本通道。 */
    private volatile boolean enabled = false;

    /** 群机器人 Webhook 的 key（密钥，绝不写入日志）。 */
    private volatile String key = "";

    /** 是否走 HTTP 代理；企业微信通常国内直连，默认关闭。 */
    private volatile boolean useProxy = false;

    /** 打包成不可变设置快照，供 {@link WecomPushChannel} 请求时读取。 */
    public WecomSettings toSettings() {
        return new WecomSettings(key, useProxy);
    }
}

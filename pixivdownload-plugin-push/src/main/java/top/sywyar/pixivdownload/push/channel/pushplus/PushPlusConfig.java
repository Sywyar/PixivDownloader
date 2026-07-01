package top.sywyar.pixivdownload.push.channel.pushplus;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * PushPlus（推送加）通道配置，映射 {@code config.yaml} 中的 {@code push.pushplus.*}。
 */
@Data
@Component
@ConfigurationProperties(prefix = "push.pushplus")
public class PushPlusConfig {

    public static final String KEY_ENABLED = "push.pushplus.enabled";
    public static final String KEY_TOKEN = "push.pushplus.token";
    public static final String KEY_USE_PROXY = "push.pushplus.use-proxy";

    /** 是否启用本通道。 */
    private volatile boolean enabled = false;

    /** 用户令牌 token（密钥，绝不写入日志）。 */
    private volatile String token = "";

    /** 是否走 HTTP 代理；PushPlus 通常国内直连，默认关闭。 */
    private volatile boolean useProxy = false;

    /** 打包成不可变设置快照，供 {@link PushPlusPushChannel} 请求时读取。 */
    public PushPlusSettings toSettings() {
        return new PushPlusSettings(token, useProxy);
    }
}

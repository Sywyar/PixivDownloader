package top.sywyar.pixivdownload.push.channel.serverchan;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Server 酱（Turbo / ³）通道配置，映射 {@code config.yaml} 中的 {@code push.serverchan.*}。
 */
@Data
@Component
@ConfigurationProperties(prefix = "push.serverchan")
public class ServerChanConfig {

    public static final String KEY_ENABLED = "push.serverchan.enabled";
    public static final String KEY_SEND_KEY = "push.serverchan.send-key";
    public static final String KEY_USE_PROXY = "push.serverchan.use-proxy";

    /** 是否启用本通道。 */
    private volatile boolean enabled = false;

    /** SendKey（密钥，绝不写入日志）。{@code sctp} 前缀的 key 会自动走 Server 酱³ 端点。 */
    private volatile String sendKey = "";

    /** 是否走 HTTP 代理；Server 酱通常国内直连，默认关闭。 */
    private volatile boolean useProxy = false;

    /** 打包成不可变设置快照，供 {@link ServerChanPushChannel} 请求时读取。 */
    public ServerChanSettings toSettings() {
        return new ServerChanSettings(sendKey, useProxy);
    }
}

package top.sywyar.pixivdownload.push.channel.bark;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Bark 通道配置，映射 {@code config.yaml} 中的 {@code push.bark.*}。与其它通道配置相互独立。
 */
@Data
@Component
@ConfigurationProperties(prefix = "push.bark")
public class BarkConfig {

    public static final String KEY_ENABLED = "push.bark.enabled";
    public static final String KEY_SERVER = "push.bark.server";
    public static final String KEY_DEVICE_KEY = "push.bark.device-key";
    public static final String KEY_SOUND = "push.bark.sound";
    public static final String KEY_USE_PROXY = "push.bark.use-proxy";

    /** 是否启用本通道。 */
    private volatile boolean enabled = false;

    /** Bark 服务器地址；官方公共服务器为 {@code https://api.day.app}，可改为自建。 */
    private volatile String server = "https://api.day.app";

    /** 设备 key（密钥；绝不写入日志）。 */
    private volatile String deviceKey = "";

    /** 可选提示音名（留空用 App 默认）。 */
    private volatile String sound = "";

    /** 是否走 HTTP 代理；Bark 通常国内直连，默认关闭。 */
    private volatile boolean useProxy = false;

    /** 打包成不可变设置快照，供 {@link BarkPushChannel} 请求时读取。 */
    public BarkSettings toSettings() {
        return new BarkSettings(server, deviceKey, sound, useProxy);
    }
}

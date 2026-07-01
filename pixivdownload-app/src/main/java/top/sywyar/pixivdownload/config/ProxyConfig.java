package top.sywyar.pixivdownload.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "proxy")
public class ProxyConfig implements OutboundProxySettings {

    /** config.yaml 中代理相关键名，供首次安装（setup / CLI）写入时复用。 */
    public static final String KEY_ENABLED = "proxy.enabled";
    public static final String KEY_HOST = "proxy.host";
    public static final String KEY_PORT = "proxy.port";

    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 7890;

    private volatile boolean enabled = true;
    private volatile String host = DEFAULT_HOST;
    private volatile int port = DEFAULT_PORT;
}

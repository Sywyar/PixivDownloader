package top.sywyar.pixivdownload.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "proxy")
public class ProxyConfig {
    private volatile boolean enabled = true;
    private volatile String host = "127.0.0.1";
    private volatile int port = 7890;
}

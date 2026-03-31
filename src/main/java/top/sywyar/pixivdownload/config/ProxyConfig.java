package top.sywyar.pixivdownload.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "proxy")
public class ProxyConfig {
    private boolean enabled = true;
    private String host = "127.0.0.1";
    private int port = 7890;
}

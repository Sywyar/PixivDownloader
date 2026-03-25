package top.sywyar.pixivdownload.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "proxy")
public class ProxyConfig {
    private boolean enabled = true;
    private String host = "127.0.0.1";
    private int port = 7890;
}

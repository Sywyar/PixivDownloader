package top.sywyar.pixivdownload.setup;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "setup")
public class SetupProperties {

    /** 每个 IP 每分钟最多允许的登录尝试次数（0 = 不限制） */
    private int loginRateLimitMinute = 10;
}

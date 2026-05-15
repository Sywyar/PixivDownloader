package top.sywyar.pixivdownload.maintenance;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "maintenance")
public class MaintenanceProperties {

    private volatile boolean enabled = true;
}

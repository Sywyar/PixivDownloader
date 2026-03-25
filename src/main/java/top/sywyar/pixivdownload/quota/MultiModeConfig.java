package top.sywyar.pixivdownload.quota;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "multi-mode")
public class MultiModeConfig {

    private Quota quota = new Quota();

    @Getter
    @Setter
    public static class Quota {
        /** 是否启用配额限制 */
        private boolean enabled = true;
        /** 每用户每周期最多下载作品数 */
        private int maxArtworks = 50;
        /** 配额重置周期（小时） */
        private int resetPeriodHours = 24;
        /** 压缩包下载链接有效时间（分钟） */
        private int archiveExpireMinutes = 60;
    }
}

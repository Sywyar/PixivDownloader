package top.sywyar.pixivdownload.download.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Setter
@Getter
@Configuration
@ConfigurationProperties(prefix = "download")
public class DownloadConfig {
    // getters and setters
    private String rootFolder = "pixiv-download";

    /**
     * User 模式下载目录结构：
     * false（默认）→ {rootFolder}/{username}/{artworkId}/
     * true          → {rootFolder}/{artworkId}/（与 N-Tab 相同）
     */
    private boolean userFlatFolder = false;

}
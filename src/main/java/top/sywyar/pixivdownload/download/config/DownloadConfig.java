package top.sywyar.pixivdownload.download.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "download")
public class DownloadConfig {
    private String rootFolder = "pixiv-download";

    public String getRootFolder() {
        return rootFolder == null ? "pixiv-download" : rootFolder.replaceAll("[/\\\\]+$", "");
    }

    /**
     * User 模式下载目录结构：
     * false（默认）→ {rootFolder}/{username}/{artworkId}/
     * true          → {rootFolder}/{artworkId}/（与 N-Tab 相同）
     */
    private boolean userFlatFolder = false;

}
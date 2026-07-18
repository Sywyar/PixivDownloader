package top.sywyar.pixivdownload.core.appconfig;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import top.sywyar.pixivdownload.config.DownloadSettings;

@Data
@Configuration
@ConfigurationProperties(prefix = "download")
public class DownloadConfig implements DownloadSettings {
    private volatile String rootFolder = "pixiv-download";

    public String getRootFolder() {
        return rootFolder == null ? "pixiv-download" : rootFolder.replaceAll("[/\\\\]+$", "");
    }

    /**
     * User 模式下载目录结构：
     * false（默认）→ {rootFolder}/{username}/{artworkId}/
     * true          → {rootFolder}/{artworkId}/（与批量导入单作品相同）
     */
    private volatile boolean userFlatFolder = false;

    /**
     * 同时下载的图片 / 作品数上限。图片下载任务跑在专用的 {@code downloadTaskExecutor} 线程池上，
     * 超出该上限的作品会排队等待。调小可降低被 Pixiv 限流的概率。
     * 线程池大小在启动时确定，修改后需重启服务才能生效。
     */
    private int maxConcurrent = 10;

    public int getMaxConcurrent() {
        return Math.max(1, maxConcurrent);
    }

}

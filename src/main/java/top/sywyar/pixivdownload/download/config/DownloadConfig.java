package top.sywyar.pixivdownload.download.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "download")
public class DownloadConfig {
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

    /**
     * 同时下载的小说数上限。小说下载任务跑在专用的 {@code novelDownloadTaskExecutor} 线程池上，
     * 与图片下载相互隔离、互不抢线程。线程池大小在启动时确定，修改后需重启服务才能生效。
     */
    private int novelMaxConcurrent = 10;

    public int getNovelMaxConcurrent() {
        return Math.max(1, novelMaxConcurrent);
    }

    /**
     * 「新下载小说自动翻译」的并发上限。翻译任务跑在专用的 {@code novelTranslateTaskExecutor} 线程池上，
     * 与图片 / 小说下载相互隔离；同一系列的章节按提交序串行翻译以保术语一致，不同系列 / 独立单章并发到此上限。
     * 线程池大小在启动时确定，修改后需重启服务才能生效。
     */
    private int novelTranslateMaxConcurrent = 10;

    public int getNovelTranslateMaxConcurrent() {
        return Math.max(1, novelTranslateMaxConcurrent);
    }

}

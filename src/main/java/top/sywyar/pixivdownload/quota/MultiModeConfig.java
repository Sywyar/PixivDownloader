package top.sywyar.pixivdownload.quota;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "multi-mode")
public class MultiModeConfig {

    private Quota quota = new Quota();

    /**
     * 下载后处理模式：
     * <ul>
     *   <li>pack-and-delete — 配额超出时打包并立即删除源文件（默认）</li>
     *   <li>never-delete    — 打包后保留源文件；再次请求同一作品视为已完成</li>
     *   <li>timed-delete    — 打包后保留源文件；超过 deleteAfterHours 后自动删除</li>
     * </ul>
     */
    private String postDownloadMode = "pack-and-delete";

    /** timed-delete 模式：作品下载完成后多少小时自动删除源文件 */
    private int deleteAfterHours = 72;

    @Data
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

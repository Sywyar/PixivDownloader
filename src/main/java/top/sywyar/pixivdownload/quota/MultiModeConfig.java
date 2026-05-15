package top.sywyar.pixivdownload.quota;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "multi-mode")
public class MultiModeConfig {

    private volatile Quota quota = new Quota();

    /**
     * 下载后处理模式：
     * <ul>
     *   <li>pack-and-delete — 配额超出时打包并立即删除源文件（默认）</li>
     *   <li>never-delete    — 打包后保留源文件；再次请求同一作品视为已完成</li>
     *   <li>timed-delete    — 打包后保留源文件；超过 deleteAfterHours 后自动删除</li>
     * </ul>
     */
    private volatile String postDownloadMode = "pack-and-delete";

    /** timed-delete 模式：作品下载完成后多少小时自动删除源文件 */
    private volatile int deleteAfterHours = 72;

    /** 多人模式下每用户每分钟最大请求次数（0 表示不限制） */
    private volatile int requestLimitMinute = 300;

    /** 多人模式游客每 IP 每分钟最大静态资源请求次数（0 表示不限制） */
    private volatile int staticResourceRequestLimitMinute = 1200;

    /** 搜索模式自动向后补页上限（0 表示不限制，仅多人模式生效） */
    private volatile int limitPage = 3;

    @Data
    public static class Quota {
        /** 是否启用配额限制 */
        private volatile boolean enabled = true;
        /** 每用户每周期最多下载作品数 */
        private volatile int maxArtworks = 50;
        /** 配额重置周期（小时） */
        private volatile int resetPeriodHours = 24;
        /** 压缩包下载链接有效时间（分钟） */
        private volatile int archiveExpireMinutes = 60;
        /** 单作品图片数上限（0=不限制）；超出后按 ceil(count/limitImage) 个作品计算配额 */
        private volatile int limitImage = 0;
        /** 每用户每重置周期最多发起的搜索/代理请求次数（0=不限制） */
        private volatile int maxProxyRequests = 200;
    }
}

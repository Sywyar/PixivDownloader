package top.sywyar.pixivdownload.core.appconfig;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import top.sywyar.pixivdownload.config.DownloadSettings;
import top.sywyar.pixivdownload.config.RuntimeFiles;

@Data
@Configuration
@ConfigurationProperties(prefix = "download")
public class DownloadConfig implements DownloadSettings {
    private volatile String rootFolder = "pixiv-download";

    public String getRootFolder() {
        return RuntimeFiles.normalizeRootFolder(rootFolder);
    }

    /**
     * User 模式下载目录结构：
     * false（默认）→ {rootFolder}/{username}/{artworkId}/
     * true          → {rootFolder}/{artworkId}/（与批量导入单作品相同）
     */
    private volatile boolean userFlatFolder = false;

    /**
     * 作品目录模板：渲染变量与文件名模板完全一致（{@code {artwork_id}} / {@code {artwork_title}} /
     * {@code {author_id}} / {@code {author_name}} / {@code {timestamp}} / {@code {page}} / {@code {count}} /
     * {@code {ai}} / {@code {ai+}} / {@code {R18}} / {@code {R18+}}），可用 {@code /} 分层，
     * 逐段做与文件名相同的安全清理，因此不可能越出下载根。
     *
     * <p>非空时作品直接落在 {@code {rootFolder}/{渲染结果}/}，<b>不再追加 {@code {artworkId}} 层级</b>，
     * 于是同一作者的作品会共用同一个目录。共享目录是应用已支持的结构：删除作品时按本作品的文件名
     * 前缀（stems）精确匹配，只会触碰本作品命名空间内的文件。
     *
     * <p>留空则保持内置结构 {@code {rootFolder}[/{username}[/R18G|R18]]/{artworkId}/}。
     */
    private volatile String artworkFolderTemplate = "";

    /**
     * 同时下载的图片 / 作品数上限。图片下载任务跑在专用的 {@code downloadTaskExecutor} 线程池上，
     * 超出该上限的作品最多排队 100 个，队列已满时拒绝新任务。调小可降低被 Pixiv 限流的概率。
     * 线程池大小在启动时确定，修改后需重启服务才能生效。
     */
    private int maxConcurrent = 10;

    public int getMaxConcurrent() {
        return Math.max(1, maxConcurrent);
    }

}

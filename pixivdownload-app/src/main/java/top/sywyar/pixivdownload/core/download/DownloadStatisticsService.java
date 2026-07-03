package top.sywyar.pixivdownload.core.download;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.core.download.response.StatisticsResponse;
import top.sywyar.pixivdownload.i18n.AppMessages;

/**
 * 下载累计统计的读写：递增 {@code statistics} 单行的已下载作品 / 图片计数，并读取总览。
 * <p>
 * {@code statistics} 事实表归核心，累计写入是每条下载路径（含单作品下载）的核心事实，
 * 因此本服务是核心 Spring 服务、不随统计仪表盘（stats 插件）启停而改变行为；
 * 统计仪表盘只读直查核心表，不承载这里的写入。委托池化的 {@link PixivDatabase}，不自写 SQL。
 */
@Slf4j
@Service
public class DownloadStatisticsService {

    private final PixivDatabase pixivDatabase;
    private final AppMessages messages;

    public DownloadStatisticsService(PixivDatabase pixivDatabase, AppMessages messages) {
        this.pixivDatabase = pixivDatabase;
        this.messages = messages;
    }

    /**
     * 把本次下载成功的图片数累加进统计单行。出错仅记日志、不向上抛出，
     * 避免翻转已成功的下载（与「部分页失败提前返回、不累计统计」的路径保持一致）。
     */
    public void recordStatistics(int count) {
        try {
            pixivDatabase.incrementStats(count);
        } catch (Exception e) {
            log.error(messages.getForLog("download.log.statistics.failed", e.getMessage()), e);
        }
    }

    public StatisticsResponse getStatistics() {
        int[] stats = pixivDatabase.getStats();
        return new StatisticsResponse(true, stats[0], stats[1], stats[2],
                messages.get("download.statistics.success"));
    }
}

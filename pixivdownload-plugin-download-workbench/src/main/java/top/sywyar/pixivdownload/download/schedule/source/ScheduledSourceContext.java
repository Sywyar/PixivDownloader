package top.sywyar.pixivdownload.download.schedule.source;

import com.fasterxml.jackson.databind.JsonNode;
import top.sywyar.pixivdownload.core.schedule.ScheduledTask;
import top.sywyar.pixivdownload.download.PixivFetchService;

import java.util.List;

/**
 * 调度壳暴露给来源 provider 的运行期上下文。来源只经本上下文读取任务参数、抓取 Pixiv，并把发现到的
 * 作品交给共享扫描驱动派发——发现 / 派发背后的作品级并发、限流、隔离重试、过度访问轮内检查、任务级代理、
 * 运行队列、水位线推进等共享调度机器全部封装在调度壳里，来源不接触。
 */
public interface ScheduledSourceContext {

    /** 当前运行的任务行。 */
    ScheduledTask task();

    /** 任务快照 params 的 {@code source} 子节点。 */
    JsonNode source();

    /** 本轮 cookie（受限 / 匿名时为 {@code null}）。 */
    String cookie();

    /** 本任务是否小说 kind。 */
    boolean novel();

    /** 抓取上限（{@code 0} = 不限 / 全量）。 */
    int fetchLimit();

    /** Pixiv 抓取服务。 */
    PixivFetchService fetch();

    /** 水位线 SEARCH 翻页前的强制礼貌延迟（10s）；供水位线 SEARCH 作为页间延迟传入。 */
    Runnable watermarkPageDelay();

    /**
     * 以 ID 水位线增量模式扫描派发：逐页取 ID（最新在前）、命中水位线即停、已下载跳过；首轮（水位线
     * 未建立）按 {@code fetchLimit} 封顶。扫描结束后 join 在途下载并推进水位线。
     *
     * @param pageDelay 翻页前的页间延迟（无延迟传 {@code () -> {}}）
     */
    void watermarkScan(PageSupplier pages, Runnable pageDelay) throws Exception;

    /** 以「翻页到底、命中第一个已下载即停」的边界模式扫描派发（页间延迟固定 10s、按 {@code fetchLimit} 每轮封顶）。 */
    void boundaryScan(PageSupplier pages) throws Exception;

    /** 以全量发现模式扫描派发（已下载免费跳过；{@code queueLimit>0} 时按每轮上限封顶，{@code <=0} 不限）。 */
    void fullScan(List<String> ids, int queueLimit) throws Exception;
}

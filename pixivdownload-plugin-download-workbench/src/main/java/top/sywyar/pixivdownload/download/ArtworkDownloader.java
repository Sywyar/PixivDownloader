package top.sywyar.pixivdownload.download;

import top.sywyar.pixivdownload.download.request.DownloadRequest;

import java.util.List;

/**
 * 单作品下载入口的窄接缝。
 *
 * <p>由 {@link ArtworkDownloadExecutor} 实现。让调度 / 自动化等上层功能只依赖这一个方法，
 * 而不必注入 {@code ArtworkDownloadExecutor} 本体（其依赖众多），既缩小耦合面又便于测试。
 * 默认入口仍是异步执行：实现会在调用线程先登记队列任务，再显式提交专用父执行器；调用方不应假设方法返回即下载完成。
 * 计划任务等需要严格等待落盘完成的后台流程使用 {@link #downloadImagesBlocking}。
 */
public interface ArtworkDownloader {

    /**
     * 异步下载单个作品的全部图片。语义与 {@link ArtworkDownloadExecutor#downloadImages} 完全一致。
     *
     * @param artworkId 作品 ID
     * @param title     作品标题（可为 Pixiv 原始标题，落库前会处理）
     * @param imageUrls 待下载的原图 URL 列表（动图场景由 {@code other} 内字段描述）
     * @param referer   请求 Referer（通常为 Pixiv 作品页）
     * @param other     下载附加参数（文件名模板、动图、收藏夹、R18 等）
     * @param cookie    本次下载使用的 Pixiv Cookie；可为 null（匿名 / 受限）
     * @param userUuid  多人模式访客 UUID；管理员 / solo 传 null（不计配额）
     */
    void downloadImages(Long artworkId, String title, List<String> imageUrls,
                        String referer, DownloadRequest.Other other, String cookie,
                        String userUuid);

    /**
     * 同步下载单个作品的全部图片，直到落盘、入库与后置 best-effort 动作结束后才返回。
     *
     * @return 服务完成且未进入顶层失败 / 取消分支时返回 {@code true}
     */
    boolean downloadImagesBlocking(Long artworkId, String title, List<String> imageUrls,
                                   String referer, DownloadRequest.Other other, String cookie,
                                   String userUuid);

    /**
     * 判定作品是否已下载（去重）。{@code verifyFiles=false} 时只查数据库记录；
     * {@code verifyFiles=true} 时还会做「实际目录检测」：磁盘缺文件则删陈旧记录视为未下载，
     * 数据库无记录但磁盘已有文件则补登记视为已下载（语义同
     * {@link DownloadedArtworkService#getDownloadedRecord(Long, boolean)}）。
     *
     * @return {@code true} 表示已下载、应跳过
     */
    boolean isArtworkDownloaded(long artworkId, boolean verifyFiles);
}

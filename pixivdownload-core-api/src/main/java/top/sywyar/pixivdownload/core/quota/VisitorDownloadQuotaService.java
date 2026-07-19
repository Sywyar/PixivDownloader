package top.sywyar.pixivdownload.core.quota;

import java.nio.file.Path;

/**
 * 多人模式游客下载配额与配额归档的核心语义端口。
 */
public interface VisitorDownloadQuotaService {

    /**
     * 按单件作品包含的媒体数量检查并预留配额；具体配额权重由宿主策略计算。
     * 文本作品传 {@code 1}。
     */
    VisitorDownloadQuotaReservation checkAndReserve(String ownerUuid, int mediaCount);

    /**
     * 为此前登记的成功下载目录创建异步归档，并返回归档任务 token。
     */
    String createArchive(String ownerUuid);

    /**
     * 仅在下载成功后登记产物目录，供后续配额归档使用。
     */
    void recordFolder(String ownerUuid, Path folder);
}

package top.sywyar.pixivdownload.core.work.model;

import java.nio.file.Path;
import java.util.List;

/**
 * 单个作品在本地磁盘上的资产概览：所在目录与各页实际存在的文件。
 *
 * <p>纯文件层视角，不参与查询层的软删除三态——已软删但磁盘文件尚存的作品照样可解析出资产。
 *
 * @param workType  媒体类型
 * @param workId    作品 ID
 * @param directory 作品文件所在目录（已重定位的作品为重定位后的目录）；下载记录中目录为空、
 *                  或资产 owner 无法安全解析其私有布局时为 {@code null}
 * @param pageCount 当前资产快照的文件计数，具体计算方式由资产 owner 定义
 * @param files     磁盘上实际存在的页文件，按页号升序；拥有稳定页号时保留该页号，
 *                  否则页号只是在当前快照内有效的临时索引
 */
public record LocalWorkAsset(
        WorkType workType,
        long workId,
        Path directory,
        int pageCount,
        List<WorkAssetFile> files) {

    public LocalWorkAsset {
        files = List.copyOf(files);
    }
}

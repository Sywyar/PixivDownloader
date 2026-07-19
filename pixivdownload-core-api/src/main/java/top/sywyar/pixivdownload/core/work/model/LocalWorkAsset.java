package top.sywyar.pixivdownload.core.work.model;

import top.sywyar.pixivdownload.core.work.service.WorkAssetService;

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
 *                  或小说独占目录守卫不通过时为 {@code null}
 * @param pageCount 插画为下载记录声明的页数（至少为 1）；小说为枚举到的文件数（可为 0）
 * @param files     磁盘上实际存在的页文件，按页号升序；插画缺页不占位，小说页号为枚举序号
 *                  （见 {@link WorkAssetService} 的小说资产语义）
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

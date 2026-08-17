package top.sywyar.pixivdownload.core.work.service;

import top.sywyar.pixivdownload.core.work.model.LocalWorkAsset;
import top.sywyar.pixivdownload.core.work.model.WorkAssetFile;
import top.sywyar.pixivdownload.core.work.model.WorkType;

import java.io.IOException;
import java.util.Optional;

/**
 * 本地资产核心接口：插件按 {@code (WorkType, workId)} 访问作品落在磁盘上的文件
 * （原图 / 缩略图 / 文件层删除），不再直接依赖下载侧实现类。
 *
 * <p>纯文件层视角，不参与查询层的软删除三态：作品行是否软删不影响文件解析，
 * 可见性判定由 {@link WorkVisibilityService}、存量三态由查询接口各自承担。
 *
 * <p>没有稳定页号的作品类型可以在单次 {@link #findAsset} 快照内使用从 0 开始的临时索引；
 * 调用方不得持久化该索引，也不得用它关联两次独立快照。本契约不承诺任何作品类型的物理目录、
 * 文件名、迁移方式或递归删除守卫，这些布局细节由实际资产 owner 保持私有。
 */
public interface WorkAssetService {

    /**
     * 解析单个作品的本地资产概览（目录、声明页数、各页实际存在的文件）。
     * 作品无下载记录时返回 {@link Optional#empty()}。
     *
     * @param workType 工作类型
     * @param workId 作品标识
     * @return 匹配的可选值
     */
    Optional<LocalWorkAsset> findAsset(WorkType workType, long workId);

    /**
     * 取指定页的缩略图文件（必要时生成并写入缩略图缓存）。
     * 作品不存在、页号越界或缩略图源不可得时返回 {@link Optional#empty()}。
     *
     * @throws IOException 缩略图生成 / 缓存写入失败
     * @param workType 工作类型
     * @param workId 作品标识
     * @param page 页码
     * @return 匹配的可选值
     */
    Optional<WorkAssetFile> thumbnail(WorkType workType, long workId, int page) throws IOException;

    /**
     * 取指定页的原始文件。作品不存在、页号越界或文件缺失时返回 {@link Optional#empty()}。
     *
     * @param workType 工作类型
     * @param workId 作品标识
     * @param page 页码
     * @return 满足条件时返回 {@code true}，否则返回 {@code false}
     */
    Optional<WorkAssetFile> rawFile(WorkType workType, long workId, int page);

    /**
     * 删除作品在磁盘上的全部留存文件（各页图片与缩略图、缩略图缓存、独占空目录）。
     *
     * @return {@code true} 表示所有尝试的删除都成功（含「没有可删的文件 / 无下载记录」），
     *         调用方可以继续数据库侧清理；{@code false} 表示有文件因锁定 / 权限不足等
     *         原因删除失败，调用方必须中止数据库清理以避免与磁盘状态不一致
     * @param workType 工作类型
     * @param workId 作品标识
     */
    boolean deleteLocalFiles(WorkType workType, long workId);

}

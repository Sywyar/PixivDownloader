package top.sywyar.pixivdownload.plugin.api;

import java.io.IOException;
import java.util.Optional;

/**
 * 本地资产核心接口：插件按 {@code (WorkType, workId)} 访问作品落在磁盘上的文件
 * （原图 / 缩略图 / 文件层删除），不再直接依赖下载侧实现类。
 *
 * <p>纯文件层视角，不参与查询层的软删除三态：作品行是否软删不影响文件解析，
 * 可见性判定由 {@link WorkVisibilityService}、存量三态由查询接口各自承担。
 *
 * <p>{@link WorkType#NOVEL} 尚未接入：小说本地文件仍由小说画廊侧自管，四个方法
 * 对 NOVEL 一律抛 {@link UnsupportedOperationException}，待小说画廊改走核心接口时
 * 接入并翻转契约单测。
 */
public interface WorkAssetService {

    /**
     * 解析单个作品的本地资产概览（目录、声明页数、各页实际存在的文件）。
     * 作品无下载记录时返回 {@link Optional#empty()}。
     */
    Optional<LocalWorkAsset> findAsset(WorkType workType, long workId);

    /**
     * 取指定页的缩略图文件（必要时生成并写入缩略图缓存）。
     * 作品不存在、页号越界或缩略图源不可得时返回 {@link Optional#empty()}。
     *
     * @throws IOException 缩略图生成 / 缓存写入失败
     */
    Optional<WorkAssetFile> thumbnail(WorkType workType, long workId, int page) throws IOException;

    /**
     * 取指定页的原始文件。作品不存在、页号越界或文件缺失时返回 {@link Optional#empty()}。
     */
    Optional<WorkAssetFile> rawFile(WorkType workType, long workId, int page);

    /**
     * 删除作品在磁盘上的全部留存文件（各页图片与缩略图、缩略图缓存、独占空目录）。
     *
     * @return {@code true} 表示所有尝试的删除都成功（含「没有可删的文件 / 无下载记录」），
     *         调用方可以继续数据库侧清理；{@code false} 表示有文件因锁定 / 权限不足等
     *         原因删除失败，调用方必须中止数据库清理以避免与磁盘状态不一致
     */
    boolean deleteLocalFiles(WorkType workType, long workId);
}

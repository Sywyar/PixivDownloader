package top.sywyar.pixivdownload.plugin.api.work.service;

import top.sywyar.pixivdownload.plugin.api.work.model.LocalWorkAsset;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkAssetFile;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkType;

import java.io.IOException;
import java.util.Optional;

/**
 * 本地资产核心接口：插件按 {@code (WorkType, workId)} 访问作品落在磁盘上的文件
 * （原图 / 缩略图 / 文件层删除），不再直接依赖下载侧实现类。
 *
 * <p>纯文件层视角，不参与查询层的软删除三态：作品行是否软删不影响文件解析，
 * 可见性判定由 {@link WorkVisibilityService}、存量三态由查询接口各自承担。
 *
 * <p><b>小说资产语义。</b>小说独占目录 {@code novel-{id}/} 下没有页概念，约定如下：
 * <ul>
 *   <li>{@link #findAsset}：目录守卫通过后枚举目录下全部常规文件，按路径字典序排序，
 *       {@code page} = 枚举序号（0 起）、{@code pageCount} = 文件数。<b>枚举序号是本次
 *       快照内的临时编号，不是持久标识</b>——不得持久化、不得跨两次 {@code findAsset}
 *       快照互相引用（与插画侧 Pixiv 真实页号语义不同）。</li>
 *   <li>{@link #thumbnail}：恒解析封面文件（{@code {存储基名}_thumb.{coverExt}}），
 *       {@code page} 参数无意义、被忽略，返回行的页号恒为 0。</li>
 *   <li>{@link #rawFile}：按 {@link #findAsset} 同一枚举序号取第 {@code page} 个文件。</li>
 *   <li>{@link #deleteLocalFiles}：仅当目录通过独占性守卫（目录名等于
 *       {@code novel-{id}}、非文件系统根 / 非 {@code download.root-folder} 本身）才递归
 *       删除；守卫不通过仅记日志并视为「无事可做」。</li>
 * </ul>
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

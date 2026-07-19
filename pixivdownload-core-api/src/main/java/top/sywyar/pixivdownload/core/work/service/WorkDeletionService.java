package top.sywyar.pixivdownload.core.work.service;

import top.sywyar.pixivdownload.core.work.model.WorkType;

import java.util.Collection;

/**
 * 作品删除核心接口：插件按 {@code (WorkType, workId)} 触发统一删除编排——
 * 判存 → 删磁盘文件 → 文件全删成功后清理派生 / 关联数据（标签关联、收藏夹关联等）并软删主行
 * （主行保留并置 {@code deleted = 1}），使下载判重能识别「已下载过，但被删除」、避免被当作未下载重新下载。
 *
 * <p>编排顺序封装在核心实现里、不可被乱序调用：本接口只暴露统一删除入口，
 * 不再暴露「跳过删文件直接软删 DB」这类低层步骤。磁盘文件删除归
 * {@link WorkAssetService#deleteLocalFiles}，由本接口在删 DB 之前调用。
 */
public interface WorkDeletionService {

    /**
     * 删除单个作品（唯一对外删除入口）：判存 → 删磁盘文件 → 软删 DB 主行，顺序固定。
     *
     * @return {@code true} 删除成功（文件已全删 + DB 已软删）；{@code false} 作品不存在或已软删
     * @throws WorkDeletionException 磁盘文件未能全部删除，数据库未触碰
     */
    boolean delete(WorkType workType, long workId);

    /**
     * 批量删除：对去重后的每个 id 逐个 {@link #delete}，单个失败只记日志不中断，返回实际删除数。
     */
    int deleteAll(WorkType workType, Collection<Long> workIds);
}

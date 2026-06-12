package top.sywyar.pixivdownload.plugin.api;

/**
 * 作品删除核心接口：插件按 {@code (WorkType, workId)} 触发数据库侧的软删除——
 * 派生 / 关联数据（标签关联、收藏夹关联等）照常清理，主行保留并置 {@code deleted = 1}，
 * 使下载判重能识别「已下载过，但被删除」、避免被当作未下载重新下载。
 *
 * <p>只覆盖数据库侧：磁盘文件删除归 {@link WorkAssetService#deleteLocalFiles}。
 * 调用方必须先删文件，文件删除失败时中止本接口调用，避免 DB 与磁盘状态不一致出现孤儿文件。
 */
public interface WorkDeletionService {

    /**
     * 清理派生 / 关联数据并标记软删除（主行保留）。对不存在的作品为无操作；
     * 重新下载成功后由下载落库路径替换主行、标记自动复位。
     */
    void markDeleted(WorkType workType, long workId);
}

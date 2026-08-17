package top.sywyar.pixivdownload.core.collection;

import top.sywyar.pixivdownload.core.work.model.WorkType;

/**
 * 把作品加入核心收藏夹的语义端口。
 */
public interface WorkCollectionMembership {

    /**
     * @return 新增了作品与收藏夹关系时为 {@code true}；关系已存在时为 {@code false}
     * @param workType 工作类型
     * @param collectionId 合集标识
     * @param workId 作品标识
     */
    boolean addWork(WorkType workType, long collectionId, long workId);
}

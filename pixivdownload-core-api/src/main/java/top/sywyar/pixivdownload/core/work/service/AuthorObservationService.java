package top.sywyar.pixivdownload.core.work.service;

/**
 * 记录作者事实的核心写入端口。
 *
 * <p>实现可以用提示名称创建缺失作者，并按自身策略校验已有作者的名称变化。</p>
 */
public interface AuthorObservationService {

    /**
     * 执行对应操作。
     *
     * @param authorId 作者标识
     * @param hintName 提示名称
     */
    void observe(long authorId, String hintName);
}

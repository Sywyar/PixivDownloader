package top.sywyar.pixivdownload.core.work.service;

import top.sywyar.pixivdownload.core.work.model.WorkMetadata;
import top.sywyar.pixivdownload.core.work.model.WorkType;

import java.util.List;
import java.util.Optional;

/**
 * 作品元数据核心接口：插件按 {@code (WorkType, workId)} 取作品的跨来源中性元数据视图
 * （规范化 meta + 本地文件记录 + 作者名 / 系列标题 / 标签补全），存储形态藏在接口后。
 *
 * <p><b>软删除语义。</b>两个读取方法默认过滤软删除行：软删作品视为不存在
 * （返回 {@link Optional#empty()} / 不出现在结果中）；「曾经下载过（含软删）」判定走
 * {@link WorkQueryService#hasWork}。
 *
 * <p><b>批量契约。</b>{@link #findAll} 的行读取与各通用关联补全（作者名 / 系列标题 /
 * 标签 / 文件名模板）必须按入参整体批量执行，禁止退化为每 id 一查的 N+1。
 * 来源插件私有详情由对应 owner 在插件内部补全，不进入本中性契约。
 */
public interface WorkMetadataRepository {

    /** 取单个作品的元数据；无记录或已软删除时返回 {@link Optional#empty()}。 */
    Optional<WorkMetadata> find(WorkType workType, long workId);

    /**
     * 批量取作品元数据。<b>返回顺序与传入 id 顺序一致</b>（排序是查询侧的职责，
     * 本方法不得打乱）；无记录或已软删除的 id 直接跳过，不占位。
     */
    List<WorkMetadata> findAll(WorkType workType, List<Long> workIds);
}

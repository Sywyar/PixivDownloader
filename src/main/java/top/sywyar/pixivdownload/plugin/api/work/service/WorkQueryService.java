package top.sywyar.pixivdownload.plugin.api.work.service;

import top.sywyar.pixivdownload.plugin.api.work.model.PagedResult;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkSummary;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkType;
import top.sywyar.pixivdownload.plugin.api.work.query.AuthorQuery;
import top.sywyar.pixivdownload.plugin.api.work.query.AuthorSummary;
import top.sywyar.pixivdownload.plugin.api.work.query.SeriesNeighbors;
import top.sywyar.pixivdownload.plugin.api.work.query.SeriesQuery;
import top.sywyar.pixivdownload.plugin.api.work.query.SeriesSummary;
import top.sywyar.pixivdownload.plugin.api.work.query.TagOption;
import top.sywyar.pixivdownload.plugin.api.work.query.TagQuery;
import top.sywyar.pixivdownload.plugin.api.work.query.WorkQuery;

import java.util.List;
import java.util.Optional;

/**
 * 作品列表查询核心接口：插件按统一查询条件取作品 id 序列与目录类聚合
 * （标签 / 作者 / 系列），不再直接依赖画廊 / 小说侧的 SQL 仓库与数据库类。
 *
 * <p><b>软删除三态。</b>所有列表 / 目录 / 关联查询默认过滤软删除行（{@code deleted = 1}
 * 视为不存在）；判重语义单独成对暴露——{@link #hasWork}（含软删，「曾经下载过」与
 * 重新下载判定）与 {@link #hasActiveWork}（画廊可见），与底层
 * {@code hasArtwork / hasActiveArtwork} 对齐。
 *
 * <p>{@code restriction == null} 表示无访客限制（管理员 / 非访客）：目录类查询统计全部
 * 未删除行——「无限制」只豁免访客可见性投影，不豁免软删除过滤。
 */
public interface WorkQueryService {

    /**
     * 按查询条件取一页作品 id（默认过滤软删除），顺序即排序结果；
     * 内容经 {@link WorkMetadataRepository#findAll} 批量补全。
     */
    PagedResult<WorkSummary> search(WorkQuery query);

    /** 同 {@link #search} 的全量版本：返回命中条件的全部作品 id，不分页。 */
    List<WorkSummary> searchAll(WorkQuery query);

    /** 是否存在下载记录（<b>含软删除行</b>）：「曾经下载过」与重新下载判定用。 */
    boolean hasWork(WorkType workType, long workId);

    /** 是否存在未被软删除的记录：软删行视为不存在（画廊可见性判定用）。 */
    boolean hasActiveWork(WorkType workType, long workId);

    /** 相关作品：与给定作品共享至少一个标签，按共享标签数降序、时间倒序。 */
    List<WorkSummary> relatedByTags(WorkType workType, long workId, int limit);

    /** 同作者的其他作品，按时间倒序，排除 {@code excludeWorkId} 自身。 */
    List<WorkSummary> byAuthor(WorkType workType, long authorId, long excludeWorkId, int limit);

    /** 同系列的其他作品，按系列内序号升序，排除 {@code excludeWorkId} 自身。 */
    List<WorkSummary> bySeries(WorkType workType, long seriesId, long excludeWorkId, int limit);

    /** 系列内相邻作品导航；作品不存在、无系列或无序号时返回 {@link Optional#empty()}。 */
    Optional<SeriesNeighbors> seriesNeighbors(WorkType workType, long workId);

    /**
     * 标签目录（带使用计数）。{@code restriction} 非空时只返回对该访客可见的标签；
     * 计数语义沿用既有查询（插画侧为全量使用数 + 可见性过滤，小说侧为可见作品计数）。
     */
    List<TagOption> tags(TagQuery query);

    /** 按名称 / 翻译名精确查找标签（大小写不敏感，原名命中优先）。 */
    Optional<TagOption> tagByName(WorkType workType, String name, String translatedName);

    /** 作者目录（带可见作品计数），作者名按作者池补全、缺名以 id 字符串兜底。 */
    List<AuthorSummary> authors(AuthorQuery query);

    /** 系列目录（带可见作品计数），标题 / 作者按系列池补全、缺行以 id 字符串兜底。 */
    List<SeriesSummary> series(SeriesQuery query);
}

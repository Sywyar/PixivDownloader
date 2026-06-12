package top.sywyar.pixivdownload.plugin.api;

/**
 * 列表查询命中的作品标识行。只携带 {@code (workType, workId)}，排序由查询结果的
 * 顺序表达；完整内容经 {@link WorkMetadataRepository#findAll} 按 id 顺序批量取得。
 */
public record WorkSummary(WorkType workType, long workId) {
}

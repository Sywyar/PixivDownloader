package top.sywyar.pixivdownload.plugin.api.work.model;

import java.util.List;

/**
 * 单个作品的跨来源中性元数据视图：规范化 meta + 本地文件记录 + 已补全的通用关联
 * 展示字段（作者名 / 系列标题 / 标签）。来源插件私有详情不进入本类型；存储形态藏在
 * {@link WorkMetadataRepository} 接口后。
 *
 * <p>本类型只经默认过滤软删除的读取方法产出（软删行不可见），故不携带 deleted 标记；
 * 「曾经下载过（含软删）」判定走 {@link WorkQueryService#hasWork}。
 *
 * @param workType           媒体类型
 * @param workId             作品 id
 * @param title              标题
 * @param description        简介，可为 {@code null}
 * @param xRestrict          年龄分级（0 = SFW，1 = R-18，2 = R-18G），历史数据可为 {@code null}
 * @param isAi               AI 生成标记，可为 {@code null}
 * @param authorId           作者 id，可为 {@code null}
 * @param authorName         作者名（按作者池补全），缺名时为 {@code null}
 * @param seriesId           系列 id（底层原值，{@code <= 0} 表示无系列），可为 {@code null}
 * @param seriesOrder        系列内序号，可为 {@code null}
 * @param seriesTitle        系列标题（仅 {@code seriesId > 0} 时补全），缺行时为 {@code null}
 * @param tags               作品标签（防御性拷贝，不可变）
 * @param downloadTime       下载落库时间（毫秒）
 * @param pageCount          本地文件计数（插画 = 图片页数；小说沿用 {@code count} 列语义）
 * @param extensions         文件扩展名记录
 * @param folder             作品目录（已解析路径前缀），可为 {@code null}
 * @param moved              是否已移动（小说侧无移动语义，恒为 {@code false}）
 * @param moveFolder         移动目标目录，可为 {@code null}
 * @param moveTime           移动时间（毫秒），可为 {@code null}
 * @param fileNameTemplateId 文件名模板 id（底层原值），可为 {@code null}
 * @param fileNameTemplate   文件名模板内容（插画侧沿用「{@code null} id 取默认模板 1」的既有规则补全）
 * @param fileAuthorNameId   文件名作者名 id，可为 {@code null}
 * @param uploadTime         Pixiv 真实上传时间（epoch 毫秒，区别于 {@link #downloadTime} 的下载落库时间），
 *                           历史数据未捕获时为 {@code null}（源 illust {@code uploadDate} / novel {@code uploadTimestamp}）
 * @param isOriginal         原创标记三态：{@code true}/{@code false}/{@code null}（NULL = 未知，区别于显式 false）
 */
public record WorkMetadata(
        WorkType workType,
        long workId,
        String title,
        String description,
        Integer xRestrict,
        Boolean isAi,
        Long authorId,
        String authorName,
        Long seriesId,
        Long seriesOrder,
        String seriesTitle,
        List<WorkTag> tags,
        long downloadTime,
        int pageCount,
        String extensions,
        String folder,
        boolean moved,
        String moveFolder,
        Long moveTime,
        Long fileNameTemplateId,
        String fileNameTemplate,
        Long fileAuthorNameId,
        Long uploadTime,
        Boolean isOriginal) {

    public WorkMetadata {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}

package top.sywyar.pixivdownload.core.work.model;

import top.sywyar.pixivdownload.core.work.service.WorkMetadataRepository;
import top.sywyar.pixivdownload.core.work.service.WorkQueryService;

import java.util.List;

/**
 * 单个作品的跨来源中性元数据视图：规范化 meta + 本地文件记录 + 已补全的通用关联
 * 展示字段（作者名 / 系列标题 / 标签）。来源插件私有详情不进入本类型；存储形态藏在
 * {@link WorkMetadataRepository} 接口后。
 *
 * <p>本类型只经默认过滤软删除的读取方法产出（软删行不可见），故不携带 deleted 标记；
 * 「曾经下载过（含软删）」判定走 {@link WorkQueryService#hasWork}。
 *
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
 * @param pageCount          当前来源投影声明的本地文件计数
 * @param extensions         文件扩展名记录
 * @param folder             作品目录（已解析路径前缀），可为 {@code null}
 * @param moved              本地资产是否处于已移动状态
 * @param moveFolder         移动目标目录，可为 {@code null}
 * @param moveTime           移动时间（毫秒），可为 {@code null}
 * @param fileNameTemplateRef 文件名模板的中性引用，可为 {@code null}；目录键只是不透明兼容值
 * @param uploadTime          来源提供的真实发布时间（epoch 毫秒，区别于 {@link #downloadTime} 的下载落库时间），
 *                            未捕获时为 {@code null}
 * @param isOriginal          原创标记三态：{@code true}/{@code false}/{@code null}（NULL = 未知，区别于显式 false）
 */
public record WorkMetadata(
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
        WorkFileNameTemplateRef fileNameTemplateRef,
        Long uploadTime,
        Boolean isOriginal) {

    /**
     * 创建 {@code WorkMetadata} 实例。
     *
     * @param workId 作品标识
     * @param title 标题
     * @param description 描述
     * @param xRestrict {@code xRestrict} 对应的值
     * @param isAi {@code isAi} 对应的值
     * @param authorId 作者标识
     * @param authorName 作者名称
     * @param seriesId 系列标识
     * @param seriesOrder 系列顺序
     * @param seriesTitle 系列标题
     * @param tags 标签集合
     * @param downloadTime 下载时间
     * @param pageCount 页码数量
     * @param extensions 扩展列表
     * @param folder 目录
     * @param moved 移动数量
     * @param moveFolder {@code moveFolder} 对应的值
     * @param moveTime {@code moveTime} 对应的值
     * @param fileNameTemplateRef {@code fileNameTemplateRef} 对应的值
     * @param uploadTime 上传时间
     * @param isOriginal {@code isOriginal} 对应的值
     */
    public WorkMetadata {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}

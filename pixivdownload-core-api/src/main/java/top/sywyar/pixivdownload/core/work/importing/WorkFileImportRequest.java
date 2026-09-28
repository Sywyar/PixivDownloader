package top.sywyar.pixivdownload.core.work.importing;

import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.core.work.model.WorkTag;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * 完整作品的原文件登记请求；缺失元数据不可推断成非敏感或非 AI。
 * @param workType 作品身份类型
 * @param workId 正数作品 ID
 * @param title 已知标题，最多 1000 字符
 * @param pageCount 完整图片页数，小说与动图为 1
 * @param restriction 已知限制级别 0、1 或 2
 * @param aiGenerated 已知 AI 标记
 * @param authorId 正数作者 ID
 * @param authorName 可空作者名称，最多 1000 字符
 * @param description 可空简介，最多 32768 字符
 * @param seriesId 可空正数系列 ID
 * @param seriesOrder 可空非负系列顺序
 * @param tags 已知标签，最多 256 个
 * @param sourceRoot 由可信调用方批准的绝对源目录，不能由未授权网络输入指定
 * @param pageFiles 按页排列的完整源文件路径，不能包含重复或越界路径
 * @param novelContent 小说正文，最多 3000000 字符；插画为空
 */
public record WorkFileImportRequest(WorkType workType, long workId, String title, int pageCount,
        Integer restriction, Boolean aiGenerated, Long authorId, String authorName, String description,
        Long seriesId, Long seriesOrder, List<WorkTag> tags, Path sourceRoot, List<Path> pageFiles,
        String novelContent) {
    /** 校验元数据与集合边界；磁盘校验由宿主完成。
     * @param workType 作品身份类型
     * @param workId 正数作品 ID
     * @param title 已知标题，最多 1000 字符
     * @param pageCount 完整图片页数，小说与动图为 1
     * @param restriction 已知限制级别 0、1 或 2
     * @param aiGenerated 已知 AI 标记
     * @param authorId 正数作者 ID
     * @param authorName 可空作者名称，最多 1000 字符
     * @param description 可空简介，最多 32768 字符
     * @param seriesId 可空正数系列 ID
     * @param seriesOrder 可空非负系列顺序
     * @param tags 已知标签，最多 256 个
     * @param sourceRoot 由可信调用方批准的绝对源目录，不能由未授权网络输入指定
     * @param pageFiles 按页排列的完整源文件路径，不能包含重复或越界路径
     * @param novelContent 小说正文，最多 3000000 字符；插画为空
     */
    public WorkFileImportRequest {
        Objects.requireNonNull(workType);
        if (workId <= 0 || title == null || title.isBlank() || title.length() > 1000
                || pageCount < 1 || pageCount > 1000 || restriction == null
                || restriction < 0 || restriction > 2 || aiGenerated == null
                || authorId == null || authorId <= 0
                || authorName != null && authorName.length() > 1000
                || description != null && description.length() > 32768
                || seriesId != null && seriesId <= 0
                || seriesOrder != null && (seriesId == null || seriesOrder < 0)
                || workType == WorkType.NOVEL && (pageCount != 1 || novelContent == null
                    || novelContent.length() > 3_000_000)
                || workType == WorkType.ARTWORK && novelContent != null) {
            throw new IllegalArgumentException("INVALID_WORK_METADATA");
        }
        Objects.requireNonNull(sourceRoot);
        if (!sourceRoot.isAbsolute()) throw new IllegalArgumentException("ABSOLUTE_SOURCE_ROOT_REQUIRED");
        tags = List.copyOf(tags);
        if (tags.size() > 256 || tags.stream().anyMatch(tag -> tag.name() == null
                || tag.name().isBlank() || tag.name().length() > 512
                || tag.translatedName() != null && tag.translatedName().length() > 512)) {
            throw new IllegalArgumentException("INVALID_TAGS");
        }
        pageFiles = List.copyOf(pageFiles);
        if (pageFiles.size() != pageCount || pageFiles.stream().distinct().count() != pageCount) {
            throw new IllegalArgumentException("INCOMPLETE_PAGE_SET");
        }
    }
}

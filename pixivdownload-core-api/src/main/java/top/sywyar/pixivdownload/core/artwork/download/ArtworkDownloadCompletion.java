package top.sywyar.pixivdownload.core.artwork.download;

import top.sywyar.pixivdownload.core.work.model.WorkTag;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 已完成插画下载向核心历史提交的稳定事实。
 *
 * <p>本类型不是数据库行模型；路径编码、标签池映射、文件名目录的持久化标识和具体持久化形态
 * 均由宿主适配器负责。</p>
 *
 * @param artworkId            插画 id
 * @param title                下载时标题
 * @param folder               已完成作品目录
 * @param imageCount           成功写入的图片数
 * @param extensions           已写入文件扩展名集合
 * @param recordTime           下载记录时间（epoch 毫秒）
 * @param restriction          年龄分级（0 = SFW，1 = R-18，2 = R-18G）
 * @param aiGenerated          是否为 AI 生成作品
 * @param authorId             作者 id，可为 {@code null}
 * @param description          已规范化的作品简介，可为 {@code null}
 * @param fileNameTemplate     下载时采用的文件名模板
 * @param normalizedAuthorName 文件名使用的规范化作者名，可为 {@code null}
 * @param seriesId             系列 id，可为 {@code null}
 * @param seriesOrder          系列内序号，可为 {@code null}
 * @param tags                 下载时携带的标签
 */
public record ArtworkDownloadCompletion(
        long artworkId,
        String title,
        Path folder,
        int imageCount,
        Set<String> extensions,
        long recordTime,
        int restriction,
        boolean aiGenerated,
        Long authorId,
        String description,
        String fileNameTemplate,
        String normalizedAuthorName,
        Long seriesId,
        Long seriesOrder,
        List<WorkTag> tags) {

    /**
     * 创建 {@code ArtworkDownloadCompletion} 实例。
     *
     * @param artworkId 插画作品标识
     * @param title 标题
     * @param folder 目录
     * @param imageCount 图片数量
     * @param extensions 扩展列表
     * @param recordTime 记录时间
     * @param restriction 访问限制
     * @param aiGenerated AI生成状态
     * @param authorId 作者标识
     * @param description 描述
     * @param fileNameTemplate 文件名称模板
     * @param normalizedAuthorName 规范化值作者名称
     * @param seriesId 系列标识
     * @param seriesOrder 系列顺序
     * @param tags 标签集合
     */
    public ArtworkDownloadCompletion {
        folder = Objects.requireNonNull(folder, "folder");
        fileNameTemplate = Objects.requireNonNull(fileNameTemplate, "fileNameTemplate");
        normalizedAuthorName = normalizedAuthorName == null || normalizedAuthorName.isBlank()
                ? null
                : normalizedAuthorName;
        extensions = extensions == null
                ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(extensions));
        tags = tags == null ? List.of() : tags.stream()
                .filter(Objects::nonNull)
                .toList();
    }
}

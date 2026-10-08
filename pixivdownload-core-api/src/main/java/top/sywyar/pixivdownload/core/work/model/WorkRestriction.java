package top.sywyar.pixivdownload.core.work.model;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 访客查询/单作品访问的限制条件，按媒体类型（{@link WorkType}）派生后的投影。
 *
 * <p>判定分两阶段：受限维度先排除任何未列入白名单的标签或作者；通过排除后，标签命中
 * {@code tagIds} <b>或</b> 作者命中 {@code authorIds} 即可见。{@code tagUnrestricted} /
 * {@code authorUnrestricted} 为 {@code true} 表示该维度不参与排除并直接满足 OR。
 *
 * @param allowedXRestricts 允许的年龄分级集合（0 = SFW，1 = R-18，2 = R-18G）
 */
public record WorkRestriction(
        Set<Integer> allowedXRestricts,
        boolean tagUnrestricted,
        List<Long> tagIds,
        boolean authorUnrestricted,
        List<Long> authorIds,
        boolean collectionUnrestricted,
        List<Long> collectionIds,
        boolean collectionRestrictsWorks) {

    /**
     * 未配置收藏夹限制的调用保留原有可见范围。
     * @param allowedXRestricts 允许的年龄分级
     * @param tagUnrestricted 标签是否不限
     * @param tagIds 可见标签
     * @param authorUnrestricted 作者是否不限
     * @param authorIds 可见作者
     */
    public WorkRestriction(Set<Integer> allowedXRestricts, boolean tagUnrestricted,
                           List<Long> tagIds, boolean authorUnrestricted, List<Long> authorIds) {
        this(allowedXRestricts, tagUnrestricted, tagIds, authorUnrestricted, authorIds,
                true, List.of(), false);
    }

    /**
     * 创建 {@code WorkRestriction} 实例。
     *
     * @param allowedXRestricts {@code allowedXRestricts} 对应的值
     * @param tagUnrestricted 标签不受限状态
     * @param tagIds 标签标识集合
     * @param authorUnrestricted 作者不受限状态
     * @param authorIds 作者标识集合
     * @param collectionUnrestricted 收藏夹入口是否不受限
     * @param collectionIds 可见收藏夹标识集合
     * @param collectionRestrictsWorks 是否同时排除属于任一不可见收藏夹的作品（未收藏作品不受影响）
     */
    public WorkRestriction {
        allowedXRestricts = Set.copyOf(Objects.requireNonNull(
                allowedXRestricts, "allowedXRestricts"));
        tagIds = List.copyOf(Objects.requireNonNull(tagIds, "tagIds"));
        authorIds = List.copyOf(Objects.requireNonNull(authorIds, "authorIds"));
        collectionIds = List.copyOf(Objects.requireNonNull(collectionIds, "collectionIds"));
    }

    /**
     * 收藏夹入口、筛选与归属标记使用同一可见集合。
     * @param collectionId 收藏夹标识
     * @return 收藏夹是否可见；作品可见性还须检查其它限制维度
     */
    public boolean isCollectionVisible(long collectionId) {
        return collectionUnrestricted || collectionIds.contains(collectionId);
    }

    /**
     * 标签与作者两个维度均无限制。
     *
     * @return 满足条件时返回 {@code true}，否则返回 {@code false}
     */
    public boolean fullyOpen() {
        return tagUnrestricted && authorUnrestricted;
    }
}

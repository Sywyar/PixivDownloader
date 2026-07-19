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
        List<Long> authorIds) {

    public WorkRestriction {
        allowedXRestricts = Set.copyOf(Objects.requireNonNull(
                allowedXRestricts, "allowedXRestricts"));
        tagIds = List.copyOf(Objects.requireNonNull(tagIds, "tagIds"));
        authorIds = List.copyOf(Objects.requireNonNull(authorIds, "authorIds"));
    }

    /** 标签与作者两个维度均无限制。 */
    public boolean fullyOpen() {
        return tagUnrestricted && authorUnrestricted;
    }
}

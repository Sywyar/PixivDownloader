package top.sywyar.pixivdownload.plugin.api.work.model;

import java.util.List;
import java.util.Set;

/**
 * 访客查询/单作品访问的限制条件，按媒体类型（{@link WorkType}）派生后的投影。
 *
 * <p>OR 语义：作品任一标签命中 {@code tagIds} <b>或</b> 作者命中 {@code authorIds} 即可见；
 * {@code tagUnrestricted} / {@code authorUnrestricted} 为 {@code true} 表示该维度无限制。
 *
 * @param allowedXRestricts 允许的年龄分级集合（0 = SFW，1 = R-18，2 = R-18G）
 */
public record WorkRestriction(
        Set<Integer> allowedXRestricts,
        boolean tagUnrestricted,
        List<Long> tagIds,
        boolean authorUnrestricted,
        List<Long> authorIds) {

    /** 标签与作者两个维度均无限制。 */
    public boolean fullyOpen() {
        return tagUnrestricted && authorUnrestricted;
    }
}

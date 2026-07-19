package top.sywyar.pixivdownload.core.work.model;

import java.util.Objects;

/**
 * 当前调用方的作品可见性作用域。
 *
 * <p>非受限调用方不携带查询限制；受邀访客同时携带插画与小说两套限制。显式的
 * {@code enforceVisibility} 不能由 {@link WorkRestriction#fullyOpen()} 替代：即使访客的标签与作者
 * 维度均开放，单作品访问仍须校验作品存在性和年龄分级。
 */
public record WorkVisibilityScope(
        boolean enforceVisibility,
        WorkRestriction artworkRestriction,
        WorkRestriction novelRestriction) {

    private static final WorkVisibilityScope UNRESTRICTED =
            new WorkVisibilityScope(false, null, null);

    public WorkVisibilityScope {
        if (enforceVisibility) {
            Objects.requireNonNull(artworkRestriction, "artworkRestriction");
            Objects.requireNonNull(novelRestriction, "novelRestriction");
        } else if (artworkRestriction != null || novelRestriction != null) {
            throw new IllegalArgumentException("Unrestricted scope must not carry restrictions");
        }
    }

    /** 非受邀访客调用方的共享无限制作用域。 */
    public static WorkVisibilityScope unrestricted() {
        return UNRESTRICTED;
    }

    /** 创建需要逐作品校验的受邀访客作用域。 */
    public static WorkVisibilityScope restricted(
            WorkRestriction artworkRestriction,
            WorkRestriction novelRestriction) {
        return new WorkVisibilityScope(true, artworkRestriction, novelRestriction);
    }

    /** 返回指定作品类型的查询限制；无限制作用域返回 {@code null}。 */
    public WorkRestriction restrictionFor(WorkType workType) {
        Objects.requireNonNull(workType, "workType");
        if (!enforceVisibility) {
            return null;
        }
        return switch (workType) {
            case ARTWORK -> artworkRestriction;
            case NOVEL -> novelRestriction;
        };
    }
}

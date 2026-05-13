package top.sywyar.pixivdownload.setup.guest;

import java.util.Set;

/**
 * 一次成功兑换的访客邀请快照，挂在请求上下文中供后续过滤/守卫使用。
 *
 * <p>白名单按媒体类型分两套：{@code tagIds}/{@code authorIds} 仅作用于插画与漫画（{@code artworks}），
 * {@code novelTagIds}/{@code novelAuthorIds} 仅作用于小说。两套白名单各自独立的 OR 语义：
 * 在同一媒体类型内，"标签命中" 或 "作者命中" 即视为可见；
 * 对应 {@code *Unrestricted=true} 时该维度不受限制，ID 集合应当为空。
 */
public record GuestInviteSession(
        long id,
        String code,
        boolean allowSfw,
        boolean allowR18,
        boolean allowR18g,
        boolean tagUnrestricted,
        Set<Long> tagIds,
        boolean authorUnrestricted,
        Set<Long> authorIds,
        boolean novelTagUnrestricted,
        Set<Long> novelTagIds,
        boolean novelAuthorUnrestricted,
        Set<Long> novelAuthorIds) {

    public static final String REQUEST_ATTR = "guestInvite";

    /**
     * 命中（任意成员为 true）即可证明 R-18 维度对该访客有非空允许集合。
     */
    public boolean hasAnyAgeRating() {
        return allowSfw || allowR18 || allowR18g;
    }
}

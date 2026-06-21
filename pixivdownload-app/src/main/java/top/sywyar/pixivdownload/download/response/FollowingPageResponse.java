package top.sywyar.pixivdownload.download.response;

import java.util.List;

/**
 * /ajax/user/{uid}/following 的代理响应：当前页关注用户列表 + 总数 + 偏移信息。
 */
public record FollowingPageResponse(
        List<FollowingUser> users,
        int total,
        int offset,
        int limit
) {
    public record FollowingUser(
            String userId,
            String userName,
            String profileImageUrl,
            String comment
    ) {
    }
}

package top.sywyar.pixivdownload.download.response;

import java.util.List;

/**
 * /ajax/follow_latest/illust 的代理响应：已关注的用户的新作（当前页）+ 页码 + 是否还有下一页。
 * Pixiv 该接口不返回作品总数，故以 {@code hasNext} 驱动前端「下一页」按钮的可用性。
 */
public record FollowLatestResponse(
        List<SearchResponse.SearchItem> items,
        int page,
        boolean hasNext
) {
}

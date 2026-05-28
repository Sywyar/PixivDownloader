package top.sywyar.pixivdownload.download.response;

import java.util.List;

/**
 * /ajax/collection/{collectionId} 的代理响应：单个珍藏集内部包含的作品（按珍藏集布局顺序）。
 * 珍藏集可同时含插画与小说，故为混合列表，每项以 {@code kind} 区分（"illust" / "novel"）。
 * Pixiv 该接口一次返回全部作品、无分页。
 */
public record CollectionWorksResponse(
        List<Work> works,
        int total
) {
    public record Work(
            String kind,        // "illust" | "novel"
            String id,
            String title,
            int illustType,
            int xRestrict,
            int aiType,
            String thumbnailUrl,
            int pageCount,
            String userId,
            String userName,
            List<String> tags,
            int bookmarkCount,
            int wordCount,
            int textLength,
            boolean isOriginal
    ) {
    }
}

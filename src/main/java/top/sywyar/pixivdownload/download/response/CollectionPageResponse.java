package top.sywyar.pixivdownload.download.response;

import java.util.List;

/**
 * /ajax/user/{uid}/profile/collections 的代理响应：当前用户的珍藏集（コレクション）列表。
 * 珍藏集是 Pixiv 的一种容器内容，内部可同时包含插画与小说；本响应只含珍藏集自身的封面元数据，
 * 集内作品由 {@link CollectionWorksResponse} 单独提供。珍藏集不区分公开/不公开、不区分插画/小说。
 */
public record CollectionPageResponse(
        List<CollectionItem> collections,
        int total
) {
    public record CollectionItem(
            String id,
            String title,
            String description,
            String coverUrl,
            int bookmarkCount,
            int xRestrict,
            List<String> tags
    ) {
    }
}

package top.sywyar.pixivdownload.core.pixiv;

import top.sywyar.pixivdownload.core.work.WorkActionResult;

/**
 * Pixiv 作品收藏的 best-effort 动作端口。
 */
public interface PixivBookmarkActions {

    /**
     * 执行收藏插画作品。
     *
     * @param artworkId 插画作品标识
     * @param cookie Cookie
     * @return 方法返回的 {@code WorkActionResult} 实例
     */
    WorkActionResult bookmarkArtwork(Long artworkId, String cookie);

    /**
     * 执行收藏小说。
     *
     * @param novelId 小说标识
     * @param cookie Cookie
     * @return 方法返回的 {@code WorkActionResult} 实例
     */
    WorkActionResult bookmarkNovel(Long novelId, String cookie);
}

package top.sywyar.pixivdownload.core.pixiv;

import top.sywyar.pixivdownload.core.work.WorkActionResult;

/**
 * Pixiv 作品收藏的 best-effort 动作端口。
 */
public interface PixivBookmarkActions {

    WorkActionResult bookmarkArtwork(Long artworkId, String cookie);

    WorkActionResult bookmarkNovel(Long novelId, String cookie);
}

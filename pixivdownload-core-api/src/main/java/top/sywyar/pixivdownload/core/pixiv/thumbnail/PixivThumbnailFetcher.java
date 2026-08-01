package top.sywyar.pixivdownload.core.pixiv.thumbnail;

import java.net.URI;

/**
 * 取得受信 Pixiv 缩略图字节的稳定宿主端口。
 *
 * <p>实现负责校验 Pixiv CDN 目标、应用统一图片请求头且不得发送用户 Cookie，
 * 并把 HTTP 与传输失败收敛为 {@link PixivThumbnailFetchException}。
 */
public interface PixivThumbnailFetcher {

    byte[] fetch(URI source);
}

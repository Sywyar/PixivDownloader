package top.sywyar.pixivdownload.core.pixiv.media;

import top.sywyar.pixivdownload.core.pixiv.PixivAjaxException;

import java.net.URI;

/**
 * 以流方式读取 Pixiv CDN 媒体的稳定宿主端口，也是插件访问 {@code *.pximg.net} 的统一出口。
 *
 * <p>实现只接受 {@code https://*.pximg.net} 媒体地址与 {@code https://www.pixiv.net} 来源页，
 * 应用统一的浏览器图片请求头和宿主代理设置，不发送 Pixiv Cookie，也不跟随重定向。
 * 只有上游返回 HTTP 200 时才交出已打开的响应流；声明长度超过本次上限时在读取正文前拒绝。
 */
public interface PixivMediaClient {

    /**
     * 打开一个 Pixiv CDN 媒体响应。
     *
     * <p>实际上限取 {@code maximumBytes} 与
     * {@link top.sywyar.pixivdownload.core.pixiv.PixivImageTransferObserver#MAX_IMAGE_BYTES} 的较小值。
     * 调用方必须在所有分支关闭返回的流；关闭会释放底层连接。
     *
     * @param source CDN 媒体地址
     * @param referer 作为 Referer 请求头发送的 Pixiv 作品页、小说页或用户页
     * @param maximumBytes 本次允许读取的最大字节数，必须大于零
     * @return 已打开且按上限计量的响应流
     * @throws IllegalArgumentException {@code maximumBytes} 不大于零时抛出
     * @throws PixivAjaxException 地址不合法（{@code INVALID_TARGET}）、上游返回非 200（{@code HTTP_STATUS}）、
     *         声明长度超限（{@code RESPONSE_TOO_LARGE}）或连接失败（{@code TRANSPORT}）时抛出
     */
    PixivMediaStream open(URI source, URI referer, long maximumBytes);
}

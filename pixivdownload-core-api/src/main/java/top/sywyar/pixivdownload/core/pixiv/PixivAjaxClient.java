package top.sywyar.pixivdownload.core.pixiv;

import java.net.URI;

/**
 * 读取 Pixiv JSON 文档的稳定端口，也是插件访问 {@code www.pixiv.net} 的统一出口。
 *
 * <p>实现只接受 {@code https://www.pixiv.net} 上规范化后的 JSON 目标：{@code /ajax/**}、
 * {@code /rpc/**}，以及查询参数显式包含 {@code format=json} 的根级 {@code *.php} 页面
 * （例如排行榜）。实现负责统一的浏览器请求头、凭据候选重试、响应大小上限和 UTF-8 解码，
 * 并把 HTTP 与传输失败收敛为 {@link PixivAjaxException}；不跟随重定向。
 * 调用方负责构造且只编码一次目标 URI。读取 Pixiv CDN 媒体使用
 * {@link top.sywyar.pixivdownload.core.pixiv.media.PixivMediaClient}。
 */
public interface PixivAjaxClient {

    /**
     * 以 GET 读取一个 Pixiv JSON 文档。
     *
     * @param uri 已编码的完整目标地址
     * @param cookie 本次请求使用的 Pixiv Cookie；{@code null} 表示匿名请求
     * @return 按 UTF-8 解码的响应正文
     * @throws PixivAjaxException 目标不合法、上游返回非 2xx、响应超过上限或传输失败时抛出
     */
    String get(URI uri, String cookie);
}

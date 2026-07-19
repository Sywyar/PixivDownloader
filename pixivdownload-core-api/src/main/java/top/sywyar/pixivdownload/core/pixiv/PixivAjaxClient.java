package top.sywyar.pixivdownload.core.pixiv;

import java.net.URI;

/**
 * 读取 Pixiv JSON 响应的稳定端口。
 *
 * <p>实现只允许 {@code https://www.pixiv.net/ajax/**} 与旧版 {@code /rpc/index.php}
 * JSON 目标，负责统一请求头和 UTF-8 解码，
 * 并把 HTTP 与传输失败收敛为 {@link PixivAjaxException}；调用方负责构造且只编码一次目标 URI。
 */
public interface PixivAjaxClient {

    String get(URI uri, String cookie);
}

package top.sywyar.pixivdownload.core.pixiv;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;

/**
 * 把 Pixiv 图片流式写入调用方选定路径的稳定端口。
 *
 * <p>实现负责来源主机与 Referer 校验、统一图片请求头和流式传输；
 * 非法目标或非 2xx 响应返回 {@code false}，传输与文件 I/O 失败只抛出 {@link IOException}。
 * 调用方保留文件命名、扩展名与业务落库所有权。
 */
public interface PixivImageDownloader {

    boolean download(
            URI source,
            URI referer,
            Path target,
            String cookie,
            PixivImageTransferObserver observer
    ) throws IOException;
}

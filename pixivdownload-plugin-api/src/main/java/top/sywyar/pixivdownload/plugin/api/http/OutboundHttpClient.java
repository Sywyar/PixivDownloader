package top.sywyar.pixivdownload.plugin.api.http;

import java.io.IOException;
import java.util.Objects;

/** 由宿主按一个插件自有客户端配置创建的可关闭出站 HTTP 传输。 */
public interface OutboundHttpClient extends AutoCloseable {

    /**
     * 执行一次请求并返回归调用方所有的实时响应体。
     *
     * <p>无论代码走到哪条路径，即使只消费了部分响应体，也必须关闭返回的响应。非成功状态仍按普通响应返回。
     *
     * @param request 出站请求
     * @return 带实时响应体的响应
     */
    OutboundHttpStreamResponse exchangeStream(OutboundHttpRequest request);

    /**
     * 执行一次请求并缓冲完整响应体。
     *
     * <p>该便捷方法始终只关闭一次流式响应，读取响应体失败时也不例外。
     *
     * @param request 出站请求
     * @return 已缓冲完整响应体的响应
     * @throws OutboundHttpTransportException 读取响应体失败时抛出
     */
    default OutboundHttpResponse exchange(OutboundHttpRequest request) {
        OutboundHttpStreamResponse response = Objects.requireNonNull(
                exchangeStream(request),
                "exchangeStream returned null");
        try (response) {
            try {
                return new OutboundHttpResponse(
                        response.statusCode(),
                        response.statusText(),
                        response.headers(),
                        response.body().readAllBytes());
            } catch (IOException e) {
                throw new OutboundHttpTransportException(
                        "Failed to read outbound HTTP response body",
                        e);
            }
        }
    }

    /**
     * 释放全部传输资源。实现必须保证重复调用安全。
     */
    @Override
    void close();
}

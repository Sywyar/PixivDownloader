package top.sywyar.pixivdownload.plugin.api.http;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 带有归调用方所有的实时响应体流的传输中立出站 HTTP 响应。
 *
 * <p>关闭该响应或其响应体都只会关闭一次底层流。调用方应优先对响应使用 try-with-resources，
 * 以便在只消费部分响应体时仍能释放传输资源。
 */
public final class OutboundHttpStreamResponse implements AutoCloseable {

    private final int statusCode;
    private final String statusText;
    private final Map<String, List<String>> headers;
    private final InputStream body;
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * 创建带实时响应体的出站响应。
     *
     * @param statusCode 三位 HTTP 状态码
     * @param statusText 不含换行符的状态说明
     * @param headers 响应头；名称按大小写不敏感语义处理
     * @param body 归调用方所有的实时响应体流
     */
    public OutboundHttpStreamResponse(
            int statusCode,
            String statusText,
            Map<String, List<String>> headers,
            InputStream body
    ) {
        if (statusCode < 100 || statusCode > 999) {
            throw new IllegalArgumentException("statusCode must be a three-digit HTTP status");
        }
        this.statusCode = statusCode;
        this.statusText = OutboundHttpResponse.normalizedStatusText(statusText);
        this.headers = OutboundHttpResponse.immutableHeaders(headers);
        this.body = new CloseOnceInputStream(Objects.requireNonNull(body, "body"));
    }

    /**
     * 返回 HTTP 状态码。
     *
     * @return 三位 HTTP 状态码
     */
    public int statusCode() {
        return statusCode;
    }

    /**
     * 返回状态说明。
     *
     * @return 不含换行符的状态说明
     */
    public String statusText() {
        return statusText;
    }

    /**
     * 返回不可变响应头。
     *
     * @return 名称按大小写不敏感语义处理的响应头
     */
    public Map<String, List<String>> headers() {
        return headers;
    }

    /**
     * 返回实时响应体流。
     *
     * @return 关闭时同时关闭底层传输资源的响应体流
     */
    public InputStream body() {
        return body;
    }

    /** 关闭响应体并释放底层传输资源；重复调用安全。 */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            body.close();
        } catch (IOException e) {
            throw new OutboundHttpTransportException(
                    "Failed to close outbound HTTP response",
                    e);
        }
    }

    private static final class CloseOnceInputStream extends FilterInputStream {

        private final AtomicBoolean closed = new AtomicBoolean();

        private CloseOnceInputStream(InputStream delegate) {
            super(delegate);
        }

        @Override
        public void close() throws IOException {
            if (closed.compareAndSet(false, true)) {
                super.close();
            }
        }
    }
}

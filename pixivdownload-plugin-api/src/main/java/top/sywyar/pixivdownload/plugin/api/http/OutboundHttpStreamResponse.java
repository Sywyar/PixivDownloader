package top.sywyar.pixivdownload.plugin.api.http;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Transport-neutral outbound HTTP response with a live, caller-owned body stream.
 *
 * <p>Closing either this response or its body closes the underlying stream at most once. Callers
 * should prefer try-with-resources on the response so transport resources are released even when
 * the body is only partially consumed.
 */
public final class OutboundHttpStreamResponse implements AutoCloseable {

    private final int statusCode;
    private final String statusText;
    private final Map<String, List<String>> headers;
    private final InputStream body;
    private final AtomicBoolean closed = new AtomicBoolean();

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

    public int statusCode() {
        return statusCode;
    }

    public String statusText() {
        return statusText;
    }

    public Map<String, List<String>> headers() {
        return headers;
    }

    public InputStream body() {
        return body;
    }

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

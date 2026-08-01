package top.sywyar.pixivdownload.plugin.api.http;

import java.io.IOException;
import java.util.Objects;

/**
 * Closeable outbound HTTP transport opened by the host for one plugin-owned client profile.
 */
public interface OutboundHttpClient extends AutoCloseable {

    /**
     * Executes one request and returns a live response body owned by the caller.
     *
     * <p>The returned response must be closed on every path, including when only part of the
     * body is consumed. Non-success statuses remain ordinary responses.
     */
    OutboundHttpStreamResponse exchangeStream(OutboundHttpRequest request);

    /**
     * Executes one request and buffers the complete response body.
     *
     * <p>This convenience method always closes the streaming response exactly once, including
     * when reading the body fails.
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
     * Releases all transport resources. Implementations must make repeated calls safe.
     */
    @Override
    void close();
}

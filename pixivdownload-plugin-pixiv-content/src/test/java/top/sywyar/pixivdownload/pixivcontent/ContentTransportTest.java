package top.sywyar.pixivdownload.pixivcontent;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.http.*;

import static org.assertj.core.api.Assertions.*;

class ContentTransportTest {
    private static final URI CDN = URI.create("https://i.pximg.net/img/x.png");
    private static final URI RANKING = URI.create("https://www.pixiv.net/ranking.php?mode=daily");
    private static final byte[] PNG = {(byte) 137,80,78,71,13,10,26,10,0,0,0,13};

    @Test void streamsOnlyTheCdnResourceAndClosesItWithTheDownstreamBody() throws IOException {
        var http = new Upstream(200, Map.of("Content-Length", List.of("12")), PNG);
        var response = new ContentTransport(http).image(CDN, 321);
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("image/png");
        assertThat(response.getHeaders().getContentLength()).isEqualTo(PNG.length);
        assertThat(http.request.get().headers()).doesNotContainKey("Cookie")
                .containsEntry("Referer", List.of("https://www.pixiv.net/artworks/321"));
        assertThat(http.closed).isFalse();
        try (var body = response.getBody().getInputStream()) {
            assertThat(body.readAllBytes()).isEqualTo(PNG);
        }
        assertThat(http.closed).isTrue();
    }

    @Test void enforcesActualTransferSizeAndDetectsEarlyEof() throws IOException {
        try (var exact = new ContentTransport.LimitedImageStream(new ByteArrayInputStream(PNG), -1, PNG.length)) {
            assertThat(exact.readAllBytes()).isEqualTo(PNG);
        }
        try (var excessive = new ContentTransport.LimitedImageStream(new ByteArrayInputStream(PNG), -1, 4);
             var shortBody = new ContentTransport.LimitedImageStream(new ByteArrayInputStream(PNG), 20, 30)) {
            assertThatIOException().isThrownBy(excessive::readAllBytes);
            assertThatIOException().isThrownBy(shortBody::readAllBytes);
        }
    }

    @Test void rejectsUntrustedSourceAndClosesInvalidImagesBeforeResponding() {
        var html = new Upstream(200, Map.of("Content-Type", List.of("image/jpeg")),
                "<html>sign in</html>".getBytes(StandardCharsets.UTF_8));
        var remote = new ContentTransport(html);
        assertThatThrownBy(() -> remote.image(URI.create("https://i.pximg.net.other.example/x"), 1))
                .isInstanceOf(ContentFailure.class);
        assertThat(html.request.get()).isNull();
        assertThatThrownBy(() -> remote.image(CDN, 1)).hasMessage("PIXIV_RESPONSE_INVALID");
        assertThat(html.closed).isTrue();
    }

    @Test void rankingCredentialGoesOnlyToTheFixedEndpointAndErrorsNeverReadTheirBody() {
        var api = new Upstream(429, Map.of(), new byte[0]);
        var remote = new ContentTransport(api);
        assertThatThrownBy(() -> remote.ranking(URI.create("https://www.pixiv.net/other"), "test-cookie"))
                .hasMessage("PIXIV_RESPONSE_INVALID");
        assertThat(api.request.get()).isNull();
        assertThatThrownBy(() -> remote.ranking(RANKING, "test-cookie")).hasMessage("PIXIV_RATE_LIMITED");
        assertThat(api.request.get().headers()).containsEntry("Cookie", List.of("test-cookie"));
        assertThat(api.closed).isTrue();
    }

    @Test void rankingBoundsChunkedBodiesAndRejectsOversizedDeclaredResponsesBeforeReading() {
        for (var headers : List.of(Map.<String, List<String>>of(),
                Map.of("Content-Length", List.of(Integer.toString(ContentTransport.JSON_BYTES + 1))))) {
            var api = new Upstream(200, headers, new byte[ContentTransport.JSON_BYTES + 1]);
            assertThatThrownBy(() -> new ContentTransport(api).ranking(RANKING, null)).hasMessage("PIXIV_RESPONSE_TOO_LARGE");
            assertThat(api.closed).isTrue();
        }
    }

    private static final class Upstream implements OutboundHttpClient {
        final AtomicReference<OutboundHttpRequest> request = new AtomicReference<>();
        final AtomicBoolean closed = new AtomicBoolean();
        final OutboundHttpStreamResponse response;

        Upstream(int status, Map<String, List<String>> headers, byte[] data) {
            InputStream stream = new ByteArrayInputStream(data) {
                @Override public void close() { closed.set(true); }
            };
            response = new OutboundHttpStreamResponse(status, "", headers, stream);
        }

        @Override public OutboundHttpStreamResponse exchangeStream(OutboundHttpRequest next) {
            request.set(next);
            return response;
        }
        @Override public void close() {}
    }
}

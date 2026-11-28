package top.sywyar.pixivdownload.pixivcontent;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import ch.qos.logback.classic.Level;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.core.pixiv.PixivAjaxException;
import top.sywyar.pixivdownload.core.pixiv.PixivAjaxFailure;
import top.sywyar.pixivdownload.core.pixiv.PixivImageTransferObserver;
import top.sywyar.pixivdownload.core.pixiv.media.PixivMediaClient;
import top.sywyar.pixivdownload.core.pixiv.media.PixivMediaStream;
import top.sywyar.pixivdownload.core.web.ResponseStreamAbortedException;

import static org.assertj.core.api.Assertions.*;

class ContentTransportTest {
    private static final URI CDN = URI.create("https://i.pximg.net/img/x.png");
    private static final byte[] PNG = {(byte) 137,80,78,71,13,10,26,10,0,0,0,13};

    @Test
    @DisplayName("图片经宿主媒体端口读取，附作品页来源并在下游关闭时释放上游")
    void streamsTheCdnResourceThroughTheHostPortAndClosesItWithTheDownstreamBody() throws IOException {
        var media = new Media(PNG, PNG.length);
        var response = new ContentTransport(media).image(CDN, 321);
        assertThat(media.source.get()).isEqualTo(CDN);
        assertThat(media.referer.get()).hasToString("https://www.pixiv.net/artworks/321");
        assertThat(media.maximumBytes.get()).isEqualTo(PixivImageTransferObserver.MAX_IMAGE_BYTES);
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("image/png");
        assertThat(response.getHeaders().getContentLength()).isEqualTo(PNG.length);
        assertThat(media.closed).isFalse();
        try (var body = response.getBody().getInputStream()) {
            assertThat(body.readAllBytes()).isEqualTo(PNG);
        }
        assertThat(media.closed).isTrue();
    }

    @Test
    @DisplayName("头像、小说图片与动图归档分别使用对应 Pixiv 页面作为来源")
    void eachMediaKindSendsItsOwnPixivPageAsReferer() throws IOException {
        var media = new Media(PNG, -1);
        var remote = new ContentTransport(media);
        remote.avatar(CDN, 3).getBody().getInputStream().close();
        assertThat(media.referer.get()).hasToString("https://www.pixiv.net/users/3");
        remote.novelImage(CDN, 88).getBody().getInputStream().close();
        assertThat(media.referer.get()).hasToString("https://www.pixiv.net/novel/show.php?id=88");
        try (var archive = remote.animationZip(CDN, 42, 4096)) {
            assertThat(archive.readAllBytes()).isEqualTo(PNG);
        }
        assertThat(media.referer.get()).hasToString("https://www.pixiv.net/artworks/42");
        assertThat(media.maximumBytes.get()).isEqualTo(4096);
    }

    @Test
    @DisplayName("不可信地址不发请求，非图片正文在响应前拒绝并释放上游")
    void rejectsUntrustedSourceAndClosesInvalidImagesBeforeResponding() {
        var media = new Media("<html>sign in</html>".getBytes(StandardCharsets.UTF_8), -1);
        var remote = new ContentTransport(media);
        assertThatThrownBy(() -> remote.image(URI.create("https://i.pximg.net.other.example/x"), 1))
                .isInstanceOf(ContentFailure.class);
        assertThat(media.source.get()).isNull();
        assertThatThrownBy(() -> remote.image(CDN, 1)).hasMessage("PIXIV_RESPONSE_INVALID");
        assertThat(media.closed).isTrue();
    }

    @Test
    @DisplayName("宿主端口的失败映射为稳定错误码并保留过期地址重试所需的状态")
    void hostFailuresKeepTheirStableCodesAndStaleUrlStatus() {
        record Example(PixivAjaxException failure, int status, String code) {}
        for (var sample : java.util.List.of(
                new Example(new PixivAjaxException(PixivAjaxFailure.HTTP_STATUS, 404), 404, "PIXIV_CONTENT_NOT_FOUND"),
                new Example(new PixivAjaxException(PixivAjaxFailure.HTTP_STATUS, 403), 403, "PIXIV_ACCESS_DENIED"),
                new Example(new PixivAjaxException(PixivAjaxFailure.RESPONSE_TOO_LARGE, 0), 502, "PIXIV_RESPONSE_TOO_LARGE"),
                new Example(new PixivAjaxException(PixivAjaxFailure.TRANSPORT, 0), 502, "PIXIV_UNAVAILABLE"))) {
            var media = new Media(PNG, PNG.length);
            media.failure = sample.failure();
            var failure = catchThrowableOfType(ContentFailure.class, () -> new ContentTransport(media).image(CDN, 1));
            assertThat(failure.status).isEqualTo(sample.status());
            assertThat(failure.code).isEqualTo(sample.code());
        }
    }

    @Test
    @DisplayName("回传中途正文截断或超限时只记录一次原因，并以中止异常交给宿主")
    void midStreamFailuresAreLoggedOnceAndAbortTheResponse() throws IOException {
        record Example(Media media, String reason) {}
        var truncated = new Media(PNG, PNG.length + 20L);
        var oversized = new Media(java.util.Arrays.copyOf(PNG, 64), -1);
        oversized.budget = 32;
        for (var sample : java.util.List.of(new Example(truncated, "media_transfer_failed"),
                new Example(oversized, "media_size_limit"))) {
            try (var log = new LogCapture(AbortLoggingStream.class);
                 var body = new ContentTransport(sample.media()).image(CDN, 7).getBody().getInputStream()) {
                assertThatThrownBy(body::readAllBytes).isInstanceOf(ResponseStreamAbortedException.class)
                        .hasMessageContaining(sample.reason());
                assertThatThrownBy(body::readAllBytes).isInstanceOf(ResponseStreamAbortedException.class);
                assertThat(log.messages(Level.WARN)).singleElement().asString()
                        .startsWith("Pixiv content stream aborted: operation=unknown")
                        .contains("reason=" + sample.reason(), "bytesSent=")
                        .doesNotContain("pximg");
            }
        }
    }

    private static final class Media implements PixivMediaClient {
        final AtomicReference<URI> source = new AtomicReference<>();
        final AtomicReference<URI> referer = new AtomicReference<>();
        final AtomicLong maximumBytes = new AtomicLong();
        final AtomicBoolean closed = new AtomicBoolean();
        final byte[] body;
        final long declaredLength;
        PixivAjaxException failure;
        long budget;

        Media(byte[] body, long declaredLength) {
            this.body = body;
            this.declaredLength = declaredLength;
        }

        @Override public PixivMediaStream open(URI next, URI page, long maximum) {
            source.set(next);
            referer.set(page);
            maximumBytes.set(maximum);
            if (failure != null) throw failure;
            return new PixivMediaStream(new ByteArrayInputStream(body) {
                @Override public void close() { closed.set(true); }
            }, declaredLength, budget > 0 ? budget : maximum);
        }
    }
}

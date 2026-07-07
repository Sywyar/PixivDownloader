package top.sywyar.pixivdownload.douyin.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.douyin.model.DouyinParsedKind;
import top.sywyar.pixivdownload.douyin.parse.DouyinUrlParser;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("DefaultDouyinShortLinkResolver 抖音短链展开")
class DefaultDouyinShortLinkResolverTest {

    @Test
    @DisplayName("30x 到视频、图文、图集和合集最终 URL")
    void resolvesSupportedRedirectTargets() throws Exception {
        assertResolved("https://www.douyin.com/video/7351234567890123456", DouyinParsedKind.VIDEO);
        assertResolved("https://www.douyin.com/note/7351234567890123456", DouyinParsedKind.NOTE);
        assertResolved("https://www.douyin.com/gallery/7351234567890123456", DouyinParsedKind.GALLERY);
        assertResolved("https://www.douyin.com/collection/12345", DouyinParsedKind.COLLECTION);
        assertResolved("https://www.douyin.com/mix/12345", DouyinParsedKind.COLLECTION);
    }

    @Test
    @DisplayName("裸短链 host 自动补 https")
    void normalizesBareShortUrl() throws Exception {
        FakeRedirectClient client = new FakeRedirectClient()
                .redirect("https://www.douyin.com/video/7351234567890123456")
                .ok();
        var resolver = new DefaultDouyinShortLinkResolver(new DouyinUrlParser(), client);

        assertThat(resolver.resolve("v.iesdouyin.com/AbCd123/", null).id())
                .isEqualTo("7351234567890123456");
    }

    @Test
    @DisplayName("跳转循环返回 redirect-loop")
    void rejectsRedirectLoop() {
        FakeRedirectClient client = new FakeRedirectClient()
                .redirect("https://v.douyin.com/AbCd123/");
        var resolver = new DefaultDouyinShortLinkResolver(new DouyinUrlParser(), client);

        assertCode(() -> resolver.resolve("https://v.douyin.com/AbCd123/", null),
                DouyinClientErrorCode.REDIRECT_LOOP);
    }

    @Test
    @DisplayName("非抖音目标返回 non-douyin-target")
    void rejectsNonDouyinTarget() {
        FakeRedirectClient client = new FakeRedirectClient()
                .redirect("https://example.test/video/1");
        var resolver = new DefaultDouyinShortLinkResolver(new DouyinUrlParser(), client);

        assertThatThrownBy(() -> resolver.resolve("https://v.douyin.com/AbCd123/", null))
                .isInstanceOf(DouyinClientException.class)
                .hasMessageContaining("host=example.test")
                .extracting(error -> ((DouyinClientException) error).code())
                .isEqualTo(DouyinClientErrorCode.NON_DOUYIN_TARGET);
    }

    @Test
    @DisplayName("403 与 429 返回独立错误")
    void classifiesForbiddenAndRateLimited() {
        assertCode(() -> resolver(new FakeRedirectClient().status(403)).resolve("https://v.douyin.com/A/", null),
                DouyinClientErrorCode.HTTP_FORBIDDEN);
        assertCode(() -> resolver(new FakeRedirectClient().status(429)).resolve("https://v.douyin.com/A/", null),
                DouyinClientErrorCode.HTTP_RATE_LIMITED);
    }

    @Test
    @DisplayName("网络超时返回 network-timeout")
    void classifiesTimeout() {
        assertCode(() -> resolver(new FakeRedirectClient().timeout()).resolve("https://v.douyin.com/A/", null),
                DouyinClientErrorCode.NETWORK_TIMEOUT);
    }

    @Test
    @DisplayName("验证页与无法提取 id 的最终 URL 分类明确")
    void classifiesVerifyPageAndUnsupportedFinalUrl() {
        assertCode(() -> resolver(new FakeRedirectClient()
                        .body("验证码".getBytes(StandardCharsets.UTF_8)))
                        .resolve("https://v.douyin.com/A/", null),
                DouyinClientErrorCode.LOGIN_OR_VERIFY_PAGE);
        assertCode(() -> resolver(new FakeRedirectClient()
                        .redirect("https://www.douyin.com/discover")
                        .ok())
                        .resolve("https://v.douyin.com/A/", null),
                DouyinClientErrorCode.UNSUPPORTED_FINAL_URL);
    }

    private static void assertResolved(String target, DouyinParsedKind kind) throws Exception {
        FakeRedirectClient client = new FakeRedirectClient().redirect(target).ok();
        var resolver = new DefaultDouyinShortLinkResolver(new DouyinUrlParser(), client);

        var parsed = resolver.resolve("https://v.douyin.com/AbCd123/", null);

        assertThat(parsed.kind()).isEqualTo(kind);
    }

    private static DefaultDouyinShortLinkResolver resolver(FakeRedirectClient client) {
        return new DefaultDouyinShortLinkResolver(new DouyinUrlParser(), client);
    }

    private static void assertCode(ThrowingRunnable action, DouyinClientErrorCode code) {
        assertThatThrownBy(action::run)
                .isInstanceOf(DouyinClientException.class)
                .extracting(error -> ((DouyinClientException) error).code())
                .isEqualTo(code);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static final class FakeRedirectClient implements DouyinRedirectClient {
        private final Queue<DouyinRedirectResponse> responses = new ArrayDeque<>();
        private boolean timeout;

        FakeRedirectClient redirect(String location) {
            responses.add(new DouyinRedirectResponse(302, URI.create(location), "text/html", new byte[0]));
            return this;
        }

        FakeRedirectClient ok() {
            responses.add(new DouyinRedirectResponse(200, null, "text/html", new byte[0]));
            return this;
        }

        FakeRedirectClient status(int status) {
            responses.add(new DouyinRedirectResponse(status, null, "text/html", new byte[0]));
            return this;
        }

        FakeRedirectClient body(byte[] body) {
            responses.add(new DouyinRedirectResponse(200, null, "text/html", body));
            return this;
        }

        FakeRedirectClient timeout() {
            timeout = true;
            return this;
        }

        @Override
        public DouyinRedirectResponse get(URI uri, String cookie) throws IOException {
            if (timeout) {
                throw new SocketTimeoutException("timeout");
            }
            return responses.isEmpty()
                    ? new DouyinRedirectResponse(200, null, "text/html", new byte[0])
                    : responses.remove();
        }
    }
}

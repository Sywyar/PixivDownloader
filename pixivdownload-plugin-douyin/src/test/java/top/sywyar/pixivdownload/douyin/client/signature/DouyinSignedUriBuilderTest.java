package top.sywyar.pixivdownload.douyin.client.signature;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DouyinSignedUriBuilder 示例项目签名请求")
class DouyinSignedUriBuilderTest {

    @Test
    @DisplayName("a_bogus 成功时不会预生成或发送 X-Bogus")
    void usesABogusWithoutEagerXBogusFallback() {
        AtomicInteger xBogusCalls = new AtomicInteger();
        var builder = new DouyinSignedUriBuilder(
                query -> query + "&a_bogus=primary",
                url -> {
                    xBogusCalls.incrementAndGet();
                    throw new AssertionError("X-Bogus must remain lazy");
                });

        var request = builder.request(
                "/aweme/v1/web/aweme/detail/",
                Map.of("aweme_id", "7351", "aid", "6383"),
                "ttwid=tt; msToken=fromCookie");

        assertThat(request.uri().getRawQuery())
                .contains("aweme_id=7351", "aid=6383", "msToken=fromCookie", "a_bogus=")
                .doesNotContain("X-Bogus=");
        assertThat(xBogusCalls).hasValue(0);
    }

    @Test
    @DisplayName("仅在 a_bogus 本地生成异常时回退到 X-Bogus")
    void fallsBackToXBogusOnlyWhenABogusGenerationThrows() {
        AtomicInteger xBogusCalls = new AtomicInteger();
        var builder = new DouyinSignedUriBuilder(
                query -> {
                    throw new IllegalStateException("synthetic signer failure");
                },
                url -> {
                    xBogusCalls.incrementAndGet();
                    return URI.create(url + "&X-Bogus=fallback");
                });

        var request = builder.request(
                "/aweme/v1/web/aweme/detail/",
                Map.of("aweme_id", "7351"),
                "msToken=fromCookie; ttwid=tt");

        assertThat(request.uri().getRawQuery())
                .contains("aweme_id=7351", "msToken=fromCookie", "X-Bogus=fallback")
                .doesNotContain("a_bogus=");
        assertThat(xBogusCalls).hasValue(1);
    }

    @Test
    @DisplayName("无签名请求保留完整参数与同一 msToken 凭证")
    void buildsUnsignedRequestWithoutCallingSigners() {
        AtomicInteger aBogusCalls = new AtomicInteger();
        AtomicInteger xBogusCalls = new AtomicInteger();
        var builder = new DouyinSignedUriBuilder(
                query -> {
                    aBogusCalls.incrementAndGet();
                    return query + "&a_bogus=unexpected";
                },
                url -> {
                    xBogusCalls.incrementAndGet();
                    return URI.create(url + "&X-Bogus=unexpected");
                });

        var request = builder.unsignedRequest(
                "/aweme/v1/web/general/search/single/",
                Map.of("keyword", "猫 图", "offset", 0),
                "ttwid=tt; msToken=fromCookie");

        assertThat(request.uri().getRawQuery())
                .contains("keyword=%E7%8C%AB+%E5%9B%BE", "offset=0", "msToken=fromCookie")
                .doesNotContain("a_bogus=", "X-Bogus=");
        assertThat(request.cookie()).isEqualTo("ttwid=tt; msToken=fromCookie");
        assertThat(aBogusCalls).hasValue(0);
        assertThat(xBogusCalls).hasValue(0);
    }

    @Test
    @DisplayName("默认签名器只附加非功能占位参数，便于示例端到端跑通装配")
    void defaultSignerAppendsStubSignature() {
        var request = new DouyinSignedUriBuilder().request(
                "/aweme/v1/web/aweme/detail/", Map.of("aweme_id", "7351"),
                "msToken=fromCookie; ttwid=tt");

        assertThat(request.uri().getRawQuery())
                .contains("aweme_id=7351", "msToken=fromCookie")
                .contains("a_bogus=" + DouyinSignedUriBuilder.STUB_SIGNATURE)
                .doesNotContain("X-Bogus=");
    }

    @Test
    @DisplayName("签名覆盖最终编码后的完整查询参数")
    void signsFinalEncodedQuery() {
        var uri = new DouyinSignedUriBuilder().api(
                "/aweme/v1/web/general/search/single/",
                Map.of("keyword", "猫 图", "search_channel", "aweme_video_web"),
                "msToken=fromCookie; ttwid=tt");

        assertThat(uri.getRawQuery())
                .contains("keyword=%E7%8C%AB+%E5%9B%BE", "search_channel=aweme_video_web")
                .doesNotContain("%25E7%258C%25AB");
    }

    @Test
    @DisplayName("Cookie 中的 msToken 同时进入请求查询与请求 Cookie")
    void keepsRealMsTokenConsistentBetweenQueryAndCookie() {
        var request = new DouyinSignedUriBuilder().request(
                "/aweme/v1/web/aweme/detail/", Map.of("aweme_id", "7351"),
                "ttwid=tt; msToken=real-token");

        assertThat(request.uri().getRawQuery())
                .contains("msToken=real-token", "a_bogus=");
        assertThat(request.cookie()).isEqualTo("ttwid=tt; msToken=real-token");
    }

    @Test
    @DisplayName("空 msToken 不会被伪造填充，请求也不注入令牌")
    void leavesBlankMsTokenUnfabricated() {
        var request = new DouyinSignedUriBuilder().request(
                "/aweme/v1/web/aweme/detail/", Map.of("aweme_id", "7351"),
                "msToken=; ttwid=tt; MSTOKEN=");

        assertThat(request.uri().getRawQuery())
                .contains("aweme_id=7351", "a_bogus=")
                .doesNotContain("msToken=");
    }
}

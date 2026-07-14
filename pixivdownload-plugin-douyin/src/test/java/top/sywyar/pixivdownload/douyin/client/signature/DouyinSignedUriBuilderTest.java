package top.sywyar.pixivdownload.douyin.client.signature;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DouyinSignedUriBuilder 示例项目签名请求")
class DouyinSignedUriBuilderTest {

    private static final Pattern MS_TOKEN = Pattern.compile("(?:^|&)msToken=([^&]+)");

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
    @DisplayName("生成的 msToken 同时进入签名查询与请求 Cookie")
    void keepsGeneratedMsTokenConsistentWithRequestCookie() {
        var request = new DouyinSignedUriBuilder().request(
                "/aweme/v1/web/aweme/detail/", Map.of("aweme_id", "7351"), "ttwid=tt");
        String token = tokenFromQuery(request.uri().getRawQuery());

        assertThat(token).hasSize(184).endsWith("==");
        boolean cookieHasToken = java.util.Arrays.stream(request.cookie().split(";"))
                .map(String::trim)
                .anyMatch(part -> part.equals("msToken=" + token));
        assertThat(cookieHasToken).isTrue();
    }

    @Test
    @DisplayName("同一缺少 msToken 的凭证在连续与并发请求中复用同一会话 token")
    void reusesGeneratedMsTokenAcrossRequests() {
        var builder = new DouyinSignedUriBuilder(
                query -> query + "&a_bogus=test",
                url -> {
                    throw new AssertionError("X-Bogus must remain lazy");
                });

        var tokens = IntStream.range(0, 24)
                .parallel()
                .mapToObj(index -> builder.request(
                        "/aweme/v1/web/aweme/post/",
                        Map.of("sec_user_id", "user", "max_cursor", index),
                        "ttwid=tt; sessionid=session"))
                .map(request -> tokenFromQuery(request.uri().getRawQuery()))
                .toList();

        assertThat(tokens.stream().distinct().count()).isOne();
    }

    @Test
    @DisplayName("空或重复的 msToken Cookie 会被替换为唯一会话 token")
    void replacesEmptyAndDuplicateMsTokenCookieParts() {
        var request = new DouyinSignedUriBuilder().request(
                "/aweme/v1/web/aweme/detail/",
                Map.of("aweme_id", "7351"),
                "msToken=; ttwid=tt; MSTOKEN=");
        String token = tokenFromQuery(request.uri().getRawQuery());
        long tokenParts = java.util.Arrays.stream(request.cookie().split(";"))
                .map(String::trim)
                .filter(part -> part.regionMatches(true, 0, "msToken=", 0, "msToken=".length()))
                .count();

        assertThat(tokenParts).isOne();
        boolean cookieHasToken = java.util.Arrays.stream(request.cookie().split(";"))
                .map(String::trim)
                .anyMatch(part -> part.equals("msToken=" + token));
        assertThat(cookieHasToken).isTrue();
        assertThat(request.cookie().contains("msToken=;")).isFalse();
    }

    private static String tokenFromQuery(String query) {
        var matcher = MS_TOKEN.matcher(query);
        if (!matcher.find()) {
            throw new AssertionError("msToken query parameter is missing");
        }
        return URLDecoder.decode(matcher.group(1), StandardCharsets.UTF_8);
    }
}

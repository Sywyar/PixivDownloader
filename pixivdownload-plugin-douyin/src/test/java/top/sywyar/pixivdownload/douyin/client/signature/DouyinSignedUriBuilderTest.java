package top.sywyar.pixivdownload.douyin.client.signature;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DouyinSignedUriBuilder 抖音 Web API 签名参数")
class DouyinSignedUriBuilderTest {

    private static final Pattern MS_TOKEN = Pattern.compile("(?:^|&)msToken=([^&]+)");

    @Test
    @DisplayName("复用 Cookie 中的 msToken 并追加 a_bogus")
    void reusesCookieMsTokenAndAppendsABogus() {
        var uri = new DouyinSignedUriBuilder().api("/aweme/v1/web/aweme/detail/",
                Map.of("aweme_id", "7351", "aid", "1128"),
                "ttwid=tt; msToken=fromCookie; odin_tt=odin");

        assertThat(uri.getPath()).isEqualTo("/aweme/v1/web/aweme/detail/");
        assertThat(uri.getRawQuery())
                .contains("device_platform=webapp")
                .contains("browser_name=Chrome")
                .contains("browser_version=139.0.0.0")
                .contains("aweme_id=7351")
                .contains("aid=1128")
                .contains("msToken=fromCookie")
                .contains("a_bogus=");
    }

    @Test
    @DisplayName("缺少 msToken 时生成兜底 token")
    void generatesFallbackMsTokenWhenCookieMissesIt() {
        var uri = new DouyinSignedUriBuilder().api("/aweme/v1/web/mix/detail/",
                Map.of("mix_id", "12345"),
                "ttwid=tt");

        String token = msToken(uri.getRawQuery());

        assertThat(token).hasSize(184).endsWith("==");
        assertThat(uri.getRawQuery()).contains("mix_id=12345", "a_bogus=");
    }

    @Test
    @DisplayName("查询参数只编码一次")
    void encodesQueryParamsOnce() {
        var uri = new DouyinSignedUriBuilder().api("/aweme/v1/web/general/search/single/",
                Map.of("keyword", "猫 图"),
                "msToken=fromCookie");

        assertThat(uri.getRawQuery())
                .contains("keyword=%E7%8C%AB+%E5%9B%BE")
                .doesNotContain("%25E7%258C%25AB");
    }

    @Test
    @DisplayName("签名候选先使用 a_bogus，再提供 X-Bogus 兜底")
    void providesXbogusFallbackCandidate() {
        var candidates = new DouyinSignedUriBuilder().apiCandidates("/aweme/v1/web/aweme/detail/",
                Map.of("aweme_id", "7351"),
                "msToken=fromCookie; ttwid=tt");

        assertThat(candidates).hasSize(2);
        assertThat(candidates.get(0).getRawQuery())
                .contains("aweme_id=7351", "msToken=fromCookie", "a_bogus=")
                .doesNotContain("X-Bogus=");
        assertThat(candidates.get(1).getRawQuery())
                .contains("aweme_id=7351", "msToken=fromCookie", "X-Bogus=")
                .doesNotContain("a_bogus=");
    }

    private static String msToken(String rawQuery) {
        var matcher = MS_TOKEN.matcher(rawQuery);
        assertThat(matcher.find()).isTrue();
        return URLDecoder.decode(matcher.group(1), StandardCharsets.UTF_8);
    }
}

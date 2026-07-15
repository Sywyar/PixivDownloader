package top.sywyar.pixivdownload.douyin.client.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DouyinApiUriBuilder 示例项目请求参数")
class DouyinApiUriBuilderTest {

    private static final Pattern MS_TOKEN = Pattern.compile("(?:^|&)msToken=([^&]+)");

    @Test
    @DisplayName("普通接口使用示例项目的完整 Web 请求参数")
    void buildsReferenceCompatibleDefaultQuery() {
        var uri = new DouyinApiUriBuilder().api("/aweme/v1/web/general/search/single/",
                Map.of("keyword", "猫", "offset", 0),
                "ttwid=tt; msToken=fromCookie");

        assertThat(uri.getPath()).isEqualTo("/aweme/v1/web/general/search/single/");
        assertThat(uri.getRawQuery())
                .contains("device_platform=webapp", "aid=6383", "channel=channel_pc_web")
                .contains("update_version_code=170400", "pc_client_type=1", "pc_libra_divert=Windows")
                .contains("version_code=290100", "version_name=29.1.0")
                .contains("cookie_enabled=true", "screen_width=1536", "screen_height=864")
                .contains("browser_language=zh-CN", "browser_platform=Win32")
                .contains("browser_name=Chrome", "browser_version=139.0.0.0")
                .contains("engine_name=Blink", "engine_version=139.0.0.0")
                .contains("os_name=Windows", "os_version=10", "cpu_core_num=16", "device_memory=8")
                .contains("support_h265=1", "support_dash=1", "msToken=fromCookie")
                .contains("keyword=%E7%8C%AB", "offset=0")
                .doesNotContain("a_bogus=", "X-Bogus=");
    }

    @Test
    @DisplayName("端点参数可覆盖默认 aid")
    void endpointParamsOverrideDefaultAid() {
        var uri = new DouyinApiUriBuilder().api("/aweme/v1/web/aweme/detail/",
                Map.of("aweme_id", "7351", "aid", "1128"),
                "msToken=fromCookie");

        assertThat(uri.getRawQuery())
                .contains("aweme_id=7351", "aid=1128")
                .doesNotContain("aid=6383");
    }

    @Test
    @DisplayName("自建收藏夹两层接口使用示例项目的 17.4.0 请求配置")
    void appliesCollectRequestProfile() {
        for (String path : java.util.List.of(
                "/aweme/v1/web/collects/list/",
                "/aweme/v1/web/collects/video/list/")) {
            var uri = new DouyinApiUriBuilder().api(path,
                    Map.of("collects_id", "folder-1", "cursor", 0, "count", 10),
                    "msToken=fromCookie");

            assertThat(uri.getRawQuery())
                    .contains("version_code=170400", "version_name=17.4.0")
                    .doesNotContain("version_code=290100", "version_name=29.1.0");
        }
    }

    @Test
    @DisplayName("Cookie 缺少 msToken 时使用与示例一致的请求参数兜底")
    void suppliesFallbackMsTokenWhenCookieMissesIt() {
        var uri = new DouyinApiUriBuilder().api("/aweme/v1/web/aweme/detail/",
                Map.of("aweme_id", "7351"), "ttwid=tt");

        var matcher = MS_TOKEN.matcher(uri.getRawQuery());
        assertThat(matcher.find()).isTrue();
        assertThat(URLDecoder.decode(matcher.group(1), StandardCharsets.UTF_8))
                .hasSize(184)
                .endsWith("==");
    }

    @Test
    @DisplayName("查询参数只编码一次")
    void encodesQueryParamsOnce() {
        var uri = new DouyinApiUriBuilder().api("/aweme/v1/web/general/search/single/",
                Map.of("keyword", "猫 图"), "msToken=fromCookie");

        assertThat(uri.getRawQuery())
                .contains("keyword=%E7%8C%AB+%E5%9B%BE")
                .doesNotContain("%25E7%258C%25AB");
    }
}

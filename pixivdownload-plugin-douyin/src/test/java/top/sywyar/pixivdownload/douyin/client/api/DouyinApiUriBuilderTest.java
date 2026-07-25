package top.sywyar.pixivdownload.douyin.client.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DouyinApiUriBuilder 示例项目请求参数")
class DouyinApiUriBuilderTest {

    @Test
    @DisplayName("普通接口使用示例项目的 Web 应用身份参数，且不伪造浏览器/设备指纹")
    void buildsReferenceCompatibleDefaultQuery() {
        var uri = new DouyinApiUriBuilder().api("/aweme/v1/web/general/search/single/",
                Map.of("keyword", "猫", "offset", 0),
                "ttwid=tt; msToken=fromCookie");

        assertThat(uri.getPath()).isEqualTo("/aweme/v1/web/general/search/single/");
        assertThat(uri.getRawQuery())
                .contains("device_platform=webapp", "aid=6383")
                .contains("version_code=290100", "version_name=29.1.0")
                .contains("msToken=fromCookie")
                .contains("keyword=%E7%8C%AB", "offset=0")
                .doesNotContain("a_bogus=", "X-Bogus=")
                .doesNotContain("cpu_core_num=", "device_memory=", "browser_platform=", "screen_width=",
                        "channel=", "uifid=");
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
    @DisplayName("Cookie 缺少 msToken 时不伪造令牌，也不注入该参数")
    void doesNotFabricateMsTokenWhenCookieMissesIt() {
        var uri = new DouyinApiUriBuilder().api("/aweme/v1/web/aweme/detail/",
                Map.of("aweme_id", "7351"), "ttwid=tt");

        assertThat(uri.getRawQuery())
                .contains("aweme_id=7351")
                .doesNotContain("msToken=");
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

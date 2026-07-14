package top.sywyar.pixivdownload.douyin.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DouyinEndpointRequestPolicy 端点请求策略")
class DouyinEndpointRequestPolicyTest {

    @Test
    @DisplayName("通用搜索端点使用无签名请求")
    void usesUnsignedRequestForGeneralSearch() {
        assertThat(DouyinEndpointRequestPolicy.forPath(
                "/aweme/v1/web/general/search/single/"))
                .isEqualTo(DouyinEndpointRequestPolicy.UNSIGNED);
        assertThat(DouyinEndpointRequestPolicy.forPath(
                "aweme/v1/web/general/search/single/"))
                .isEqualTo(DouyinEndpointRequestPolicy.UNSIGNED);
    }

    @Test
    @DisplayName("其它端点保持既有签名请求策略")
    void keepsSignedRequestsForOtherEndpoints() {
        assertThat(DouyinEndpointRequestPolicy.forPath(
                "/aweme/v1/web/aweme/detail/"))
                .isEqualTo(DouyinEndpointRequestPolicy.SIGNED);
        assertThat(DouyinEndpointRequestPolicy.forPath(
                "/aweme/v1/web/aweme/post/"))
                .isEqualTo(DouyinEndpointRequestPolicy.SIGNED);
    }
}

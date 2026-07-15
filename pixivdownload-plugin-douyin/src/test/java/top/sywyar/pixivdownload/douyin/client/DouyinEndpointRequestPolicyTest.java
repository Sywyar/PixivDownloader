package top.sywyar.pixivdownload.douyin.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DouyinEndpointRequestPolicy 端点请求策略")
class DouyinEndpointRequestPolicyTest {

    @Test
    @DisplayName("通用搜索端点使用无签名请求")
    void usesUnsignedRequestForGeneralSearch() {
        assertThat(DouyinEndpointRequestPolicy.forPath(
                "/aweme/v1/web/general/search/single/"))
                .isEqualTo(DouyinEndpointRequestPolicy.UNSIGNED_GET)
                .satisfies(policy -> {
                    assertThat(policy.method()).isEqualTo(HttpMethod.GET);
                    assertThat(policy.requiresSignature()).isFalse();
                });
        assertThat(DouyinEndpointRequestPolicy.forPath(
                "aweme/v1/web/general/search/single/"))
                .isEqualTo(DouyinEndpointRequestPolicy.UNSIGNED_GET);
    }

    @Test
    @DisplayName("全部收藏作品端点使用签名 POST 请求")
    void usesSignedPostRequestForFavoriteWorks() {
        assertThat(DouyinEndpointRequestPolicy.forPath(
                "/aweme/v1/web/aweme/listcollection/"))
                .isEqualTo(DouyinEndpointRequestPolicy.SIGNED_POST)
                .satisfies(policy -> {
                    assertThat(policy.method()).isEqualTo(HttpMethod.POST);
                    assertThat(policy.requiresSignature()).isTrue();
                });
    }

    @Test
    @DisplayName("其它端点保持既有签名请求策略")
    void keepsSignedRequestsForOtherEndpoints() {
        assertThat(DouyinEndpointRequestPolicy.forPath(
                "/aweme/v1/web/aweme/detail/"))
                .isEqualTo(DouyinEndpointRequestPolicy.SIGNED_GET);
        assertThat(DouyinEndpointRequestPolicy.forPath(
                "/aweme/v1/web/aweme/post/"))
                .isEqualTo(DouyinEndpointRequestPolicy.SIGNED_GET)
                .satisfies(policy -> {
                    assertThat(policy.method()).isEqualTo(HttpMethod.GET);
                    assertThat(policy.requiresSignature()).isTrue();
                });
        assertThat(DouyinEndpointRequestPolicy.forPath(
                "/aweme/v1/web/collects/list/"))
                .isEqualTo(DouyinEndpointRequestPolicy.SIGNED_GET);
        assertThat(DouyinEndpointRequestPolicy.forPath(
                "/aweme/v1/web/collects/video/list/"))
                .isEqualTo(DouyinEndpointRequestPolicy.SIGNED_GET);
    }
}

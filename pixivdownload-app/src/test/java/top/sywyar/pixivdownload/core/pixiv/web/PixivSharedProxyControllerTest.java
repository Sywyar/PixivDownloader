package top.sywyar.pixivdownload.core.pixiv.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import top.sywyar.pixivdownload.core.pixiv.PixivAjaxClient;
import top.sywyar.pixivdownload.core.pixiv.PixivProxyAccessDecision;
import top.sywyar.pixivdownload.core.pixiv.PixivProxyAccessOutcome;
import top.sywyar.pixivdownload.core.pixiv.PixivProxyAccessPolicy;
import top.sywyar.pixivdownload.core.pixiv.thumbnail.PixivThumbnailFetcher;
import top.sywyar.pixivdownload.core.web.AcquisitionCredentialResolver;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentityResolver;
import top.sywyar.pixivdownload.testsupport.HostTestMessages;

import java.net.URI;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@DisplayName("宿主共用 Pixiv 账号查询")
class PixivSharedProxyControllerTest {
    private final PixivAjaxClient ajax = mock(PixivAjaxClient.class);
    private final PixivProxyAccessPolicy access = mock(PixivProxyAccessPolicy.class);
    private final RequestOwnerIdentityResolver identity = mock(RequestOwnerIdentityResolver.class);
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final PixivSharedProxyController controller = new PixivSharedProxyController(
            new ObjectMapper(), ajax, mock(PixivThumbnailFetcher.class), access, identity, HostTestMessages.messages());

    private void allow() {
        when(identity.resolveExistingOwnerUuid(request)).thenReturn(Optional.of("fixture-owner"));
        when(identity.isAdminAuthenticated(request)).thenReturn(true);
        when(access.evaluate("fixture-owner", true)).thenReturn(
                new PixivProxyAccessDecision(PixivProxyAccessOutcome.ALLOWED, null, 0, 0));
    }

    @Test
    @DisplayName("用户元数据沿用统一取得凭据且 URL 只编码一次")
    void sharedUserMetadataPreservesCredentialAndUri() throws Exception {
        allow();
        request.addHeader(AcquisitionCredentialResolver.HEADER_NAME, "fixture-credential");
        URI uri = URI.create("https://www.pixiv.net/ajax/user/test%20user?lang=zh");
        when(ajax.get(uri, "fixture-credential")).thenReturn("{\"body\":{\"name\":\"Fixture\"}}");
        var response = controller.getUserMeta("test user", null, request);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(new PixivSharedProxyController.UserMetaResponse("Fixture", "test user"));
        verify(ajax).get(uri, "fixture-credential");
    }

    @Test
    @DisplayName("游客限流先于上游请求并保留配额响应")
    void visitorLimitPreventsUpstreamRequest() throws Exception {
        when(access.evaluate(null, false)).thenReturn(
                new PixivProxyAccessDecision(PixivProxyAccessOutcome.RATE_LIMITED, "limited", 9, 2));
        var response = controller.getUserMeta("123", null, request);
        assertThat(response.getStatusCode().value()).isEqualTo(429);
        assertThat(response.getBody()).isEqualTo(new PixivSharedProxyController.ProxyRateLimitResponse("limited", 9, 2));
        verifyNoInteractions(ajax);
    }

    @Test
    @DisplayName("当前用户查询使用统一凭据，无凭据时拒绝且不访问网络")
    void currentUserUsesCredentialWithoutNetwork() {
        allow();
        request.addHeader(AcquisitionCredentialResolver.HEADER_NAME, "PHPSESSID=24680_fixture");
        assertThat(controller.getMeUid(null, request).getBody())
                .isEqualTo(new PixivSharedProxyController.MeUidResponse("24680"));
        request.removeHeader(AcquisitionCredentialResolver.HEADER_NAME);
        assertThat(controller.getMeUid(null, request).getStatusCode().value()).isEqualTo(401);
        verifyNoInteractions(ajax);
    }

    @Test
    @DisplayName("两种请求头凭据冲突时在权限查询和网络访问前拒绝")
    void conflictingCredentialsFailClosed() {
        request.addHeader(AcquisitionCredentialResolver.HEADER_NAME, "fixture-credential");
        assertThatThrownBy(() -> controller.getUserMeta("123", "other-credential", request))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controller.getMeUid("other-credential", request))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(ajax, access, identity);
    }
}

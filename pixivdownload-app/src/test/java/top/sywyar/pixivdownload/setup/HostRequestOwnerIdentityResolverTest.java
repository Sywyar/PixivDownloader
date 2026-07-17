package top.sywyar.pixivdownload.setup;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("宿主请求 owner 解析器")
class HostRequestOwnerIdentityResolverTest {

    @Test
    @DisplayName("admin / solo 请求解析为全 owner 作用域")
    void resolvesAdminScope() {
        SetupService setupService = mock(SetupService.class);
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(setupService.hasAdminScope(request)).thenReturn(true);

        RequestOwnerIdentity identity = new HostRequestOwnerIdentityResolver(setupService).resolve(request);

        assertThat(identity).isEqualTo(RequestOwnerIdentity.adminScope());
    }

    @Test
    @DisplayName("访客请求使用宿主 UUID 规则解析为单 owner 作用域")
    void resolvesVisitorOwnerScope() {
        SetupService setupService = mock(SetupService.class);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-UUID", "11111111-1111-1111-1111-111111111111");
        when(setupService.hasAdminScope(request)).thenReturn(false);

        RequestOwnerIdentity identity = new HostRequestOwnerIdentityResolver(setupService).resolve(request);

        assertThat(identity.admin()).isFalse();
        assertThat(identity.ownerUuid()).isEqualTo("11111111-1111-1111-1111-111111111111");
    }

    @Test
    @DisplayName("缺失 HTTP 请求时拒绝解析")
    void rejectsMissingRequest() {
        HostRequestOwnerIdentityResolver resolver =
                new HostRequestOwnerIdentityResolver(mock(SetupService.class));

        assertThatNullPointerException().isThrownBy(() -> resolver.resolve(null));
    }
}

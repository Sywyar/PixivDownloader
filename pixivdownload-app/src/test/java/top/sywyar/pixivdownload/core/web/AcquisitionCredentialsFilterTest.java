package top.sywyar.pixivdownload.core.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import top.sywyar.pixivdownload.config.credential.AcquisitionCredentialLibrary;
import top.sywyar.pixivdownload.plugin.api.download.context.AcquisitionCredentials;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentityResolver;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("多凭据请求鉴权与作用域")
class AcquisitionCredentialsFilterTest {
    private final AcquisitionCredentialLibrary library = mock(AcquisitionCredentialLibrary.class);
    private final RequestOwnerIdentityResolver identity = mock(RequestOwnerIdentityResolver.class);
    private final AcquisitionCredentialsFilter filter = new AcquisitionCredentialsFilter(library, identity,
            new top.sywyar.pixivdownload.i18n.AppMessages(new org.springframework.context.support.StaticMessageSource()),
            new top.sywyar.pixivdownload.i18n.AppLocaleResolver(top.sywyar.pixivdownload.i18n.LocaleCatalog.defaultCatalog()),
            new tools.jackson.databind.ObjectMapper());

    @Test @DisplayName("非管理员请求在读取凭据前拒绝，不能依靠请求体或选择头提升权限")
    void rejectsNonAdminBeforeReadingSecrets() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader(AcquisitionCredentialsFilter.HEADER, "a,b");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> fail("Must not enter controller"));
        assertThat(response.getStatus()).isEqualTo(403);
        verifyNoInteractions(library);
        assertThat(AcquisitionCredentials.current()).isNull();
    }

    @Test @DisplayName("只在当前已鉴权调用中解析候选，异常退出也不遗留凭据")
    void bindsOnlyThisRequest() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader(AcquisitionCredentialsFilter.HEADER, "b,a");
        when(identity.isAdminAuthenticated(request)).thenReturn(true);
        var credentials = new AcquisitionCredentials(List.of("second-secret", "first-secret"));
        when(library.resolve(List.of("b", "a"))).thenReturn(credentials);
        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            assertThat(AcquisitionCredentials.current()).isSameAs(credentials);
            assertThat(((jakarta.servlet.http.HttpServletRequest) req).getHeader(AcquisitionCredentialResolver.HEADER_NAME))
                    .isEqualTo("second-secret");
            throw new IllegalStateException("controlled failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(AcquisitionCredentials.current()).isNull();
    }

    @Test @DisplayName("多个选择头或同时提交原始凭据会在解析前拒绝")
    void rejectsAmbiguousCredentialInputs() throws Exception {
        for (boolean duplicate : List.of(true, false)) {
            var request = new MockHttpServletRequest();
            request.addHeader(AcquisitionCredentialsFilter.HEADER, "a");
            request.addHeader(duplicate ? AcquisitionCredentialsFilter.HEADER : AcquisitionCredentialResolver.HEADER_NAME, "b");
            when(identity.isAdminAuthenticated(request)).thenReturn(true);
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) -> fail("Must not enter controller"));
            assertThat(response.getStatus()).isEqualTo(400);
        }
        verifyNoInteractions(library);
    }
    @Test @DisplayName("凭据被删除或存储不可用时返回可暂停的机器码且不回显底层错误")
    void unavailableSelectionPausesWithoutDisclosingSecrets() throws Exception {
        for (Exception failure : List.of(new java.io.IOException("secret fixture"),
                new top.sywyar.pixivdownload.plugin.api.download.context.AcquisitionCredentialsUnavailableException(0))) {
            var request = new MockHttpServletRequest();
            request.addHeader(AcquisitionCredentialsFilter.HEADER, "removed");
            when(identity.isAdminAuthenticated(request)).thenReturn(true);
            doThrow(failure).when(library).resolve(List.of("removed"));
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) -> fail("Must not enter controller"));
            assertThat(response.getStatus()).isEqualTo(503);
            assertThat(response.getContentAsString()).contains("ACQUISITION_CREDENTIALS_UNAVAILABLE").doesNotContain("secret fixture");
            assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        }
    }

}

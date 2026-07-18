package top.sywyar.pixivdownload.plugin.api.web;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

@DisplayName("请求 owner 解析器稳定契约")
class RequestOwnerIdentityResolverTest {

    @Test
    @DisplayName("lambda 实现保持函数式接口且精确身份查询默认保守拒绝")
    void defaultIdentityQueriesAreConservative() {
        RequestOwnerIdentityResolver resolver = request -> RequestOwnerIdentity.owner("owner-a");
        HttpServletRequest request = requestStub();

        assertThat(resolver.resolve(request)).isEqualTo(RequestOwnerIdentity.owner("owner-a"));
        assertThat(resolver.resolveExistingOwnerUuid(request)).isEmpty();
        assertThat(resolver.resolveInvitedGuestRateLimitSubject(request)).isEmpty();
        assertThat(resolver.isAdminAuthenticated(request)).isFalse();
        assertThatNullPointerException()
                .isThrownBy(() -> resolver.resolveExistingOwnerUuid(null));
        assertThatNullPointerException()
                .isThrownBy(() -> resolver.resolveInvitedGuestRateLimitSubject(null));
        assertThatNullPointerException()
                .isThrownBy(() -> resolver.isAdminAuthenticated(null));
    }

    private static HttpServletRequest requestStub() {
        return (HttpServletRequest) Proxy.newProxyInstance(
                RequestOwnerIdentityResolverTest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class},
                (proxy, method, args) -> null);
    }
}

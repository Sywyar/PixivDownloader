package top.sywyar.pixivdownload.setup.guest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.sywyar.pixivdownload.core.work.model.WorkRestriction;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.core.work.model.WorkVisibilityScope;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("作品可见性作用域 MVC 参数解析")
class GuestWorkVisibilityScopeArgumentResolverTest {

    private static final GuestInviteSession SESSION = new GuestInviteSession(
            7L, "invite-code",
            true, false, true,
            false, Set.of(11L, 12L),
            true, Set.of(),
            true, Set.of(),
            false, Set.of(33L));

    private final GuestWorkVisibilityScopeArgumentResolver resolver =
            new GuestWorkVisibilityScopeArgumentResolver(new GuestWorkVisibilityScopeFactory());

    @Test
    @DisplayName("只解析精确的作品可见性作用域参数")
    void supportsOnlyWorkVisibilityScopeParameters() throws Exception {
        assertThat(resolver.supportsParameter(parameter("scope", WorkVisibilityScope.class))).isTrue();
        assertThat(resolver.supportsParameter(parameter("text", String.class))).isFalse();
    }

    @Test
    @DisplayName("从当前请求的邀请会话解析插画与小说限制")
    void resolvesRestrictionsFromCurrentRequestSession() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(GuestInviteSession.REQUEST_ATTR, SESSION);

        WorkVisibilityScope scope = (WorkVisibilityScope) resolver.resolveArgument(
                parameter("scope", WorkVisibilityScope.class),
                null,
                new ServletWebRequest(request),
                null);

        assertThat(scope.enforceVisibility()).isTrue();
        assertThat(scope.restrictionFor(WorkType.ARTWORK).allowedXRestricts()).containsExactlyInAnyOrder(0, 2);
        assertThat(scope.restrictionFor(WorkType.ARTWORK).tagIds()).containsExactlyInAnyOrder(11L, 12L);
        assertThat(scope.restrictionFor(WorkType.ARTWORK).authorUnrestricted()).isTrue();
        assertThat(scope.restrictionFor(WorkType.NOVEL).tagUnrestricted()).isTrue();
        assertThat(scope.restrictionFor(WorkType.NOVEL).authorIds()).containsExactly(33L);
    }

    @Test
    @DisplayName("MVC 配置注册解析器并驱动真实控制器参数绑定")
    void webConfigurationRegistersResolverForMockMvcBinding() throws Exception {
        List<HandlerMethodArgumentResolver> resolvers = new ArrayList<>();
        new GuestWorkVisibilityWebConfiguration(resolver).addArgumentResolvers(resolvers);

        assertThat(resolvers).containsExactly(resolver);

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ScopeController())
                .setCustomArgumentResolvers(resolvers.toArray(HandlerMethodArgumentResolver[]::new))
                .build();

        mockMvc.perform(get("/test/work-visibility-scope"))
                .andExpect(status().isOk())
                .andExpect(content().string("unrestricted"));

        mockMvc.perform(get("/test/work-visibility-scope")
                        .requestAttr(GuestInviteSession.REQUEST_ATTR, SESSION))
                .andExpect(status().isOk())
                .andExpect(content().string("restricted:artwork-tag-11:novel-author-33"));
    }

    private static MethodParameter parameter(String methodName, Class<?> parameterType) throws Exception {
        Method method = ScopeController.class.getDeclaredMethod(methodName, parameterType);
        return new MethodParameter(method, 0);
    }

    @RestController
    private static final class ScopeController {

        @GetMapping("/test/work-visibility-scope")
        String scope(WorkVisibilityScope scope) {
            if (!scope.enforceVisibility()) {
                return "unrestricted";
            }
            WorkRestriction artwork = scope.restrictionFor(WorkType.ARTWORK);
            WorkRestriction novel = scope.restrictionFor(WorkType.NOVEL);
            return "restricted:"
                    + (artwork.tagIds().contains(11L) ? "artwork-tag-11" : "artwork-tag-missing")
                    + ":"
                    + (novel.authorIds().contains(33L) ? "novel-author-33" : "novel-author-missing");
        }

        @SuppressWarnings("unused")
        void text(String value) {
        }
    }
}

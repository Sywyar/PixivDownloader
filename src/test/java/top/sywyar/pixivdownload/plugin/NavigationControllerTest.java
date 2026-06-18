package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
import top.sywyar.pixivdownload.plugin.api.web.NavigationContribution;
import top.sywyar.pixivdownload.setup.SetupService;
import top.sywyar.pixivdownload.setup.guest.GuestInviteSession;

import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("NavigationController /api/navigation 可见性过滤")
class NavigationControllerTest {

    private final SetupService setupService = mock(SetupService.class);
    private final NavigationRegistry registry = seededRegistry();
    private final NavigationController controller = new NavigationController(registry, setupService);

    /** 注册顺序刻意与 order 字段不一致，以便验证响应按 order 排序而非注册顺序。 */
    private static NavigationRegistry seededRegistry() {
        NavigationRegistry registry = new NavigationRegistry(new PluginRegistry(List.of()));
        registry.register("plug", List.of(
                new NavigationContribution("p3", "nav.p3", "/p3.html", "i", AccessPolicy.PUBLIC, 30),
                new NavigationContribution("p1", "nav.p1", "/p1.html", "i", AccessPolicy.PUBLIC, 10),
                new NavigationContribution("g2", "nav.g2", "/g2.html", "i", AccessPolicy.INVITED_GUEST, 20),
                new NavigationContribution("a1", "nav.a1", "/a1.html", "i", AccessPolicy.ADMIN, 5)));
        return registry;
    }

    private static GuestInviteSession guestSession() {
        return new GuestInviteSession(1L, "code", true, false, false,
                true, Set.of(), true, Set.of(), true, Set.of(), true, Set.of());
    }

    @Test
    @DisplayName("管理员范围可见全部导航项并按 order 升序排序")
    void adminSeesAllSortedByOrder() {
        when(setupService.hasAdminScope(any())).thenReturn(true);
        MockHttpServletRequest request = new MockHttpServletRequest();

        List<NavigationController.NavigationView> result = controller.navigation(request);

        assertThat(result).extracting(NavigationController.NavigationView::id)
                .containsExactly("a1", "p1", "g2", "p3");
    }

    @Test
    @DisplayName("访客邀请会话仅可见 PUBLIC 与 INVITED_GUEST（访客判定优先于管理员范围）")
    void guestSeesPublicAndGuestRead() {
        // solo 模式下 hasAdminScope 对任意请求为真：访客判定必须优先，否则访客会看到 admin 项
        when(setupService.hasAdminScope(any())).thenReturn(true);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(GuestInviteSession.REQUEST_ATTR, guestSession());

        List<NavigationController.NavigationView> result = controller.navigation(request);

        assertThat(result).extracting(NavigationController.NavigationView::id)
                .containsExactly("p1", "g2", "p3");
    }

    @Test
    @DisplayName("匿名请求（multi 非管理员）仅可见 PUBLIC 导航项")
    void anonymousSeesOnlyPublic() {
        when(setupService.hasAdminScope(any())).thenReturn(false);
        MockHttpServletRequest request = new MockHttpServletRequest();

        List<NavigationController.NavigationView> result = controller.navigation(request);

        assertThat(result).extracting(NavigationController.NavigationView::id)
                .containsExactly("p1", "p3");
    }

    @Test
    @DisplayName("视图只暴露渲染字段（id/labelI18nKey/href/icon/order），刻意不含 visibleTo")
    void viewExposesOnlyRenderFields() {
        when(setupService.hasAdminScope(any())).thenReturn(true);
        MockHttpServletRequest request = new MockHttpServletRequest();

        NavigationController.NavigationView first = controller.navigation(request).get(0);

        assertThat(first.id()).isEqualTo("a1");
        assertThat(first.labelI18nKey()).isEqualTo("nav.a1");
        assertThat(first.href()).isEqualTo("/a1.html");
        assertThat(first.icon()).isEqualTo("i");
        assertThat(first.order()).isEqualTo(5);
        assertThat(NavigationController.NavigationView.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly("id", "labelI18nKey", "href", "icon", "order");
    }
}

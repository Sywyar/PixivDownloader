package top.sywyar.pixivdownload.setup.guest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.core.work.model.WorkVisibilityScope;
import top.sywyar.pixivdownload.core.work.service.WorkVisibilityService;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("访客作品可见性请求适配器")
class GuestAccessGuardTest {

    private GuestWorkVisibilityScopeFactory scopeFactory;
    private WorkVisibilityService workVisibilityService;
    private GuestAccessGuard guard;

    @BeforeEach
    void setUp() {
        scopeFactory = mock(GuestWorkVisibilityScopeFactory.class);
        workVisibilityService = mock(WorkVisibilityService.class);
        guard = new GuestAccessGuard(scopeFactory, workVisibilityService);
    }

    @Test
    @DisplayName("请求守卫只把宿主请求投影为作用域后委托领域服务")
    void requestGuardDelegatesWithTrustedScope() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        WorkVisibilityScope scope = WorkVisibilityScope.unrestricted();
        when(scopeFactory.fromRequest(request)).thenReturn(scope);

        guard.requireVisible(request, 42L);
        guard.requireNovelVisible(request, 43L);

        verify(workVisibilityService).requireVisible(scope, WorkType.ARTWORK, 42L);
        verify(workVisibilityService).requireVisible(scope, WorkType.NOVEL, 43L);
    }

    @Test
    @DisplayName("既有会话判定入口复用同一作用域与领域服务")
    void sessionVisibilityDelegatesToDomainService() {
        GuestInviteSession session = new GuestInviteSession(
                1L, "invite", true, false, false,
                true, Set.of(), true, Set.of(),
                true, Set.of(), true, Set.of());
        WorkVisibilityScope scope = WorkVisibilityScope.unrestricted();
        when(scopeFactory.fromSession(session)).thenReturn(scope);
        when(workVisibilityService.isVisible(scope, WorkType.ARTWORK, 42L)).thenReturn(true);
        when(workVisibilityService.isVisible(scope, WorkType.NOVEL, 43L)).thenReturn(false);

        assertThat(guard.isVisibleToGuest(42L, session)).isTrue();
        assertThat(guard.isNovelVisibleToGuest(43L, session)).isFalse();
    }
}

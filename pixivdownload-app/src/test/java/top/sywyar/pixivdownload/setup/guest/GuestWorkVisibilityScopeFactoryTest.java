package top.sywyar.pixivdownload.setup.guest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import top.sywyar.pixivdownload.core.metadata.GuestRestriction;
import top.sywyar.pixivdownload.core.work.model.WorkRestriction;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.core.work.model.WorkVisibilityScope;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("访客作品可见性作用域投影")
class GuestWorkVisibilityScopeFactoryTest {

    private static final GuestInviteSession SESSION = new GuestInviteSession(
            1L, "code",
            true, false, true,
            false, Set.of(11L),
            true, Set.of(),
            true, Set.of(),
            false, Set.of(33L));

    private final GuestWorkVisibilityScopeFactory factory = new GuestWorkVisibilityScopeFactory();

    @Test
    @DisplayName("无邀请会话时返回共享无限制作用域")
    void requestWithoutSessionIsUnrestricted() {
        WorkVisibilityScope scope = factory.fromRequest(new MockHttpServletRequest());

        assertThat(scope).isSameAs(WorkVisibilityScope.unrestricted());
        assertThat(scope.restrictionFor(WorkType.ARTWORK)).isNull();
        assertThat(scope.restrictionFor(WorkType.NOVEL)).isNull();
    }

    @Test
    @DisplayName("邀请会话同时投影插画与小说的独立限制")
    void requestWithSessionProjectsBothWorkTypes() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(GuestInviteSession.REQUEST_ATTR, SESSION);

        WorkVisibilityScope scope = factory.fromRequest(request);

        assertThat(scope.enforceVisibility()).isTrue();
        assertMatches(GuestRestriction.from(SESSION), scope.restrictionFor(WorkType.ARTWORK));
        assertMatches(GuestRestriction.forNovel(SESSION), scope.restrictionFor(WorkType.NOVEL));
    }

    private static void assertMatches(GuestRestriction expected, WorkRestriction actual) {
        assertThat(actual.allowedXRestricts()).isEqualTo(expected.allowedXRestricts());
        assertThat(actual.tagUnrestricted()).isEqualTo(expected.tagUnrestricted());
        assertThat(actual.tagIds()).containsExactlyInAnyOrderElementsOf(expected.tagIds());
        assertThat(actual.authorUnrestricted()).isEqualTo(expected.authorUnrestricted());
        assertThat(actual.authorIds()).containsExactlyInAnyOrderElementsOf(expected.authorIds());
        assertThat(actual.fullyOpen()).isEqualTo(expected.fullyOpen());
    }
}

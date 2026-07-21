package top.sywyar.pixivdownload.plugin.api.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AccessPolicy 页面身份投影契约")
class AccessPolicyTest {

    @Test
    @DisplayName("页面身份只保留当前请求能够真实解析出的游客、受邀访客和管理员")
    void audienceCatalogMatchesCurrentRequestIdentities() {
        assertThat(Audience.values())
                .containsExactly(Audience.VISITOR, Audience.INVITED_GUEST, Audience.ADMIN);
    }

    @Test
    @DisplayName("只有五种常规策略可用于 UI 可见性，流程专用策略不参与投影")
    void uiVisibilityPolicyCatalogIsExact() {
        assertThat(Arrays.stream(AccessPolicy.values())
                .filter(AccessPolicy::supportsUiVisibility))
                .containsExactly(
                        AccessPolicy.PUBLIC,
                        AccessPolicy.VISITOR,
                        AccessPolicy.VISITOR_AND_INVITED_GUEST,
                        AccessPolicy.INVITED_GUEST,
                        AccessPolicy.ADMIN);
        assertThat(Arrays.stream(AccessPolicy.values())
                .filter(policy -> !policy.supportsUiVisibility()))
                .containsExactly(AccessPolicy.LOCAL, AccessPolicy.GUI, AccessPolicy.ACTUATOR_PUBLIC);
    }

    @Test
    @DisplayName("常规策略对三种页面身份的可见性矩阵保持现有语义")
    void uiVisibilityMatrixMatchesCurrentBehavior() {
        assertVisibleTo(AccessPolicy.PUBLIC,
                Audience.VISITOR, Audience.INVITED_GUEST, Audience.ADMIN);
        assertVisibleTo(AccessPolicy.VISITOR, Audience.VISITOR, Audience.ADMIN);
        assertVisibleTo(AccessPolicy.VISITOR_AND_INVITED_GUEST,
                Audience.VISITOR, Audience.INVITED_GUEST, Audience.ADMIN);
        assertVisibleTo(AccessPolicy.INVITED_GUEST, Audience.INVITED_GUEST, Audience.ADMIN);
        assertVisibleTo(AccessPolicy.ADMIN, Audience.ADMIN);
    }

    @Test
    @DisplayName("流程专用策略与空身份均拒绝被误当作 UI 可见性")
    void invalidUiProjectionFailsFast() {
        for (AccessPolicy policy : new AccessPolicy[]{
                AccessPolicy.LOCAL, AccessPolicy.GUI, AccessPolicy.ACTUATOR_PUBLIC}) {
            assertThatThrownBy(() -> policy.isVisibleTo(Audience.ADMIN))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(policy.name());
        }
        assertThatThrownBy(() -> AccessPolicy.PUBLIC.isVisibleTo(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("audience");
    }

    private static void assertVisibleTo(AccessPolicy policy, Audience... visibleAudiences) {
        assertThat(Arrays.stream(Audience.values())
                .filter(policy::isVisibleTo))
                .containsExactly(visibleAudiences);
    }
}

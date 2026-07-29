package top.sywyar.pixivdownload.plugin.api.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Web 路由 contribution 纯数据契约")
class WebRouteContributionTest {

    @Test
    @DisplayName("第三方便利工厂不暴露宿主专用 actuator 策略")
    void namedFactoriesExcludeHostOnlyActuatorPolicy() {
        assertThat(Arrays.stream(WebRouteContribution.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> Modifier.isStatic(method.getModifiers()))
                .filter(method -> method.getReturnType() == WebRouteContribution.class)
                .filter(WebRouteContributionTest::acceptsOnlyPathPattern)
                .map(Method::getName))
                .containsExactlyInAnyOrder(
                        "publicRoute",
                        "visitor",
                        "visitorAndInvitedGuest",
                        "invitedGuest",
                        "admin",
                        "local",
                        "gui")
                .doesNotContain("actuatorPublic");
    }

    private static boolean acceptsOnlyPathPattern(Method method) {
        return Arrays.equals(method.getParameterTypes(), new Class<?>[]{String.class});
    }
}

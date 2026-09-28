package top.sywyar.pixivdownload.plugin.api.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Web 路由 contribution 纯数据契约")
class WebRouteContributionTest {

    @ParameterizedTest
    @CsvSource({
            "/api/files/**, /api/files/, true",
            "/api/files/**, /api/files/a/b, true",
            "/api/files/**, /api/files, false",
            "/api/files/**, /api/filesOther/a, false",
            "/api/files/**, /API/files/a, false",
            "/api/author**, /api/authors/1, true",
            "/api/author**, /api/auth, false",
            "/图像/**, /图像/一, true",
            "/图像/**, /图文/一, false",
            "**, '', true",
            "**, /any, true",
            "/api/exact, /api/exact, true",
            "/api/exact, /api/exact/extra, false",
            "/api/*/status, /api/one/status, true",
            "/api/*/status, /api/one/two/status, false"
    })
    @DisplayName("路由匹配保留前缀长度、斜杠、大小写、Unicode 与路径段语义")
    void preservesPathBoundaries(String pattern, String path, boolean expected) {
        assertThat(WebRouteContribution.admin(pattern).matches(path)).isEqualTo(expected);
        assertThat(WebRouteContribution.admin(pattern).matches(null)).isFalse();
    }

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

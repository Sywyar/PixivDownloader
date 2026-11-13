package top.sywyar.pixivdownload.plugin.api.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

@DisplayName("程序权限显式准入")
class ProgramPermissionTest {
    private final ProgramPermission read = new ProgramPermission("items.read", "permissions.items.read");

    @Test
    @DisplayName("程序权限不能覆盖未来子路由或 GUI 专用流程")
    void rejectsBroadAndPrivilegedRoutes() {
        for (var route : new WebRouteContribution[] {
                WebRouteContribution.admin("/api/items/**"), WebRouteContribution.gui("/api/gui/items"),
                WebRouteContribution.local("/api/items"), WebRouteContribution.admin("/items.html")}) {
            assertThatIllegalArgumentException().isThrownBy(() -> route.withProgramPermissions(Map.of(HttpMethod.GET, read)));
        }
        assertThatIllegalArgumentException().isThrownBy(() -> new ProgramPermission("*", "permissions.all"));
    }

    @Test
    @DisplayName("授权方法必须被路由接受且不会隐式授予写入")
    void boundsMethodsAndFreezesMapping() {
        var grants = new java.util.HashMap<HttpMethod, ProgramPermission>();
        grants.put(HttpMethod.GET, read);
        var route = new WebRouteContribution("/api/items/*", AccessPolicy.ADMIN, Set.of(HttpMethod.GET), false)
                .withProgramPermissions(grants);
        grants.put(HttpMethod.POST, read);
        assertThat(route.programPermissions()).containsOnlyKeys(HttpMethod.GET);
        assertThatIllegalArgumentException().isThrownBy(() -> route.withProgramPermissions(grants));
        assertThatIllegalArgumentException().isThrownBy(() -> WebRouteContribution.admin("/api/items")
                .withProgramPermissions(Map.of(HttpMethod.OPTIONS, read)));
    }
}

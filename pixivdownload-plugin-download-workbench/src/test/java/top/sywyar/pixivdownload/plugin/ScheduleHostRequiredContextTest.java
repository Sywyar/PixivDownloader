package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.download.DownloadWorkbenchPlugin;
import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
import top.sywyar.pixivdownload.plugin.api.web.HttpMethod;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("下载工作台只贡献 Pixiv 计划兼容入口")
class ScheduleHostRequiredContextTest {
    @Test
    @DisplayName("通用管理面不随工作台撤回，兼容动作仍要求管理员")
    void onlyPixivCompatibilityRoutesBelongToWorkbench() {
        var routes = new DownloadWorkbenchPlugin().routes();
        assertThat(routes.stream().filter(route -> route.matches("/api/schedule/tasks"))).isEmpty();
        for (String path : new String[]{"/api/schedule/tasks/1/authorize-cookie",
                "/api/schedule/tasks/1/revoke-cookie", "/api/schedule/account/1/resume"}) {
            assertThat(routes.stream().filter(route -> route.matches(path)
                    && route.acceptsMethod(HttpMethod.POST)))
                    .singleElement().satisfies(route ->
                            assertThat(route.accessPolicy()).isEqualTo(AccessPolicy.ADMIN));
        }
    }
}

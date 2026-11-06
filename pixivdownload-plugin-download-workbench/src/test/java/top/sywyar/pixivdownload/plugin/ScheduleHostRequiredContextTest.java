package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.download.DownloadWorkbenchPlugin;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("计划管理路由由宿主提供")
class ScheduleHostRequiredContextTest {
    @Test
    @DisplayName("工作台不再贡献来源专用的计划管理路由")
    void scheduleManagementRoutesBelongToHost() {
        var routes = new DownloadWorkbenchPlugin().routes();
        for (String path : new String[]{"/api/schedule/tasks",
                "/api/schedule/tasks/1/credential", "/api/schedule/credential-policies/actions",
                "/api/schedule/tasks/1/authorize-cookie", "/api/schedule/tasks/1/revoke-cookie",
                "/api/schedule/account/1/resume"}) {
            assertThat(routes.stream().filter(route -> route.matches(path))).isEmpty();
        }
    }
}

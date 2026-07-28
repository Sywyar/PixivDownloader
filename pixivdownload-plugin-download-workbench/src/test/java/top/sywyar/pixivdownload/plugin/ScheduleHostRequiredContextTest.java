package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.download.DownloadWorkbenchPlugin;
import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
import top.sywyar.pixivdownload.plugin.api.web.HttpMethod;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 计划任务宿主能力已并入 download-workbench 外置 required 包；核心壳不再携带独立 schedule 插件。
 */
@DisplayName("schedule 宿主随 download-workbench 外置包贡献")
class ScheduleHostRequiredContextTest {

    @Test
    @DisplayName("schedule 管理路由由 download-workbench 声明，不再存在独立 schedule 插件 id")
    void scheduleRoutesOwnedByDownloadWorkbench() {
        DownloadWorkbenchPlugin plugin = new DownloadWorkbenchPlugin();
        List<WebRouteContribution> scheduleRoutes = plugin.routes().stream()
                .filter(route -> route.matches("/api/schedule/tasks")
                        && route.acceptsMethod(HttpMethod.GET))
                .toList();

        assertThat(plugin.id()).isEqualTo("download-workbench").isNotEqualTo("schedule");
        assertThat(scheduleRoutes).singleElement().satisfies(route -> {
            assertThat(route.pathPattern()).isEqualTo("/api/schedule/**");
            assertThat(route.accessPolicy()).isEqualTo(AccessPolicy.ADMIN);
            assertThat(route.acceptsMethod(HttpMethod.POST)).isTrue();
        });
    }
}

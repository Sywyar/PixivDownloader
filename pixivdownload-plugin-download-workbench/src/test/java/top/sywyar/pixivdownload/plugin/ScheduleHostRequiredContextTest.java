package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.download.DownloadWorkbenchPlugin;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.RouteAccessRegistry;

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
        RouteAccessRegistry registry = new RouteAccessRegistry(
                new PluginRegistry(List.of(new DownloadWorkbenchPlugin())));

        assertThat(registry.isDeclared("/api/schedule/tasks")).isTrue();
        assertThat(registry.routes())
                .filteredOn(route -> route.route().matches("/api/schedule/tasks"))
                .extracting(RouteAccessRegistry.RegisteredRoute::pluginId)
                .containsOnly("download-workbench");
        assertThat(registry.routes())
                .extracting(RouteAccessRegistry.RegisteredRoute::pluginId)
                .doesNotContain("schedule");
    }
}

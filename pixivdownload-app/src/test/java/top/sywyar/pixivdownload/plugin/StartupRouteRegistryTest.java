package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.web.StartupRouteContribution;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.StartupRouteRegistry;

@DisplayName("默认启动落点注册中心")
class StartupRouteRegistryTest {

    private static StartupRouteRegistry emptyRegistry() {
        return new StartupRouteRegistry(new PluginRegistry(List.of()));
    }

    @Test
    @DisplayName("内置插件清单注册后：multi 首选下载工作台、solo 首选画廊")
    void builtInResolvesPreferredPerMode() {
        StartupRouteRegistry registry = StartupRouteRegistry.forBuiltInPlugins();

        assertThat(registry.resolvePath("download-workbench")).contains("/pixiv-batch.html");
        assertThat(registry.resolvePath("gallery")).contains("/pixiv-gallery.html");
    }

    @Test
    @DisplayName("首选插件缺失时回退到 order 最小的已注册落点")
    void fallsBackToLowestOrderWhenPreferredAbsent() {
        StartupRouteRegistry registry = emptyRegistry();
        registry.register("gallery", List.of(new StartupRouteContribution("gallery", "/pixiv-gallery.html", 20)));
        registry.register("download-workbench",
                List.of(new StartupRouteContribution("download-workbench", "/pixiv-batch.html", 10)));

        // 未声明落点的插件 id → 回退到 order 最小者（download-workbench order=10）
        assertThat(registry.resolvePath("stats")).contains("/pixiv-batch.html");
        // null 首选 → 同样走回退
        assertThat(registry.resolvePath(null)).contains("/pixiv-batch.html");
    }

    @Test
    @DisplayName("禁用下载工作台（注销）后默认落点自动落到其他已启用插件（画廊）")
    void fallsBackToGalleryWhenDownloadWorkbenchUnregistered() {
        StartupRouteRegistry registry = StartupRouteRegistry.forBuiltInPlugins();
        assertThat(registry.resolvePath("download-workbench")).contains("/pixiv-batch.html");

        registry.unregister("download-workbench");

        // multi 首选下载工作台已缺失 → 回退到 order 最小的已注册落点（画廊 order=20）
        assertThat(registry.resolvePath("download-workbench")).contains("/pixiv-gallery.html");
        assertThat(registry.resolvePath("gallery")).contains("/pixiv-gallery.html");
    }

    @Test
    @DisplayName("无任何落点时解析返回空（由调用方兜底）")
    void emptyRegistryResolvesToEmpty() {
        StartupRouteRegistry registry = emptyRegistry();
        assertThat(registry.resolvePath("download-workbench")).isEmpty();
        assertThat(registry.resolvePath(null)).isEmpty();
    }

    @Test
    @DisplayName("注册 → 注销 → 再注册后状态与首次一致（可逆性）")
    void registerUnregisterReversible() {
        StartupRouteRegistry registry = emptyRegistry();
        StartupRouteContribution route = new StartupRouteContribution("download-workbench", "/pixiv-batch.html", 10);

        registry.register("download-workbench", List.of(route));
        assertThat(registry.startupRoutes()).hasSize(1);

        registry.unregister("download-workbench");
        assertThat(registry.startupRoutes()).isEmpty();
        assertThat(registry.resolvePath("download-workbench")).isEmpty();

        registry.register("download-workbench", List.of(route));
        assertThat(registry.startupRoutes()).hasSize(1);
        assertThat(registry.resolvePath("download-workbench")).contains("/pixiv-batch.html");
    }

    @Test
    @DisplayName("未注册过的 pluginId 注销静默返回")
    void unregisterUnknownIsSilent() {
        StartupRouteRegistry registry = emptyRegistry();
        registry.unregister("never-registered");
        assertThat(registry.startupRoutes()).isEmpty();
    }

    @Test
    @DisplayName("同一 pluginId 重复注册即抛错")
    void duplicatePluginRegistrationThrows() {
        StartupRouteRegistry registry = emptyRegistry();
        registry.register("download-workbench",
                List.of(new StartupRouteContribution("download-workbench", "/pixiv-batch.html", 10)));

        assertThatThrownBy(() -> registry.register("download-workbench",
                List.of(new StartupRouteContribution("download-workbench", "/other.html", 11))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("非法落点（pluginId 不符 / 路径非法）注册即抛错")
    void invalidContributionThrows() {
        StartupRouteRegistry registry = emptyRegistry();

        // pluginId 与声明方不一致
        assertThatThrownBy(() -> registry.register("download-workbench",
                List.of(new StartupRouteContribution("gallery", "/pixiv-batch.html", 10))))
                .isInstanceOf(IllegalStateException.class);

        // 路径非法（不以 / 开头）
        assertThatThrownBy(() -> registry.register("download-workbench",
                List.of(new StartupRouteContribution("download-workbench", "pixiv-batch.html", 10))))
                .isInstanceOf(IllegalStateException.class);

        // 空来源列表
        assertThatThrownBy(() -> registry.register("download-workbench", List.of()))
                .isInstanceOf(IllegalStateException.class);
    }
}

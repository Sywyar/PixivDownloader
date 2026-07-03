package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.web.StartupRouteContribution;
import top.sywyar.pixivdownload.plugin.api.web.StartupRouteContext;

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
    @DisplayName("gallery 已安装时：download-workbench 缺席时 multi/solo 均回退到画廊")
    void builtInResolvesPreferredPerMode() {
        StartupRouteRegistry registry = registryWithGallery();

        assertThat(registry.resolvePath(StartupRouteContext.MULTI)).contains("/pixiv-gallery.html");
        assertThat(registry.resolvePath(StartupRouteContext.SOLO)).contains("/pixiv-gallery.html");
    }

    @Test
    @DisplayName("首选插件缺失时回退到 order 最小的已注册落点")
    void fallsBackToLowestOrderWhenPreferredAbsent() {
        StartupRouteRegistry registry = emptyRegistry();
        registry.register("gallery", List.of(new StartupRouteContribution("gallery", "/pixiv-gallery.html", 20)));
        registry.register("download-workbench",
                List.of(new StartupRouteContribution("download-workbench", "/pixiv-batch.html", 10)));

        // 未声明首选上下文 → 回退到 order 最小者（download-workbench order=10）
        assertThat(registry.resolvePath(StartupRouteContext.MULTI)).contains("/pixiv-batch.html");
        // null 上下文 → 同样走回退
        assertThat(registry.resolvePath((StartupRouteContext) null)).contains("/pixiv-batch.html");
    }

    @Test
    @DisplayName("注册再注销下载工作台后默认落点自动落到其他已启用插件（画廊）")
    void fallsBackToGalleryWhenDownloadWorkbenchUnregistered() {
        StartupRouteRegistry registry = registryWithGallery();
        registry.register("download-workbench",
                List.of(new StartupRouteContribution("download-workbench", "/pixiv-batch.html", 10,
                        java.util.Set.of(StartupRouteContext.MULTI))));
        assertThat(registry.resolvePath(StartupRouteContext.MULTI)).contains("/pixiv-batch.html");

        registry.unregister("download-workbench");

        // multi 首选上下文已缺失 → 回退到 order 最小的已注册落点（画廊 order=20）
        assertThat(registry.resolvePath(StartupRouteContext.MULTI)).contains("/pixiv-gallery.html");
        assertThat(registry.resolvePath(StartupRouteContext.SOLO)).contains("/pixiv-gallery.html");
    }

    @Test
    @DisplayName("无任何落点时解析返回空（由调用方兜底）")
    void emptyRegistryResolvesToEmpty() {
        StartupRouteRegistry registry = emptyRegistry();
        assertThat(registry.resolvePath(StartupRouteContext.MULTI)).isEmpty();
        assertThat(registry.resolvePath((StartupRouteContext) null)).isEmpty();
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
        assertThat(registry.resolvePath(StartupRouteContext.MULTI)).isEmpty();

        registry.register("download-workbench", List.of(route));
        assertThat(registry.startupRoutes()).hasSize(1);
        assertThat(registry.resolvePath(StartupRouteContext.MULTI)).contains("/pixiv-batch.html");
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

    private static StartupRouteRegistry registryWithGallery() {
        java.util.ArrayList<top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin> plugins =
                new java.util.ArrayList<>(BuiltInPlugins.createAll());
        plugins.add(new TestGalleryPlugin());
        return new StartupRouteRegistry(new PluginRegistry(plugins));
    }
}

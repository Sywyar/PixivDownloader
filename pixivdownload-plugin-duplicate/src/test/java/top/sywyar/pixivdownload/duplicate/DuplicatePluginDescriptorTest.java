package top.sywyar.pixivdownload.duplicate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("duplicate 外置插件描述测试")
class DuplicatePluginDescriptorTest {

    @Test
    @DisplayName("PF4J 主类暴露 duplicate 功能插件与子上下文配置类")
    void pf4jProviderExposesFeaturePluginAndConfiguration() {
        DuplicatePf4jPlugin provider = new DuplicatePf4jPlugin();

        assertThat(provider.featurePlugins()).singleElement()
                .isInstanceOf(DuplicatePlugin.class)
                .extracting(plugin -> plugin.id())
                .isEqualTo("duplicate");
        assertThat(provider.configurationClasses()).containsExactly(DuplicatePluginConfiguration.class);
    }

    @Test
    @DisplayName("duplicate 声明管理员路由、页面资源、i18n、导航与维护任务")
    void duplicatePluginDeclaresWebAndMaintenanceContributions() {
        DuplicatePlugin plugin = new DuplicatePlugin();

        assertThat(plugin.routes())
                .extracting(route -> route.pathPattern() + "|" + route.accessPolicy())
                .containsExactlyInAnyOrder(
                        "/pixiv-duplicates.html|" + AccessPolicy.ADMIN,
                        "/pixiv-duplicates/**|" + AccessPolicy.ADMIN,
                        "/api/duplicates/**|" + AccessPolicy.ADMIN);
        assertThat(plugin.staticResources())
                .extracting(resource -> resource.publicPathPrefix() + "|" + resource.exactFile())
                .containsExactlyInAnyOrder("/pixiv-duplicates.html|true", "/pixiv-duplicates/|false");
        assertThat(plugin.i18n()).singleElement()
                .satisfies(bundle -> {
                    assertThat(bundle.namespace()).isEqualTo("duplicates");
                    assertThat(bundle.baseName()).isEqualTo("i18n.web.duplicates");
                });
        assertThat(plugin.navigation()).singleElement()
                .satisfies(nav -> {
                    assertThat(nav.id()).isEqualTo("duplicate");
                    assertThat(nav.visibleTo()).isEqualTo(AccessPolicy.ADMIN);
                    assertThat(nav.href()).isEqualTo("/pixiv-duplicates.html");
                });
        assertThat(plugin.maintenanceTasks()).containsExactly(DuplicateHashBackfillTask.class);
    }

    @Test
    @DisplayName("外置资源只能从 duplicate 模块 classpath 解析")
    void moduleClasspathCarriesDuplicateResources() {
        ClassLoader loader = getClass().getClassLoader();

        assertThat(loader.getResource("plugin.properties")).isNotNull();
        assertThat(loader.getResource("static/pixiv-duplicates.html")).isNotNull();
        assertThat(loader.getResource("static/pixiv-duplicates/pixiv-duplicates.css")).isNotNull();
        assertThat(loader.getResource("i18n/web/duplicates.properties")).isNotNull();
        assertThat(loader.getResource("i18n/web/duplicates_en.properties")).isNotNull();
    }
}

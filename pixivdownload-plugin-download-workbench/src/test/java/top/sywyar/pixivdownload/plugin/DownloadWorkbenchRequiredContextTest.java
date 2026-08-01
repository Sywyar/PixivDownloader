package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.download.DownloadWorkbenchPlugin;
import top.sywyar.pixivdownload.download.schedule.source.descriptor.PixivScheduledSourceDescriptors;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceDescriptor;
import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
import top.sywyar.pixivdownload.plugin.api.web.HttpMethod;
import top.sywyar.pixivdownload.plugin.api.web.StartupRouteContext;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;

import java.io.InputStream;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 下载工作台作为宿主策略 required 的外置 PF4J 插件时的 contribution 契约。这里不加载 app 的
 * {@code SpringBootTest} 上下文，避免外置模块出现在测试 classpath 后被 app 根包扫描成内置 controller。
 */
@DisplayName("download-workbench 外置 required 插件贡献契约")
class DownloadWorkbenchRequiredContextTest {

    private final DownloadWorkbenchPlugin plugin = new DownloadWorkbenchPlugin();

    @Test
    @DisplayName("插件身份：FEATURE 与稳定 id，宿主策略可据 id 约束为官方必需包")
    void pluginIdentity() {
        assertThat(plugin.id()).isEqualTo(DownloadWorkbenchPlugin.ID);
        assertThat(plugin.kind()).isEqualTo(PluginKind.FEATURE);
        assertThat(plugin.displayNamespace()).isEqualTo("batch");
    }

    @Test
    @DisplayName("未发布插件描述符统一要求首个核心 API 1.0")
    void descriptorRequiresInitialApi10() throws Exception {
        Properties descriptor = new Properties();
        try (InputStream input = getClass().getResourceAsStream("/plugin.properties")) {
            assertThat(input).isNotNull();
            descriptor.load(input);
        }
        assertThat(descriptor.getProperty("plugin.requires")).isEqualTo("1.0");
    }

    @Test
    @DisplayName("下载页、下载 API、userscript、Pixiv 插画入口和 schedule API 均由插件声明")
    void workbenchRoutesDeclared() {
        for (String path : List.of(
                "/pixiv-batch.html",
                "/pixiv-batch/batch-core.js",
                "/api/download/pixiv",
                "/api/download/cancel/123",
                "/api/download/queue/clear",
                "/api/batch/state",
                "/api/download/extensions",
                "/api/scripts",
                "/api/scripts/pixiv-batch.user.js",
                "/api/sse/close/123",
                "/api/pixiv/thumbnail-proxy",
                "/api/pixiv/user/100/artworks",
                "/api/pixiv/user/100/illust-cards",
                "/api/pixiv/me/illust-bookmarks",
                "/api/pixiv/me/collection/42/works")) {
            assertRoute(path, HttpMethod.GET, AccessPolicy.VISITOR);
        }
        assertRoute("/api/schedule/tasks", HttpMethod.GET, AccessPolicy.ADMIN);
    }

    @Test
    @DisplayName("插件不再声明 user/me 宽前缀，避免继续承载小说形状")
    void broadPixivUserAndMeRoutesAreNotDeclared() {
        assertThat(plugin.routes())
                .extracting(WebRouteContribution::pathPattern)
                .doesNotContain("/api/pixiv/user/**", "/api/pixiv/me/**");
    }

    @Test
    @DisplayName("静态资源、i18n 与 userscript 均由插件 classloader-aware contribution 声明")
    void staticResourcesI18nAndUserscriptsDeclared() {
        assertThat(plugin.staticResources())
                .extracting(resource -> resource.classpathLocation()
                        + "|" + resource.publicPathPrefix() + "|" + resource.exactFile())
                .containsExactly(
                        "classpath:/static/|/pixiv-batch.html|true",
                        "classpath:/static/pixiv-batch/|/pixiv-batch/|false");
        assertThat(plugin.i18n())
                .extracting(i18n -> i18n.namespace() + "|" + i18n.baseName())
                .containsExactly("batch|i18n.web.batch", "userscript|i18n.web.userscript");
        assertThat(plugin.userscripts())
                .extracting(script -> script.id() + "|" + script.classpathResource())
                .containsExactly(
                        "all-in-one|classpath:/static/userscripts/Pixiv All-in-One.user.js",
                        "artwork-java|classpath:/static/userscripts/Pixiv 单作品图片下载器(Java后端版).user.js",
                        "artwork-local|classpath:/static/userscripts/Pixiv 单作品图片下载器(Local Download).user.js",
                        "user-batch|classpath:/static/userscripts/Pixiv User 批量下载器(User Batch).user.js",
                        "page-batch|classpath:/static/userscripts/Pixiv 页面批量下载器(Page Scrape).user.js",
                        "import-batch|classpath:/static/userscripts/Pixiv URL 批量导入单作品下载器(URL Batch).user.js",
                        "experience-toolbox|classpath:/static/userscripts/Pixiv 体验增强工具箱(Toolbox).user.js");
    }

    @Test
    @DisplayName("七类计划来源前端模块均由下载工作台自己的静态资源提供")
    void scheduledSourceFrontendModulesBelongToPluginAssets() {
        assertThat(plugin.staticResources()).anySatisfy(resource -> {
            assertThat(resource.classpathLocation()).isEqualTo("classpath:/static/pixiv-batch/");
            assertThat(resource.publicPathPrefix()).isEqualTo("/pixiv-batch/");
            assertThat(resource.exactFile()).isFalse();
        });
        assertThat(plugin.scheduledSourceDescriptors()).hasSize(7).allSatisfy(descriptor -> {
            assertThat(descriptor.frontend()).isNotNull();
            assertThat(descriptor.frontend().moduleUrl())
                    .isEqualTo(PixivScheduledSourceDescriptors.FRONTEND_MODULE_URL)
                    .startsWith("/pixiv-batch/");
            assertThat(getClass().getResource("/static" + descriptor.frontend().moduleUrl()))
                    .as("计划来源模块必须由下载工作台插件资源提供")
                    .isNotNull();
        });
    }

    @Test
    @DisplayName("导航、默认落点和插画下载类型由插件声明")
    void navigationStartupAndDownloadTypeDeclared() {
        assertThat(plugin.navigation()).singleElement()
                .satisfies(nav -> {
                    assertThat(nav.id()).isEqualTo("download-workbench");
                    assertThat(nav.href()).isEqualTo("/pixiv-batch.html");
                });
        assertThat(plugin.startupRoutes()).singleElement().satisfies(route -> {
            assertThat(route.path()).isEqualTo("/pixiv-batch.html");
            assertThat(route.order()).isEqualTo(10);
            assertThat(route.preferredContexts()).containsExactly(StartupRouteContext.MULTI);
        });
        assertThat(plugin.downloadTypes()).singleElement().satisfies(descriptor -> {
            assertThat(descriptor.type()).isEqualTo("illust");
            assertThat(descriptor.displayNamespace()).isEqualTo("batch");
            assertThat(descriptor.i18nNamespace()).isEqualTo("batch");
            assertThat(descriptor.cancelSupported()).isTrue();
        });
    }

    @Test
    @DisplayName("七类计划来源、旧别名和前端模块由稳定描述符声明")
    void scheduledSourcesDeclared() {
        List<ScheduledSourceDescriptor> descriptors = plugin.scheduledSourceDescriptors();
        assertThat(descriptors)
                .extracting(ScheduledSourceDescriptor::sourceType)
                .containsExactly("user-new", "user-request", "search", "series",
                        "my-bookmarks", "follow-latest", "collection");
        assertThat(descriptors.stream()
                .flatMap(descriptor -> descriptor.legacyAliases().stream())
                .toList())
                .containsExactlyInAnyOrder("USER_NEW", "USER_REQUEST", "SEARCH", "SERIES",
                        "MY_BOOKMARKS", "FOLLOW_LATEST", "COLLECTION");
        assertThat(descriptors).allSatisfy(descriptor -> {
            assertThat(descriptor.frontend()).isNotNull();
            assertThat(descriptor.frontend().moduleUrl())
                    .isEqualTo(PixivScheduledSourceDescriptors.FRONTEND_MODULE_URL);
        });
    }

    private void assertRoute(String path, HttpMethod method, AccessPolicy expectedPolicy) {
        assertThat(plugin.routes())
                .filteredOn(route -> route.matches(path) && route.acceptsMethod(method))
                .as("下载工作台应通过稳定路由 contribution 声明 %s %s", method, path)
                .anySatisfy(route ->
                        assertThat(route.accessPolicy()).isEqualTo(expectedPolicy));
    }
}

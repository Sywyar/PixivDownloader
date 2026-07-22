package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityOwner;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistry;
import top.sywyar.pixivdownload.core.schedule.capability.SchedulePlanningLease;
import top.sywyar.pixivdownload.download.DownloadWorkbenchPlugin;
import top.sywyar.pixivdownload.download.schedule.source.descriptor.PixivScheduledSourceDescriptors;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceDescriptor;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceExecutor;
import top.sywyar.pixivdownload.plugin.api.web.StartupRouteContext;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginSource;
import top.sywyar.pixivdownload.plugin.registry.RouteAccessRegistry;
import top.sywyar.pixivdownload.plugin.registry.StartupRouteRegistry;
import top.sywyar.pixivdownload.plugin.registry.StaticResourceRegistry;
import top.sywyar.pixivdownload.schedule.ScheduleCapabilityTestFixture;
import top.sywyar.pixivdownload.plugin.web.PluginOwnedWebAssetValidator;

import java.io.InputStream;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
        RouteAccessRegistry registry = new RouteAccessRegistry(new PluginRegistry(List.of(plugin)));

        assertThat(registry.routes())
                .extracting(RouteAccessRegistry.RegisteredRoute::pluginId)
                .containsOnly(DownloadWorkbenchPlugin.ID);
        assertThat(registry.isDeclared("/pixiv-batch.html")).isTrue();
        assertThat(registry.isDeclared("/pixiv-batch/batch-core.js")).isTrue();
        assertThat(registry.isDeclared("/api/download/pixiv")).isTrue();
        assertThat(registry.isDeclared("/api/download/cancel/123")).isTrue();
        assertThat(registry.isDeclared("/api/download/queue/clear")).isTrue();
        assertThat(registry.isDeclared("/api/batch/state")).isTrue();
        assertThat(registry.isDeclared("/api/download/extensions")).isTrue();
        assertThat(registry.isDeclared("/api/scripts")).isTrue();
        assertThat(registry.isDeclared("/api/scripts/pixiv-batch.user.js")).isTrue();
        assertThat(registry.isDeclared("/api/schedule/tasks")).isTrue();
        assertThat(registry.isDeclared("/api/sse/close/123")).isTrue();
        assertThat(registry.isDeclared("/api/pixiv/user/100/artworks")).isTrue();
        assertThat(registry.isDeclared("/api/pixiv/user/100/illust-cards")).isTrue();
        assertThat(registry.isDeclared("/api/pixiv/me/illust-bookmarks")).isTrue();
        assertThat(registry.isDeclared("/api/pixiv/me/collection/42/works")).isTrue();
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
                .extracting(script -> script.classpathPattern())
                .containsExactly("classpath:/static/userscripts/*.user.js");
    }

    @Test
    @DisplayName("七类计划来源前端模块均由下载工作台自己的静态资源提供")
    void scheduledSourceFrontendModulesBelongToPluginAssets() {
        PluginRegistry.RegisteredPlugin registered = new PluginRegistry.RegisteredPlugin(
                plugin,
                PluginSource.EXTERNAL,
                plugin.getClass().getClassLoader(),
                DownloadWorkbenchPlugin.ID,
                1L);
        PluginRegistry pluginRegistry = new PluginRegistry(List.of());
        StaticResourceRegistry staticResources = new StaticResourceRegistry(pluginRegistry);
        pluginRegistry.register(registered);
        try {
            staticResources.register(registered, List.copyOf(plugin.staticResources()));
            PluginOwnedWebAssetValidator validator = new PluginOwnedWebAssetValidator(staticResources);

            assertThat(plugin.scheduledSourceDescriptors()).hasSize(7).allSatisfy(descriptor -> {
                assertThat(descriptor.frontend()).isNotNull();
                assertThat(descriptor.frontend().moduleUrl())
                        .isEqualTo(PixivScheduledSourceDescriptors.FRONTEND_MODULE_URL);
                validator.validateOwnedJavaScript(
                        registered,
                        descriptor.frontend().moduleUrl(),
                        "Pixiv scheduled source frontend");
            });
        } finally {
            staticResources.unregister(registered.id());
            pluginRegistry.unregister(registered);
        }
    }

    @Test
    @DisplayName("导航、默认落点和插画下载类型由插件声明")
    void navigationStartupAndDownloadTypeDeclared() {
        PluginRegistry pluginRegistry = new PluginRegistry(List.of(plugin));
        StartupRouteRegistry startupRouteRegistry = new StartupRouteRegistry(pluginRegistry);

        assertThat(plugin.navigation()).singleElement()
                .satisfies(nav -> {
                    assertThat(nav.id()).isEqualTo("download-workbench");
                    assertThat(nav.href()).isEqualTo("/pixiv-batch.html");
                });
        assertThat(startupRouteRegistry.resolvePath(StartupRouteContext.MULTI))
                .contains("/pixiv-batch.html");
        assertThat(plugin.downloadTypes()).singleElement().satisfies(descriptor -> {
            assertThat(descriptor.type()).isEqualTo("illust");
            assertThat(descriptor.displayNamespace()).isEqualTo("batch");
            assertThat(descriptor.i18nNamespace()).isEqualTo("batch");
            assertThat(descriptor.cancelSupported()).isTrue();
        });

        startupRouteRegistry.unregister("download-workbench");
        assertThat(startupRouteRegistry.resolvePath(StartupRouteContext.MULTI)).isEmpty();
    }

    @Test
    @DisplayName("七类来源描述符与 child-context 执行器按同一 owner 原子发布")
    void scheduledSourcesDeclared() {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        List<ScheduledSourceDescriptor> descriptors = plugin.scheduledSourceDescriptors();
        List<ScheduledSourceExecutor> sourceExecutors = descriptors.stream()
                .map(descriptor -> {
                    ScheduledSourceExecutor executor = mock(ScheduledSourceExecutor.class);
                    when(executor.sourceType()).thenReturn(descriptor.sourceType());
                    return executor;
                })
                .toList();
        ScheduleCapabilityTestFixture.publish(
                registry,
                new ScheduleCapabilityOwner("download-workbench", "download-workbench", 1L),
                descriptors,
                sourceExecutors,
                List.of());

        assertThat(registry.snapshotView().owners()).singleElement()
                .satisfies(owner -> {
                    assertThat(owner.owner().featurePluginId()).isEqualTo("download-workbench");
                    assertThat(owner.owner().packageId()).isEqualTo("download-workbench");
                    assertThat(owner.owner().pluginGeneration()).isEqualTo(1L);
                });
        assertThat(registry.snapshotView().owners().get(0).sourceTypes())
                .containsExactlyInAnyOrder("user-new", "user-request", "search", "series",
                        "my-bookmarks", "follow-latest", "collection");
        assertThat(registry.snapshotView().owners().get(0).sourceAliases())
                .containsExactlyInAnyOrder("USER_NEW", "USER_REQUEST", "SEARCH", "SERIES",
                        "MY_BOOKMARKS", "FOLLOW_LATEST", "COLLECTION");
        SchedulePlanningLease userNew = registry.prepareSource("USER_NEW").orElseThrow();
        SchedulePlanningLease collection = registry.prepareSource("COLLECTION").orElseThrow();
        try (userNew; collection) {
            assertThat(registry.activate(userNew)).isTrue();
            assertThat(registry.activate(collection)).isTrue();
            assertThat(userNew.descriptor()).map(ScheduledSourceDescriptor::sourceType)
                    .contains("user-new");
            assertThat(userNew.sourceExecutor()).map(ScheduledSourceExecutor::sourceType)
                    .contains("user-new");
            assertThat(collection.descriptor()).map(ScheduledSourceDescriptor::sourceType)
                    .contains("collection");
            assertThat(collection.sourceExecutor()).map(ScheduledSourceExecutor::sourceType)
                    .contains("collection");
        }
    }
}

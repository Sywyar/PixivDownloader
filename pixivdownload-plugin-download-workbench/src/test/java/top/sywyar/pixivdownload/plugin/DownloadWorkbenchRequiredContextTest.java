package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityOwner;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistry;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleOwnerBundle;
import top.sywyar.pixivdownload.core.schedule.capability.SchedulePlanningLease;
import top.sywyar.pixivdownload.download.DownloadWorkbenchPlugin;
import top.sywyar.pixivdownload.download.schedule.source.descriptor.PixivScheduledSourceDescriptors;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.schedule.ScheduledSourceProvider;
import top.sywyar.pixivdownload.plugin.api.web.StartupRouteContext;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;
import top.sywyar.pixivdownload.plugin.registry.DownloadTabRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginSource;
import top.sywyar.pixivdownload.plugin.registry.QueueTypeRegistry;
import top.sywyar.pixivdownload.plugin.registry.RouteAccessRegistry;
import top.sywyar.pixivdownload.plugin.registry.StartupRouteRegistry;
import top.sywyar.pixivdownload.plugin.registry.StaticResourceRegistry;
import top.sywyar.pixivdownload.schedule.ScheduleCapabilityTestFixture;
import top.sywyar.pixivdownload.plugin.web.PluginOwnedWebAssetValidator;

import java.io.InputStream;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 下载工作台作为外置 required PF4J 插件时的 contribution 契约。这里不加载 app 的
 * {@code SpringBootTest} 上下文，避免外置模块出现在测试 classpath 后被 app 根包扫描成内置 controller。
 */
@DisplayName("download-workbench 外置 required 插件贡献契约")
class DownloadWorkbenchRequiredContextTest {

    private final DownloadWorkbenchPlugin plugin = new DownloadWorkbenchPlugin();

    @Test
    @DisplayName("插件身份：required 功能插件，核心策略可据 id 约束为官方必需包")
    void pluginIdentity() {
        assertThat(plugin.id()).isEqualTo(DownloadWorkbenchPlugin.ID);
        assertThat(plugin.kind()).isEqualTo(PluginKind.FEATURE);
        assertThat(plugin.required()).isTrue();
        assertThat(plugin.displayNamespace()).isEqualTo("batch");
    }

    @Test
    @DisplayName("插件描述符要求提供通用计划任务保存契约的核心 API 1.2")
    void descriptorRequiresScheduleApi12() throws Exception {
        Properties descriptor = new Properties();
        try (InputStream input = getClass().getResourceAsStream("/plugin.properties")) {
            assertThat(input).isNotNull();
            descriptor.load(input);
        }
        assertThat(descriptor.getProperty("plugin.requires")).isEqualTo("1.2");
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
                .extracting(resource -> resource.pluginId() + "|" + resource.classpathLocation()
                        + "|" + resource.publicPathPrefix() + "|" + resource.exactFile())
                .containsExactly(
                        "download-workbench|classpath:/static/|/pixiv-batch.html|true",
                        "download-workbench|classpath:/static/pixiv-batch/|/pixiv-batch/|false");
        assertThat(plugin.i18n())
                .extracting(i18n -> i18n.namespace() + "|" + i18n.baseName())
                .containsExactly("batch|i18n.web.batch", "userscript|i18n.web.userscript");
        assertThat(plugin.userscripts())
                .extracting(script -> script.pluginId() + "|" + script.classpathPattern())
                .containsExactly("download-workbench|classpath:/static/userscripts/*.user.js");
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
    @DisplayName("导航、默认落点、插画队列类型和五个下载页 tab 均随插件注册/注销")
    void navigationStartupQueueTypeAndTabsDeclared() {
        PluginRegistry pluginRegistry = new PluginRegistry(List.of(plugin));
        StartupRouteRegistry startupRouteRegistry = new StartupRouteRegistry(pluginRegistry);
        QueueTypeRegistry queueTypeRegistry = new QueueTypeRegistry(pluginRegistry);
        DownloadTabRegistry downloadTabRegistry = new DownloadTabRegistry(pluginRegistry);

        assertThat(plugin.navigation()).singleElement()
                .satisfies(nav -> {
                    assertThat(nav.id()).isEqualTo("download-workbench");
                    assertThat(nav.href()).isEqualTo("/pixiv-batch.html");
                });
        assertThat(startupRouteRegistry.resolvePath(StartupRouteContext.MULTI))
                .contains("/pixiv-batch.html");
        assertThat(queueTypeRegistry.queueTypes())
                .extracting(item -> item.queueType().type())
                .containsExactly("illust");
        assertThat(queueTypeRegistry.queueTypes()).singleElement().satisfies(item -> {
            assertThat(item.queueType().labelNamespace()).isEqualTo("batch");
            assertThat(item.queueType().descriptor().displayNamespace()).isEqualTo("batch");
            assertThat(item.queueType().descriptor().i18nNamespace()).isEqualTo("batch");
        });
        assertThat(downloadTabRegistry.tabs())
                .extracting(item -> item.tab().tabId())
                .containsExactly("quick-fetch", "single-import", "user", "search", "series");

        startupRouteRegistry.unregister("download-workbench");
        queueTypeRegistry.unregister("download-workbench");
        downloadTabRegistry.unregister("download-workbench");
        assertThat(startupRouteRegistry.resolvePath(StartupRouteContext.MULTI)).isEmpty();
        assertThat(queueTypeRegistry.queueTypes()).isEmpty();
        assertThat(downloadTabRegistry.tabs()).isEmpty();
    }

    @Test
    @DisplayName("七类默认插画/混合作品来源随 download-workbench 插件贡献")
    void scheduledSourcesDeclared() {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        ScheduleCapabilityTestFixture.publish(registry, ScheduleOwnerBundle.prepare(
                new ScheduleCapabilityOwner("download-workbench", "download-workbench", 1L),
                plugin.scheduledSources(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()));

        assertThat(registry.snapshotView().owners()).singleElement()
                .satisfies(owner -> {
                    assertThat(owner.owner().featurePluginId()).isEqualTo("download-workbench");
                    assertThat(owner.owner().packageId()).isEqualTo("download-workbench");
                    assertThat(owner.owner().pluginGeneration()).isEqualTo(1L);
                });
        assertThat(registry.snapshotView().owners().get(0).legacySourceTypes())
                .containsExactlyInAnyOrder("user-new", "user-request", "search", "series",
                        "my-bookmarks", "follow-latest", "collection");
        SchedulePlanningLease userNew = registry.prepareSource("USER_NEW").orElseThrow();
        SchedulePlanningLease collection = registry.prepareSource("COLLECTION").orElseThrow();
        try (userNew; collection) {
            assertThat(registry.activate(userNew)).isTrue();
            assertThat(registry.activate(collection)).isTrue();
            assertThat(userNew.legacySourceProvider()).map(ScheduledSourceProvider::type)
                    .contains("user-new");
            assertThat(collection.legacySourceProvider()).map(ScheduledSourceProvider::type)
                    .contains("collection");
        }
    }
}

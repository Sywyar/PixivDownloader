package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.context.support.GenericApplicationContext;
import top.sywyar.pixivdownload.plugin.api.web.page.*;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.PageContributionAdapter;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.*;
import top.sywyar.pixivdownload.plugin.registry.web.PageContributionRegistry;
import top.sywyar.pixivdownload.plugin.lifecycle.request.PluginRequestOwner;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class PageContributionAdapterTest {
    @Test @DisplayName("分类保留成员顺序、补齐插件名称并拒绝越权重复和超量声明")
    void validatesCategoriesAndCompletesUnassignedComponents() {
        var invocations = new ExternalCapabilityInvocationRegistry();
        var registry = new PageContributionRegistry(List.of(), invocations);
        var plugins = org.mockito.Mockito.mock(top.sywyar.pixivdownload.plugin.registry.PluginRegistry.class);
        var feature = org.mockito.Mockito.mock(top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin.class);
        org.mockito.Mockito.when(feature.displayName()).thenReturn("plugin.name");
        org.mockito.Mockito.when(feature.displayNamespace()).thenReturn("owner");
        org.mockito.Mockito.when(plugins.registeredPlugins()).thenReturn(List.of(
                new top.sywyar.pixivdownload.plugin.registry.PluginRegistry.RegisteredPlugin(feature,
                        top.sywyar.pixivdownload.plugin.registry.PluginSource.EXTERNAL, getClass().getClassLoader(), "owner", 1, "owner")));
        var routes = org.mockito.Mockito.mock(top.sywyar.pixivdownload.plugin.registry.route.RouteAccessRegistry.class);
        var assets = org.mockito.Mockito.mock(top.sywyar.pixivdownload.plugin.web.resource.PluginOwnedWebAssetValidator.class);
        org.mockito.Mockito.when(routes.resolve("/owner/list.mjs", top.sywyar.pixivdownload.plugin.api.web.HttpMethod.GET))
                .thenReturn(Optional.of(new top.sywyar.pixivdownload.plugin.registry.route.RouteAccessRegistry.RegisteredRoute("owner",
                        top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution.publicRoute("/owner/list.mjs"))));
        var adapter = new PageContributionAdapter(registry, invocations, plugins, routes, assets);
        var preparation = invocations.allocatePreparation("owner", "owner", 1);
        invocations.installPreparation(preparation);
        var categories = new ArrayList<PageComponentCategory>();
        var group = new PageComponentCategory("owner:group", "group", "owner", "help", List.of("owner:two"));
        categories.add(group);
        try (var context = new GenericApplicationContext()) {
            context.registerBean(PageContribution.class, () -> new PageContribution() {
                public List<PageComponent> components() {
                    return List.of("one", "two").stream().map(id -> new PageComponent("owner:" + id, 1, "label", "owner",
                            "/owner/list.mjs", "", "{\"type\":\"object\"}", "{}", true, List.of(), List.of(), null)).toList();
                }
                public List<PageComponentCategory> componentCategories() { return categories; }
                public void validate(String type, String configuration) { }
            });
            context.refresh();
            var prepared = adapter.prepare(preparation, context);
            // 只发布捕获后的纯值；修改插件原集合不能污染目录。
            categories.clear();
            adapter.publish(prepared); invocations.publish(preparation);
            assertThat(registry.componentCategories()).containsExactly(group,
                    new PageComponentCategory("owner", "plugin.name", "owner", "", List.of("owner:one")));
            adapter.withdraw(preparation.owner());
            assertThat(registry.componentCategories()).isEmpty();
            invocations.retireDrained(invocations.withdrawPublished(preparation));
            var next = invocations.allocatePreparation("owner", "owner", 2);
            invocations.installPreparation(next);
            org.mockito.Mockito.when(plugins.registeredPlugins()).thenReturn(List.of(
                    new top.sywyar.pixivdownload.plugin.registry.PluginRegistry.RegisteredPlugin(feature,
                            top.sywyar.pixivdownload.plugin.registry.PluginSource.EXTERNAL, getClass().getClassLoader(), "owner", 2, "owner")));
            for (var invalid : List.of(
                    List.of(new PageComponentCategory("other:group", "label", "owner", "", List.of("owner:one"))),
                    List.of(new PageComponentCategory("owner:group", "label", "owner", "", List.of("other:one"))),
                    List.of(new PageComponentCategory("owner:group", "label", "owner", "", List.of("owner:missing"))),
                    List.of(new PageComponentCategory("owner:group", "label", "owner", "", List.of("owner:one", "owner:one"))),
                    List.of(new PageComponentCategory("owner:group", "label", "owner", "", List.of())),
                    List.of(group, group),
                    List.of(group, new PageComponentCategory("owner:other", "label", "owner", "", List.of("owner:two"))),
                    List.of(new PageComponentCategory("owner:group", "label", "owner", "x".repeat(1024 * 1024),
                            List.of("owner:one", "owner:two"))),
                    List.of(group, group, group))) {
                categories.clear(); categories.addAll(invalid);
                assertThatThrownBy(() -> adapter.prepare(next, context)).isInstanceOf(IllegalArgumentException.class);
            }
            categories.clear();
            categories.add(group);
            categories.add(new PageComponentCategory("owner:other", "other", "owner", "", List.of("owner:one")));
            adapter.publish(adapter.prepare(next, context)); invocations.publish(next);
            assertThat(registry.componentCategories()).containsExactlyElementsOf(categories);
        }
    }

    @Test @DisplayName("聚合独立 owner、拒绝非法值并撤回精确 publication")
    void aggregatesOwnersRejectsInvalidValuesAndWithdrawsExactPublication() throws Exception {
        var invocations = new ExternalCapabilityInvocationRegistry();
        var registry = new PageContributionRegistry(List.of(), invocations);
        var plugins = org.mockito.Mockito.mock(top.sywyar.pixivdownload.plugin.registry.PluginRegistry.class);
        var routes = org.mockito.Mockito.mock(top.sywyar.pixivdownload.plugin.registry.route.RouteAccessRegistry.class);
        var assets = org.mockito.Mockito.mock(top.sywyar.pixivdownload.plugin.web.resource.PluginOwnedWebAssetValidator.class);
        var registered = new ArrayList<top.sywyar.pixivdownload.plugin.registry.PluginRegistry.RegisteredPlugin>();
        for (var owner : List.of("first", "second")) {
            var feature = org.mockito.Mockito.mock(top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin.class);
            org.mockito.Mockito.when(feature.displayName()).thenReturn("plugin.name");
            org.mockito.Mockito.when(feature.displayNamespace()).thenReturn(owner);
            var plugin = new top.sywyar.pixivdownload.plugin.registry.PluginRegistry.RegisteredPlugin(feature,
                    top.sywyar.pixivdownload.plugin.registry.PluginSource.EXTERNAL, getClass().getClassLoader(), owner,
                    owner.equals("first") ? 1 : 2, owner);
            registered.add(plugin);
            org.mockito.Mockito.when(routes.resolve("/" + owner + "/list.mjs", top.sywyar.pixivdownload.plugin.api.web.HttpMethod.GET))
                    .thenReturn(Optional.of(new top.sywyar.pixivdownload.plugin.registry.route.RouteAccessRegistry.RegisteredRoute(owner,
                            top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution.publicRoute("/" + owner + "/list.mjs"))));
            for (String asset : List.of("page.html", "page.css")) {
                String path = "/" + owner + "/" + asset;
                org.mockito.Mockito.when(routes.resolve(path, top.sywyar.pixivdownload.plugin.api.web.HttpMethod.GET))
                        .thenReturn(Optional.of(new top.sywyar.pixivdownload.plugin.registry.route.RouteAccessRegistry.RegisteredRoute(owner,
                                top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution.publicRoute(path))));
            }
        }
        org.mockito.Mockito.when(plugins.registeredPlugins()).thenReturn(registered);
        var adapter = new PageContributionAdapter(registry, invocations, plugins, routes, assets);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var first = invocations.allocatePreparation("first", "first", 1);
        var second = invocations.allocatePreparation("second", "second", 2);
        invocations.installPreparation(first); invocations.installPreparation(second);
        try (var one = context("first", () -> {
            entered.countDown(); try { release.await(); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
        }); var two = context("second", () -> {})) {
            adapter.publish(adapter.prepare(first, one)); adapter.publish(adapter.prepare(second, two));
            invocations.publish(first); invocations.publish(second);
            assertThat(registry.components()).hasSize(2);
            assertThat(registry.pages()).hasSize(2);
            assertThat(registry.componentCategories()).containsExactly(
                    new PageComponentCategory("first", "plugin.name", "first", "", List.of("first:list")),
                    new PageComponentCategory("second", "plugin.name", "second", "", List.of("second:list")));
            String token = registry.components().get(0).publication();
            assertThat(registry.pages().get(0).publication()).isEqualTo(token);
            org.mockito.Mockito.verify(assets).validateOwnedStylesheet(registered.get(0), "/first/page.css", "page stylesheet");
            assertThat(registry.permits(token, new PluginRequestOwner("first", 1, 1))).isTrue();
            assertThat(registry.permits(token, new PluginRequestOwner("first", 2, 1))).isFalse();
            assertThatThrownBy(() -> registry.validate("first:list", token, 1, "{\"items\":false}"))
                    .hasMessage("PAGE_CONFIGURATION_INVALID");
            var pool = Executors.newSingleThreadExecutor();
            try {
                var call = pool.submit(() -> registry.validate("first:list", token, 1, "{\"items\":[\"one\"]}"));
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                var drain = invocations.withdrawPublished(first); adapter.withdraw(first.owner());
                assertThat(drain.isDrained()).isFalse();
                assertThat(registry.components()).hasSize(1);
                assertThat(registry.pages()).hasSize(1);
                assertThat(registry.componentCategories()).extracting(PageComponentCategory::id).containsExactly("second");
                assertThat(registry.permits(token, "first", new PluginRequestOwner("first", 1, 1))).isFalse();
                assertThatThrownBy(() -> registry.validate("first:list", token, 1, "{\"items\":[]}"))
                        .hasMessage("PAGE_COMPONENT_UNAVAILABLE");
                release.countDown();
                assertThatThrownBy(() -> call.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
                assertThat(drain.await(java.time.Duration.ofSeconds(5))).isTrue();
                invocations.retireDrained(drain);
            } finally { release.countDown(); pool.shutdownNow(); }
        }
    }

    private static GenericApplicationContext context(String owner, Runnable validation) {
        var context = new GenericApplicationContext();
        context.registerBean(PageContribution.class, () -> new PageContribution() {
            public List<EditablePage> pages() {
                return List.of(new EditablePage(owner + ":page", "page", owner, "/" + owner + "/page.html", true,
                        List.of(new PageRegion("card", "card", "", true, false),
                                new PageRegion("field", "field", "card", false, false)),
                        "", "/" + owner + "/list.mjs", List.of("/" + owner + "/page.css"), List.of()));
            }
            public List<PageComponent> components() {
                return List.of(new PageComponent(owner + ":list", 1, "label", owner, "/" + owner + "/list.mjs", "",
                        "{\"type\":\"object\",\"properties\":{\"items\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}},\"required\":[\"items\"],\"additionalProperties\":false}",
                        "{\"items\":[]}", true, List.of(), List.of(), null));
            }
            public void validate(String type, String configuration) { validation.run(); }
        });
        context.refresh(); return context;
    }

    @Test @DisplayName("页面区域声明拒绝循环父级并将核心预览绑定到本进程")
    void rejectsRegionCyclesAndBindsCorePreview() {
        var invocations = new ExternalCapabilityInvocationRegistry();
        var page = new EditablePage("core:test", "test", "test", "/test.html", true, List.of(), "", "", List.of(), List.of());
        var registry = new PageContributionRegistry(List.of(page), invocations);
        String token = registry.pages().get(0).publication();
        assertThat(registry.permits(token, "core", null)).isTrue();
        assertThat(registry.permits(token, "another", null)).isFalse();
        assertThat(new PageContributionRegistry(List.of(page), invocations).permits(token, "core", null)).isFalse();
        var adapter = new PageContributionAdapter(registry, invocations, null, null, null);
        var preparation = invocations.allocatePreparation("test", "test", 1);
        invocations.installPreparation(preparation);
        try (var context = new GenericApplicationContext()) {
            context.registerBean(PageContribution.class, () -> new PageContribution() {
                public void validate(String type, String configuration) { }
                public List<EditablePage> pages() {
                    return List.of(new EditablePage("test:page", "test", "test", "/test.html", true,
                            List.of(new PageRegion("a", "a", "b", true, false),
                                    new PageRegion("b", "b", "a", true, false)), "", "", List.of(), List.of()));
                }
            });
            context.refresh();
            assertThatThrownBy(() -> adapter.prepare(preparation, context)).hasMessage("PAGE_DECLARATION_INVALID");
        }
    }
}

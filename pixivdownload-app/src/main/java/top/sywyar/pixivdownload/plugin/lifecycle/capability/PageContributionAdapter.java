package top.sywyar.pixivdownload.plugin.lifecycle.capability;

import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.plugin.api.web.page.*;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.*;
import top.sywyar.pixivdownload.plugin.registry.web.PageContributionRegistry;
import top.sywyar.pixivdownload.plugin.web.remote.RemoteValues;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.route.RouteAccessRegistry;
import top.sywyar.pixivdownload.plugin.web.resource.PluginOwnedWebAssetValidator;
import top.sywyar.pixivdownload.plugin.api.web.*;
import java.util.*;
import java.nio.charset.StandardCharsets;

/** 在既有准备、发布、撤回及排空事务内装配页面贡献，不另建生命周期。 */
@Component
public final class PageContributionAdapter implements ExternalRuntimeCapabilityAdapter {
    private record Prepared(ExternalCapabilityOwner owner, PageContributionRegistry.Entry entry) implements PreparedContribution { }
    private final PageContributionRegistry registry;
    private final ExternalCapabilityInvocationRegistry invocations;
    private final PluginRegistry plugins;
    private final RouteAccessRegistry routes;
    private final PluginOwnedWebAssetValidator assets;
    public PageContributionAdapter(PageContributionRegistry registry, ExternalCapabilityInvocationRegistry invocations,
            PluginRegistry plugins, RouteAccessRegistry routes, PluginOwnedWebAssetValidator assets) {
        this.registry = registry; this.invocations = invocations; this.plugins = plugins; this.routes = routes; this.assets = assets;
    }
    @Override public String capabilityName() { return PageContribution.class.getName(); }
    @Override public PreparedContribution prepare(ExternalCapabilityPreparation preparation, ConfigurableApplicationContext context) {
        var providers = context.getBeansOfType(PageContribution.class).values();
        var sources = context.getBeansOfType(PagePublicationSource.class).values();
        if (providers.size() > 1 || sources.size() > 1) throw new IllegalArgumentException("PAGE_PROVIDER_CONFLICT");
        PageContribution provider = providers.stream().findFirst().orElse(null);
        List<PageComponent> components = provider == null ? List.of() : List.copyOf(invocations.captureMetadata(
                preparation, PageContribution.class, "page components", provider::components));
        List<EditablePage> pages = provider == null ? List.of() : List.copyOf(invocations.captureMetadata(
                preparation, PageContribution.class, "editable pages", provider::pages));
        if (components.size() + pages.size() > 128) throw new IllegalArgumentException("PAGE_CATALOG_BUDGET");
        long bytes = 0;
        for (PageComponent c : components) {
            identity(c.type(), preparation.owner().pluginId()); path(c.moduleUrl());
            if (!c.propertiesModuleUrl().isEmpty()) path(c.propertiesModuleUrl());
            if (c.version() < 1 || c.labelKey().isBlank() || c.namespace().isBlank()) throw new IllegalArgumentException("PAGE_DECLARATION_INVALID");
            ports(c.inputs()); ports(c.outputs());
            bytes += c.toString().getBytes(StandardCharsets.UTF_8).length;
            if (bytes > 1024 * 1024) throw new IllegalArgumentException("PAGE_CATALOG_BUDGET");
            RemoteValues.validateConfiguration(c.schema(), c.defaults());
        }
        for (EditablePage page : pages) {
            identity(page.id(), preparation.owner().pluginId()); path(page.path());
            names(page.regions().stream().map(PageRegion::id).toList());
            var regions = new HashMap<String, PageRegion>();
            page.regions().forEach(region -> regions.put(region.id(), region));
            for (PageRegion region : page.regions()) {
                if (region.labelKey().isBlank()) throw new IllegalArgumentException("PAGE_DECLARATION_INVALID");
                var seen = new HashSet<String>();
                PageRegion current = region;
                while (!current.parent().isEmpty()) {
                    if (!seen.add(current.id())) throw new IllegalArgumentException("PAGE_DECLARATION_INVALID");
                    current = regions.get(current.parent());
                    if (current == null || !current.container()) throw new IllegalArgumentException("PAGE_DECLARATION_INVALID");
                }
            }
            if (!page.previewModuleUrl().isEmpty()) path(page.previewModuleUrl());
            if (page.stylesheets().size() > 16) throw new IllegalArgumentException("PAGE_CATALOG_BUDGET");
            page.stylesheets().forEach(PageContributionAdapter::path);
            if (page.labelKey().isBlank() || page.namespace().isBlank()) throw new IllegalArgumentException("PAGE_DECLARATION_INVALID");
            bytes += page.toString().getBytes(StandardCharsets.UTF_8).length;
            if (bytes > 1024 * 1024) throw new IllegalArgumentException("PAGE_CATALOG_BUDGET");
        }
        PageContribution proxy = provider == null ? null : invocations.prepareProxy(preparation, PageContribution.class, provider);
        PagePublicationSource source = sources.isEmpty() ? null : invocations.prepareProxy(preparation, PagePublicationSource.class, sources.iterator().next());
        return new Prepared(preparation.owner(), new PageContributionRegistry.Entry(preparation.owner(), UUID.randomUUID().toString(),
                components, pages, proxy, source));
    }
    private static void identity(String id, String owner) {
        if (id == null || id.length() > 128 || !id.startsWith(owner + ":") || !id.matches("[a-z0-9-]+:[a-zA-Z0-9._-]+"))
            throw new IllegalArgumentException("PAGE_ID_INVALID");
    }
    private static void ports(List<PagePort> ports) {
        names(ports.stream().map(PagePort::name).toList());
        for (PagePort port : ports) {
            if (!port.contractId().matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}"))
                throw new IllegalArgumentException("PAGE_PORT_INVALID");
            RemoteValues.validateSchema(port.schema());
        }
    }
    private static void names(List<String> values) {
        if (values.size() > 64 || new HashSet<>(values).size() != values.size()
                || values.stream().anyMatch(value -> !value.matches("[A-Za-z][A-Za-z0-9._-]{0,63}")))
            throw new IllegalArgumentException("PAGE_DECLARATION_INVALID");
    }
    private static void path(String path) {
        if (path == null || !path.startsWith("/") || path.startsWith("//") || path.contains("\\") || path.contains("?") || path.contains("#"))
            throw new IllegalArgumentException("PAGE_MODULE_PATH_INVALID");
    }
    @Override public void publish(PreparedContribution contribution) {
        var entry = ((Prepared) contribution).entry();
        if (!entry.components().isEmpty() || !entry.pages().isEmpty()) {
            var owner = plugins.registeredPlugins().stream().filter(plugin -> plugin.id().equals(entry.owner().pluginId())
                    && plugin.generation() == entry.owner().pluginGeneration()).findFirst()
                    .orElseThrow(() -> new IllegalStateException("PAGE_OWNER_UNAVAILABLE"));
            for (PageComponent component : entry.components()) {
                assets.validateOwnedJavaScript(owner, component.moduleUrl(), "page component");
                route(owner.id(), component.moduleUrl(), component.publicRenderable());
                if (!component.propertiesModuleUrl().isEmpty()) {
                    assets.validateOwnedJavaScript(owner, component.propertiesModuleUrl(), "page property editor");
                    route(owner.id(), component.propertiesModuleUrl(), false);
                }
            }
            for (EditablePage page : entry.pages()) {
                route(owner.id(), page.path(), page.publicPage());
                if (!page.previewModuleUrl().isEmpty()) {
                    assets.validateOwnedJavaScript(owner, page.previewModuleUrl(), "page preview");
                    route(owner.id(), page.previewModuleUrl(), false);
                }
                for (String stylesheet : page.stylesheets()) {
                    assets.validateOwnedStylesheet(owner, stylesheet, "page stylesheet");
                    route(owner.id(), stylesheet, page.publicPage());
                }
            }
        }
        registry.publish(entry);
    }
    private void route(String owner, String path, boolean publicPage) {
        var route = routes.resolve(path, HttpMethod.GET).orElseThrow(() -> new IllegalArgumentException("PAGE_ROUTE_INVALID"));
        if (!route.pluginId().equals(owner) || publicPage && route.route().accessPolicy() != AccessPolicy.PUBLIC)
            throw new IllegalArgumentException("PAGE_ROUTE_INVALID");
    }
    @Override public void withdraw(ExternalCapabilityOwner owner) { registry.withdraw(owner); }
}

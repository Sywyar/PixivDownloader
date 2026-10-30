package top.sywyar.pixivdownload.plugin.registry.web;

import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.plugin.api.web.page.*;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.*;
import top.sywyar.pixivdownload.plugin.web.remote.RemoteValues;
import top.sywyar.pixivdownload.plugin.lifecycle.request.PluginRequestOwner;
import java.util.*;

/** 只保存纯值和精确代际代理；组件目录不通过单 provider 服务键聚合。 */
@Component
public final class PageContributionRegistry implements PageCatalog {
    public record Entry(ExternalCapabilityOwner owner, String token, List<PageComponent> components,
            List<EditablePage> pages, PageContribution provider, PagePublicationSource source) { }
    private volatile List<Entry> entries = List.of();
    private final List<EditablePage> corePages;
    private final String corePublication = UUID.randomUUID().toString();
    private final ExternalCapabilityInvocationRegistry invocations;

    public PageContributionRegistry(List<EditablePage> corePages, ExternalCapabilityInvocationRegistry invocations) {
        this.corePages = List.copyOf(corePages); this.invocations = invocations;
    }

    public synchronized void publish(Entry entry) {
        Set<String> types = new HashSet<>(); Set<String> pages = new HashSet<>();
        corePages.forEach(p -> pages.add(p.id()));
        List<Entry> next = new ArrayList<>(entries);
        if (next.stream().anyMatch(e -> e.owner().pluginId().equals(entry.owner().pluginId())))
            throw new IllegalStateException("PAGE_OWNER_CONFLICT");
        next.add(entry);
        for (Entry e : next) {
            for (PageComponent c : e.components()) if (!types.add(c.type())) throw new IllegalArgumentException("PAGE_TYPE_CONFLICT");
            for (EditablePage p : e.pages()) if (!pages.add(p.id())) throw new IllegalArgumentException("PAGE_ID_CONFLICT");
        }
        entries = List.copyOf(next);
    }

    public synchronized void withdraw(ExternalCapabilityOwner owner) {
        entries = entries.stream().filter(e -> !e.owner().equals(owner)).toList();
    }

    private List<Entry> active() {
        return entries.stream().filter(e -> invocations.acceptsInvocations(e.owner())).toList();
    }

    @Override public List<AvailableComponent> components() {
        return active().stream().flatMap(e -> e.components().stream().map(c -> new AvailableComponent(c, e.token()))).toList();
    }

    @Override public List<AvailablePage> pages() {
        var result = new ArrayList<AvailablePage>();
        corePages.forEach(page -> result.add(new AvailablePage(page, corePublication)));
        active().forEach(e -> e.pages().forEach(page -> result.add(new AvailablePage(page, e.token()))));
        return List.copyOf(result);
    }

    private Entry require(String type, String token) {
        return active().stream().filter(e -> e.token().equals(token)
                && e.components().stream().anyMatch(c -> c.type().equals(type))).findFirst()
                .orElseThrow(() -> new IllegalStateException("PAGE_COMPONENT_UNAVAILABLE"));
    }

    @Override public void validate(String type, String token, int version, String json) {
        Entry e = require(type, token);
        PageComponent c = e.components().stream().filter(d -> d.type().equals(type)).findFirst().orElseThrow();
        if (c.version() != version) throw new IllegalArgumentException("PAGE_CONFIGURATION_VERSION");
        RemoteValues.validateConfiguration(c.schema(), json);
        e.provider().validate(type, json);
        require(type, token);
    }

    @Override public String migrate(String type, String token, int version, String json) {
        Entry e = require(type, token);
        String migrated = e.provider().migrate(type, version, json);
        int current = e.components().stream().filter(c -> c.type().equals(type)).findFirst().orElseThrow().version();
        validate(type, token, current, migrated);
        return migrated;
    }

    /** 检查组件传输绑定的 owner，不能把旧请求交给同 ID 替代者。 */
    public boolean permits(String token, PluginRequestOwner owner) {
        return owner != null && active().stream().anyMatch(e -> e.token().equals(token)
                && e.owner().pluginId().equals(owner.pluginId()) && e.owner().pluginGeneration() == owner.generation());
    }

    /** 核心页面的预览绑定本进程；此检查不替代路由鉴权。 */
    public boolean permits(String token, String routeOwner, PluginRequestOwner owner) {
        return corePublication.equals(token) && "core".equals(routeOwner) && owner == null
                || permits(token, owner);
    }

    /** 只有唯一活动来源才能提供投影；缺席与歧义均恢复默认页面。 */
    public Optional<String> published(String page) {
        List<Entry> sources = active().stream().filter(e -> e.source() != null).toList();
        if (sources.size() != 1) return Optional.empty();
        return sources.get(0).source().published(page);
    }
}

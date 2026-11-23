package top.sywyar.pixivdownload.gui.media;

import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiText;
import top.sywyar.pixivdownload.plugin.api.gui.media.DesktopMediaTool;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityOwner;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** 只保存宿主纯值与可撤回代理，旧 publication 不能撤回或调用替代来源。 */
@Component
public final class DesktopMediaToolRegistry {
    private record Entry(DesktopMediaTool tool, MediaToolExecution source) {}
    private final ConcurrentHashMap<DesktopMediaTool.Identity, Entry> entries = new ConcurrentHashMap<>();

    public void register(ExternalCapabilityOwner owner, DesktopMediaTool.Description description, DesktopMediaTool.Source source) {
        var identity = identity(owner);
        entries.put(identity, new Entry(new DesktopMediaTool(identity, description), MediaToolExecution.wrap(source)));
    }

    public void unregister(ExternalCapabilityOwner owner) { entries.remove(identity(owner)); }

    boolean isPublished(DesktopMediaTool.Identity identity) { return entries.containsKey(identity); }

    public List<DesktopMediaTool> tools() {
        return entries.values().stream().map(Entry::tool)
                .sorted(Comparator.comparing(tool -> tool.identity().pluginId())).toList();
    }

    public DesktopMediaTool.Source source(DesktopMediaTool.Identity identity) {
        return execution(identity);
    }

    MediaToolExecution execution(DesktopMediaTool.Identity identity) {
        Entry entry = entries.get(identity);
        if (entry == null) throw new DesktopMediaTool.OperationException(DesktopUiText.key("gui.message.backend-busy"));
        return entry.source();
    }

    private static DesktopMediaTool.Identity identity(ExternalCapabilityOwner owner) {
        return new DesktopMediaTool.Identity(owner.pluginId(), owner.packageId(), owner.pluginGeneration(), owner.publicationId());
    }
}

package top.sywyar.pixivdownload.gui.importing;

import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiText;
import top.sywyar.pixivdownload.plugin.api.gui.importing.DesktopFileImportTool;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityOwner;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** 只保存纯值描述和可撤回代理；旧身份不能触达 replacement。 */
@Component
public final class DesktopFileImportToolRegistry {
    private record Entry(DesktopFileImportTool tool, DesktopFileImportTool.Source source) {}
    private final ConcurrentHashMap<DesktopFileImportTool.Identity, Entry> entries = new ConcurrentHashMap<>();

    public void register(ExternalCapabilityOwner owner, DesktopFileImportTool.Description description, DesktopFileImportTool.Source source) {
        var identity = identity(owner);
        entries.put(identity, new Entry(new DesktopFileImportTool(identity, description), source));
    }
    public void unregister(ExternalCapabilityOwner owner) { entries.remove(identity(owner)); }
    public List<DesktopFileImportTool> tools() {
        return entries.values().stream().map(Entry::tool)
                .sorted(Comparator.comparing(tool -> tool.identity().pluginId())).toList();
    }
    public DesktopFileImportTool.Source source(DesktopFileImportTool.Identity identity) {
        Entry entry = entries.get(identity);
        if (entry == null) throw new DesktopFileImportTool.OperationException(DesktopUiText.key("gui.message.backend-busy"));
        return entry.source();
    }
    private static DesktopFileImportTool.Identity identity(ExternalCapabilityOwner owner) {
        return new DesktopFileImportTool.Identity(owner.pluginId(), owner.packageId(), owner.pluginGeneration(), owner.publicationId());
    }
}

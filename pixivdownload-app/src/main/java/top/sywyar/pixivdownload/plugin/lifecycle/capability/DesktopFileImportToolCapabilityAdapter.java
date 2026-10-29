package top.sywyar.pixivdownload.plugin.lifecycle.capability;

import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.gui.importing.DesktopFileImportToolRegistry;
import top.sywyar.pixivdownload.plugin.api.gui.importing.DesktopFileImportTool;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityInvocationRegistry;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityOwner;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityPreparation;

/** 导入来源复用现有 publication 准入、撤回与在途调用 drain。 */
@Component
public final class DesktopFileImportToolCapabilityAdapter implements ExternalRuntimeCapabilityAdapter {
    private record Prepared(ExternalCapabilityOwner owner, DesktopFileImportTool.Description description,
                            DesktopFileImportTool.Source source) implements PreparedContribution {}
    private final DesktopFileImportToolRegistry registry;
    private final ExternalCapabilityInvocationRegistry invocations;
    public DesktopFileImportToolCapabilityAdapter(DesktopFileImportToolRegistry registry, ExternalCapabilityInvocationRegistry invocations) {
        this.registry = registry;
        this.invocations = invocations;
    }
    @Override public String capabilityName() { return DesktopFileImportTool.Source.class.getName(); }
    @Override public PreparedContribution prepare(ExternalCapabilityPreparation preparation, ConfigurableApplicationContext context) {
        var beans = context.getBeansOfType(DesktopFileImportTool.Source.class);
        if (beans.size() > 1) throw new IllegalArgumentException("multiple file import sources");
        if (beans.isEmpty()) return new Prepared(preparation.owner(), null, null);
        var source = beans.values().iterator().next();
        var description = invocations.captureMetadata(preparation, DesktopFileImportTool.Source.class, "description", source::description);
        return new Prepared(preparation.owner(), description,
                invocations.prepareProxy(preparation, DesktopFileImportTool.Source.class, source));
    }
    @Override public void publish(PreparedContribution contribution) {
        if (!(contribution instanceof Prepared prepared)) throw new IllegalArgumentException("invalid file import contribution");
        if (prepared.source() != null) registry.register(prepared.owner(), prepared.description(), prepared.source());
    }
    @Override public void withdraw(ExternalCapabilityOwner owner) { registry.unregister(owner); }
}

package top.sywyar.pixivdownload.plugin.lifecycle.capability;

import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.gui.media.DesktopMediaToolRegistry;
import top.sywyar.pixivdownload.plugin.api.gui.media.DesktopMediaTool;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityInvocationRegistry;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityOwner;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityPreparation;

/** 原生媒体工具沿用外置 capability 的准入、撤回和在途调用 drain。 */
@Component
public final class DesktopMediaToolCapabilityAdapter implements ExternalRuntimeCapabilityAdapter {
    private record Prepared(ExternalCapabilityOwner owner, DesktopMediaTool.Description description,
                            DesktopMediaTool.Source source) implements PreparedContribution {}
    private final DesktopMediaToolRegistry registry;
    private final ExternalCapabilityInvocationRegistry invocations;

    public DesktopMediaToolCapabilityAdapter(DesktopMediaToolRegistry registry, ExternalCapabilityInvocationRegistry invocations) {
        this.registry = registry;
        this.invocations = invocations;
    }

    @Override public String capabilityName() { return DesktopMediaTool.Source.class.getName(); }

    @Override public PreparedContribution prepare(ExternalCapabilityPreparation preparation, ConfigurableApplicationContext context) {
        var beans = context.getBeansOfType(DesktopMediaTool.Source.class);
        if (beans.size() > 1) throw new IllegalArgumentException("multiple media tool sources");
        if (beans.isEmpty()) return new Prepared(preparation.owner(), null, null);
        var source = beans.values().iterator().next();
        return new Prepared(preparation.owner(), source.description(),
                invocations.prepareProxy(preparation, DesktopMediaTool.Source.class, source));
    }

    @Override public void publish(PreparedContribution contribution) {
        if (!(contribution instanceof Prepared prepared)) throw new IllegalArgumentException("invalid media tool contribution");
        if (prepared.source() != null) registry.register(prepared.owner(), prepared.description(), prepared.source());
    }

    @Override public void withdraw(ExternalCapabilityOwner owner) { registry.unregister(owner); }
}

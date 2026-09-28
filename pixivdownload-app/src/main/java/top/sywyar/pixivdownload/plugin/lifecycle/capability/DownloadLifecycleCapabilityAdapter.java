package top.sywyar.pixivdownload.plugin.lifecycle.capability;

import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.core.download.DownloadLifecycleRegistry;
import top.sywyar.pixivdownload.plugin.api.download.lifecycle.DownloadAdmissionPolicy;
import top.sywyar.pixivdownload.plugin.api.download.lifecycle.DownloadObserver;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityInvocationRegistry;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityOwner;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityPreparation;

/** 复用精确 publication 的准入、撤回和在途 drain。 */
@Component
public final class DownloadLifecycleCapabilityAdapter implements ExternalRuntimeCapabilityAdapter {
    private record Prepared(ExternalCapabilityOwner owner,
                            DownloadLifecycleRegistry.Contribution value) implements PreparedContribution {}

    private final DownloadLifecycleRegistry registry;
    private final ExternalCapabilityInvocationRegistry invocations;

    public DownloadLifecycleCapabilityAdapter(DownloadLifecycleRegistry registry,
                                              ExternalCapabilityInvocationRegistry invocations) {
        this.registry = registry;
        this.invocations = invocations;
    }

    @Override
    public String capabilityName() {
        return DownloadObserver.class.getName();
    }

    @Override
    public PreparedContribution prepare(ExternalCapabilityPreparation preparation,
                                        ConfigurableApplicationContext context) {
        var observers = context.getBeansOfType(DownloadObserver.class).values().stream()
                .map(bean -> invocations.prepareProxy(preparation, DownloadObserver.class, bean)).toList();
        var policies = context.getBeansOfType(DownloadAdmissionPolicy.class).values().stream()
                .map(bean -> invocations.prepareProxy(preparation, DownloadAdmissionPolicy.class, bean)).toList();
        return new Prepared(preparation.owner(), new DownloadLifecycleRegistry.Contribution(observers, policies));
    }

    @Override
    public void publish(PreparedContribution contribution) {
        if (!(contribution instanceof Prepared prepared)) {
            throw new IllegalArgumentException("invalid download lifecycle contribution");
        }
        registry.register(prepared.owner(), prepared.value());
    }

    @Override
    public void withdraw(ExternalCapabilityOwner owner) {
        registry.withdraw(owner);
    }
}

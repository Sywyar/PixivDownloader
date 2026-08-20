package top.sywyar.pixivdownload.plugin.lifecycle.capability;

import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.gui.controlcenter.DesktopControlCenterRegistry;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopAutomationSource;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopDashboardSource;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityInvocationRegistry;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityOwner;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityPreparation;

import java.util.List;
import java.util.Map;

/** 把 child context 的控制中心事实源代理接入精确 owner publication 生命周期。 */
@Component
public final class DesktopControlCenterCapabilityAdapter implements ExternalRuntimeCapabilityAdapter {

    private record Prepared(ExternalCapabilityOwner owner,
                            List<DesktopDashboardSource> dashboards,
                            List<DesktopAutomationSource> automations) implements PreparedContribution {
        private Prepared {
            dashboards = List.copyOf(dashboards);
            automations = List.copyOf(automations);
        }
    }

    private final DesktopControlCenterRegistry registry;
    private final ExternalCapabilityInvocationRegistry invocations;

    public DesktopControlCenterCapabilityAdapter(DesktopControlCenterRegistry registry,
                                                 ExternalCapabilityInvocationRegistry invocations) {
        this.registry = registry;
        this.invocations = invocations;
    }

    @Override
    public String capabilityName() {
        return DesktopDashboardSource.class.getName();
    }

    @Override
    public PreparedContribution prepare(ExternalCapabilityPreparation preparation,
                                        ConfigurableApplicationContext context) {
        return new Prepared(preparation.owner(),
                proxySingle(preparation, context.getBeansOfType(DesktopDashboardSource.class),
                        DesktopDashboardSource.class),
                proxySingle(preparation, context.getBeansOfType(DesktopAutomationSource.class),
                        DesktopAutomationSource.class));
    }

    @Override
    public void publish(PreparedContribution contribution) {
        Prepared prepared = requirePrepared(contribution);
        registry.registerPrepared(prepared.owner(), prepared.dashboards(), prepared.automations());
    }

    @Override
    public void withdraw(ExternalCapabilityOwner owner) {
        registry.unregisterPrepared(owner);
    }

    private <T> List<T> proxySingle(ExternalCapabilityPreparation preparation,
                                    Map<String, T> beans,
                                    Class<T> type) {
        if (beans.size() != 1) {
            if (beans.size() > 1) {
                throw new IllegalArgumentException("multiple desktop control-center source beans: "
                        + type.getName());
            }
            return List.of();
        }
        T target = beans.values().iterator().next();
        return List.of(invocations.prepareProxy(preparation, type, target));
    }

    private static Prepared requirePrepared(PreparedContribution contribution) {
        if (contribution instanceof Prepared prepared) {
            return prepared;
        }
        throw new IllegalArgumentException("invalid prepared desktop control-center contribution");
    }
}

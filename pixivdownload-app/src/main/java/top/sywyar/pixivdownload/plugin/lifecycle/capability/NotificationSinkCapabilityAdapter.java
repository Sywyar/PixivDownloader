package top.sywyar.pixivdownload.plugin.lifecycle.capability;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.core.notification.NotificationSinkRegistry;
import top.sywyar.pixivdownload.notification.NotificationSink;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityInvocationRegistry;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityOwner;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityPreparation;

import java.util.List;

@Component
public class NotificationSinkCapabilityAdapter implements PluginCapabilityContributionAdapter<NotificationSink>,
        ExternalRuntimeCapabilityAdapter {

    private record Prepared(
            ExternalCapabilityOwner owner,
            List<NotificationSinkRegistry.PreparedSink> sinks
    ) implements PreparedContribution {
        private Prepared {
            sinks = List.copyOf(sinks);
        }
    }

    private final NotificationSinkRegistry registry;
    private final ExternalCapabilityInvocationRegistry invocationRegistry;

    public NotificationSinkCapabilityAdapter(NotificationSinkRegistry registry) {
        this(registry, new ExternalCapabilityInvocationRegistry());
    }

    @Autowired
    public NotificationSinkCapabilityAdapter(
            NotificationSinkRegistry registry,
            ExternalCapabilityInvocationRegistry invocationRegistry) {
        this.registry = registry;
        this.invocationRegistry = invocationRegistry;
    }

    @Override
    public Class<NotificationSink> beanType() {
        return NotificationSink.class;
    }

    @Override
    public String capabilityName() {
        return NotificationSink.class.getName();
    }

    @Override
    public void register(String pluginId, List<NotificationSink> beans) {
        registry.register(pluginId, beans);
    }

    @Override
    public void unregister(String pluginId) {
        registry.unregister(pluginId);
    }

    @Override
    public PreparedContribution prepare(
            ExternalCapabilityPreparation preparation,
            ConfigurableApplicationContext context) {
        List<NotificationSinkRegistry.PreparedSink> sinks = context
                .getBeansOfType(NotificationSink.class).values().stream()
                .map(target -> {
                    String medium = invocationRegistry.captureMetadata(
                            preparation, NotificationSink.class, "sink medium", target::medium);
                    NotificationSink proxy = invocationRegistry.prepareProxy(
                            preparation, NotificationSink.class, target);
                    return new NotificationSinkRegistry.PreparedSink(
                            medium, proxy, target.getClass().getName());
                })
                .toList();
        return new Prepared(preparation.owner(), sinks);
    }

    @Override
    public void publish(PreparedContribution contribution) {
        Prepared prepared = requirePrepared(contribution);
        registry.registerPrepared(
                prepared.owner().pluginId(), prepared.owner().publicationId(), prepared.sinks());
    }

    @Override
    public void withdraw(ExternalCapabilityOwner owner) {
        registry.unregisterPrepared(owner.pluginId(), owner.publicationId());
    }

    private static Prepared requirePrepared(PreparedContribution contribution) {
        if (contribution instanceof Prepared prepared) {
            return prepared;
        }
        throw new IllegalArgumentException("invalid prepared notification capability contribution");
    }
}

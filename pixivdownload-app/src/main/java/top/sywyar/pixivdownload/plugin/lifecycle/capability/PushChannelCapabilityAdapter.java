package top.sywyar.pixivdownload.plugin.lifecycle.capability;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.core.push.PushChannelRegistry;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityInvocationRegistry;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityOwner;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityPreparation;
import top.sywyar.pixivdownload.push.PushChannel;
import top.sywyar.pixivdownload.push.PushChannelType;

import java.util.List;

@Component
public class PushChannelCapabilityAdapter implements PluginCapabilityContributionAdapter<PushChannel>,
        ExternalRuntimeCapabilityAdapter {

    private record Prepared(
            ExternalCapabilityOwner owner,
            List<PushChannelRegistry.PreparedChannel> channels
    ) implements PreparedContribution {
        private Prepared {
            channels = List.copyOf(channels);
        }
    }

    private final PushChannelRegistry registry;
    private final ExternalCapabilityInvocationRegistry invocationRegistry;

    public PushChannelCapabilityAdapter(PushChannelRegistry registry) {
        this(registry, new ExternalCapabilityInvocationRegistry());
    }

    @Autowired
    public PushChannelCapabilityAdapter(
            PushChannelRegistry registry,
            ExternalCapabilityInvocationRegistry invocationRegistry) {
        this.registry = registry;
        this.invocationRegistry = invocationRegistry;
    }

    @Override
    public Class<PushChannel> beanType() {
        return PushChannel.class;
    }

    @Override
    public String capabilityName() {
        return PushChannel.class.getName();
    }

    @Override
    public void register(String pluginId, List<PushChannel> beans) {
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
        List<PushChannelRegistry.PreparedChannel> channels = context
                .getBeansOfType(PushChannel.class).values().stream()
                .map(target -> {
                    PushChannelType type = invocationRegistry.captureMetadata(
                            preparation, PushChannel.class, "channel type", target::type);
                    PushChannel proxy = invocationRegistry.prepareProxy(
                            preparation, PushChannel.class, target);
                    return new PushChannelRegistry.PreparedChannel(
                            type, proxy, target.getClass().getName());
                })
                .toList();
        return new Prepared(preparation.owner(), channels);
    }

    @Override
    public void publish(PreparedContribution contribution) {
        Prepared prepared = requirePrepared(contribution);
        registry.registerPrepared(
                prepared.owner().pluginId(), prepared.owner().publicationId(), prepared.channels());
    }

    @Override
    public void withdraw(ExternalCapabilityOwner owner) {
        registry.unregisterPrepared(owner.pluginId(), owner.publicationId());
    }

    private static Prepared requirePrepared(PreparedContribution contribution) {
        if (contribution instanceof Prepared prepared) {
            return prepared;
        }
        throw new IllegalArgumentException("invalid prepared push capability contribution");
    }
}

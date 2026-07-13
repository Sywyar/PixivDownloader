package top.sywyar.pixivdownload.plugin.lifecycle.capability;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.ai.AiChatClient;
import top.sywyar.pixivdownload.core.ai.AiChatClientRegistry;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityInvocationRegistry;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityOwner;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityPreparation;

import java.util.List;

@Component
public class AiChatClientCapabilityAdapter implements PluginCapabilityContributionAdapter<AiChatClient>,
        ExternalRuntimeCapabilityAdapter {

    private record Prepared(
            ExternalCapabilityOwner owner,
            List<AiChatClient> clients
    ) implements PreparedContribution {
        private Prepared {
            clients = List.copyOf(clients);
        }
    }

    private final AiChatClientRegistry registry;
    private final ExternalCapabilityInvocationRegistry invocationRegistry;

    public AiChatClientCapabilityAdapter(AiChatClientRegistry registry) {
        this(registry, new ExternalCapabilityInvocationRegistry());
    }

    @Autowired
    public AiChatClientCapabilityAdapter(
            AiChatClientRegistry registry,
            ExternalCapabilityInvocationRegistry invocationRegistry) {
        this.registry = registry;
        this.invocationRegistry = invocationRegistry;
    }

    @Override
    public Class<AiChatClient> beanType() {
        return AiChatClient.class;
    }

    @Override
    public String capabilityName() {
        return AiChatClient.class.getName();
    }

    @Override
    public void register(String pluginId, List<AiChatClient> beans) {
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
        List<AiChatClient> clients = context.getBeansOfType(AiChatClient.class).values().stream()
                .map(target -> invocationRegistry.prepareProxy(preparation, AiChatClient.class, target))
                .toList();
        return new Prepared(preparation.owner(), clients);
    }

    @Override
    public void publish(PreparedContribution contribution) {
        Prepared prepared = requirePrepared(contribution);
        registry.registerPrepared(
                prepared.owner().pluginId(), prepared.owner().publicationId(), prepared.clients());
    }

    @Override
    public void withdraw(ExternalCapabilityOwner owner) {
        registry.unregisterPrepared(owner.pluginId(), owner.publicationId());
    }

    private static Prepared requirePrepared(PreparedContribution contribution) {
        if (contribution instanceof Prepared prepared) {
            return prepared;
        }
        throw new IllegalArgumentException("invalid prepared AI capability contribution");
    }
}

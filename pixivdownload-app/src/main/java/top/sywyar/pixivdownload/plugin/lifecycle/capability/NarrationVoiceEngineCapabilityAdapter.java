package top.sywyar.pixivdownload.plugin.lifecycle.capability;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.core.narration.NarrationEngineRegistry;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityInvocationRegistry;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityOwner;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityPreparation;
import top.sywyar.pixivdownload.tts.narration.engine.NarrationVoiceEngine;

import java.util.List;

@Component
public class NarrationVoiceEngineCapabilityAdapter
        implements PluginCapabilityContributionAdapter<NarrationVoiceEngine>, ExternalRuntimeCapabilityAdapter {

    private record Prepared(
            ExternalCapabilityOwner owner,
            List<NarrationEngineRegistry.PreparedEngine> engines
    ) implements PreparedContribution {
        private Prepared {
            engines = List.copyOf(engines);
        }
    }

    private final NarrationEngineRegistry registry;
    private final ExternalCapabilityInvocationRegistry invocationRegistry;

    public NarrationVoiceEngineCapabilityAdapter(NarrationEngineRegistry registry) {
        this(registry, new ExternalCapabilityInvocationRegistry());
    }

    @Autowired
    public NarrationVoiceEngineCapabilityAdapter(
            NarrationEngineRegistry registry,
            ExternalCapabilityInvocationRegistry invocationRegistry) {
        this.registry = registry;
        this.invocationRegistry = invocationRegistry;
    }

    @Override
    public Class<NarrationVoiceEngine> beanType() {
        return NarrationVoiceEngine.class;
    }

    @Override
    public String capabilityName() {
        return NarrationVoiceEngine.class.getName();
    }

    @Override
    public void register(String pluginId, List<NarrationVoiceEngine> beans) {
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
        List<NarrationEngineRegistry.PreparedEngine> engines = context
                .getBeansOfType(NarrationVoiceEngine.class).values().stream()
                .map(target -> {
                    String id = invocationRegistry.captureMetadata(
                            preparation, NarrationVoiceEngine.class, "engine id", target::id);
                    NarrationVoiceEngine proxy = invocationRegistry.prepareProxy(
                            preparation, NarrationVoiceEngine.class, target);
                    return new NarrationEngineRegistry.PreparedEngine(
                            id, proxy, target.getClass().getName());
                })
                .toList();
        return new Prepared(preparation.owner(), engines);
    }

    @Override
    public void publish(PreparedContribution contribution) {
        Prepared prepared = requirePrepared(contribution);
        registry.registerPrepared(
                prepared.owner().pluginId(), prepared.owner().publicationId(), prepared.engines());
    }

    @Override
    public void withdraw(ExternalCapabilityOwner owner) {
        registry.unregisterPrepared(owner.pluginId(), owner.publicationId());
    }

    private static Prepared requirePrepared(PreparedContribution contribution) {
        if (contribution instanceof Prepared prepared) {
            return prepared;
        }
        throw new IllegalArgumentException("invalid prepared narration capability contribution");
    }
}

package top.sywyar.pixivdownload.plugin.lifecycle.capability;

import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.ai.AiChatClient;
import top.sywyar.pixivdownload.core.ai.AiChatClientRegistry;

import java.util.List;

@Component
public class AiChatClientCapabilityAdapter implements PluginCapabilityContributionAdapter<AiChatClient> {

    private final AiChatClientRegistry registry;

    public AiChatClientCapabilityAdapter(AiChatClientRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Class<AiChatClient> beanType() {
        return AiChatClient.class;
    }

    @Override
    public void register(String pluginId, List<AiChatClient> beans) {
        registry.register(pluginId, beans);
    }

    @Override
    public void unregister(String pluginId) {
        registry.unregister(pluginId);
    }
}

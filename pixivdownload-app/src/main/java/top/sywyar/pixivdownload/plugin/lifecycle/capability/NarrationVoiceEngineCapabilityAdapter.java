package top.sywyar.pixivdownload.plugin.lifecycle.capability;

import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.tts.narration.engine.NarrationEngineRegistry;
import top.sywyar.pixivdownload.tts.narration.engine.NarrationVoiceEngine;

import java.util.List;

@Component
public class NarrationVoiceEngineCapabilityAdapter implements PluginCapabilityContributionAdapter<NarrationVoiceEngine> {

    private final NarrationEngineRegistry registry;

    public NarrationVoiceEngineCapabilityAdapter(NarrationEngineRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Class<NarrationVoiceEngine> beanType() {
        return NarrationVoiceEngine.class;
    }

    @Override
    public void register(String pluginId, List<NarrationVoiceEngine> beans) {
        registry.register(pluginId, beans);
    }

    @Override
    public void unregister(String pluginId) {
        registry.unregister(pluginId);
    }
}

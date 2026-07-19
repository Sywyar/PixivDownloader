package top.sywyar.pixivdownload.core.narration;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.tts.narration.engine.NarrationVoiceSelection;
import top.sywyar.pixivdownload.tts.narration.engine.NarrationVoiceSelector;

import java.util.Optional;

/** 宿主朗读配置与活动引擎 registry 的稳定端口适配器。 */
@Component
@RequiredArgsConstructor
public class CoreNarrationVoiceSelectorAdapter implements NarrationVoiceSelector {

    private final NarrationEngineRegistry registry;
    private final NarrationTtsConfig config;

    @Override
    public String configuredEngineId() {
        String configured = config.getEngine();
        return configured == null ? "" : configured;
    }

    @Override
    public Optional<NarrationVoiceSelection> selected() {
        return registry.selectedPrepared(configuredEngineId())
                .map(engine -> new NarrationVoiceSelection(engine.id(), engine.engine()));
    }

    @Override
    public int availableEngineCount() {
        return registry.count();
    }
}

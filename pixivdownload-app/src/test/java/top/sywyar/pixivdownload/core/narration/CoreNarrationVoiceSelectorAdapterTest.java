package top.sywyar.pixivdownload.core.narration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.tts.narration.engine.NarrationVoiceEngine;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("宿主朗读引擎选择端口适配器")
class CoreNarrationVoiceSelectorAdapterTest {

    @Test
    @DisplayName("按宿主配置返回发布时捕获的引擎 id 与代理")
    void resolvesConfiguredEngineSnapshot() {
        NarrationVoiceEngine engine = mock(NarrationVoiceEngine.class);
        when(engine.id()).thenReturn("sample");
        NarrationEngineRegistry registry = new NarrationEngineRegistry(List.of(engine));
        NarrationTtsConfig config = new NarrationTtsConfig();
        config.setEngine("sample");
        CoreNarrationVoiceSelectorAdapter adapter =
                new CoreNarrationVoiceSelectorAdapter(registry, config);

        var selected = adapter.selected().orElseThrow();

        assertThat(adapter.configuredEngineId()).isEqualTo("sample");
        assertThat(adapter.availableEngineCount()).isEqualTo(1);
        assertThat(selected.id()).isEqualTo("sample");
        assertThat(selected.engine()).isSameAs(engine);
    }

    @Test
    @DisplayName("配置未命中活动引擎时返回空选择并保留诊断数量")
    void returnsEmptyWhenConfiguredEngineIsUnavailable() {
        NarrationEngineRegistry registry = new NarrationEngineRegistry(List.of());
        NarrationTtsConfig config = new NarrationTtsConfig();
        config.setEngine("missing");
        CoreNarrationVoiceSelectorAdapter adapter =
                new CoreNarrationVoiceSelectorAdapter(registry, config);

        assertThat(adapter.selected()).isEmpty();
        assertThat(adapter.configuredEngineId()).isEqualTo("missing");
        assertThat(adapter.availableEngineCount()).isZero();
    }
}

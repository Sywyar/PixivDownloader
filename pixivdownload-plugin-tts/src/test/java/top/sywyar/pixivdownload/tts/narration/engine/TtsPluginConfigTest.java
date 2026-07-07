package top.sywyar.pixivdownload.tts.narration.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TTS 插件配置")
class TtsPluginConfigTest {

    @Test
    @DisplayName("空的布尔和整数插件配置应回退默认值")
    void blankBooleanAndIntegerValuesFallBackToDefaults() {
        MutablePropertySources sources = new MutablePropertySources();
        sources.addLast(new MapPropertySource("test", Map.ofEntries(
                Map.entry("narration-tts.voxcpm.use-proxy", ""),
                Map.entry("narration-tts.voxcpm.enable-clone", ""),
                Map.entry("narration-tts.voxcpm.max-new-tokens", ""),
                Map.entry("narration-tts.mimo.use-proxy", ""),
                Map.entry("narration-tts.mimo.enable-clone", ""),
                Map.entry("narration-tts.cosyvoice.use-proxy", ""),
                Map.entry("narration-tts.cosyvoice.enable-clone", ""),
                Map.entry("narration-tts.fish.use-proxy", ""),
                Map.entry("narration-tts.minimax.sample-rate", ""),
                Map.entry("narration-tts.minimax.use-proxy", ""),
                Map.entry("narration-tts.elevenlabs.use-proxy", ""),
                Map.entry("narration-tts.qwen.use-proxy", ""),
                Map.entry("narration-tts.doubao.use-proxy", "")
        )));

        TtsPluginConfig config = new Binder(ConfigurationPropertySources.from(sources))
                .bind("narration-tts", Bindable.of(TtsPluginConfig.class))
                .orElseGet(TtsPluginConfig::new);

        assertThat(config.getVoxcpm().getUseProxy()).isFalse();
        assertThat(config.getVoxcpm().getEnableClone()).isTrue();
        assertThat(config.getVoxcpm().getMaxNewTokens()).isEqualTo(4096);
        assertThat(config.getMimo().getUseProxy()).isFalse();
        assertThat(config.getMimo().getEnableClone()).isTrue();
        assertThat(config.getCosyvoice().getUseProxy()).isFalse();
        assertThat(config.getCosyvoice().getEnableClone()).isTrue();
        assertThat(config.getFish().getUseProxy()).isFalse();
        assertThat(config.getMinimax().getSampleRate()).isEqualTo(32000);
        assertThat(config.getMinimax().getUseProxy()).isFalse();
        assertThat(config.getElevenlabs().getUseProxy()).isFalse();
        assertThat(config.getQwen().getUseProxy()).isFalse();
        assertThat(config.getDoubao().getUseProxy()).isFalse();
    }
}

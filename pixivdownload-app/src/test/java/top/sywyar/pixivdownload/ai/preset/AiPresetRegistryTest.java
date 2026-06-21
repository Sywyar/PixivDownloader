package top.sywyar.pixivdownload.ai.preset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AiPresetRegistry 单元测试")
class AiPresetRegistryTest {

    private final AiPresetRegistry registry = new AiPresetRegistry();

    @Test
    @DisplayName("预设 id 全部唯一")
    void shouldHaveUniqueIds() {
        Set<String> ids = new HashSet<>();
        for (AiPreset preset : registry.all()) {
            assertThat(ids.add(preset.id()))
                    .as("duplicate preset id: " + preset.id())
                    .isTrue();
        }
        assertThat(ids).contains("custom", "openai", "deepseek", "anthropic");
    }

    @Test
    @DisplayName("custom 哨兵必须位于列表末尾")
    void customSentinelShouldBeLast() {
        List<AiPreset> all = registry.all();
        assertThat(all.get(all.size() - 1).isCustom()).isTrue();
    }

    @Test
    @DisplayName("按 base-url 反查命中已知服务商（忽略大小写与结尾斜杠）")
    void shouldFindPresetByBaseUrl() {
        assertThat(registry.findByBaseUrl("https://api.openai.com/v1"))
                .isPresent()
                .get()
                .extracting(AiPreset::id)
                .isEqualTo("openai");

        // 大小写不敏感 + 结尾斜杠归一化
        assertThat(registry.findByBaseUrl("HTTPS://API.DEEPSEEK.COM/"))
                .isPresent()
                .get()
                .extracting(AiPreset::id)
                .isEqualTo("deepseek");
    }

    @Test
    @DisplayName("按 base-url 反查未命中返回 empty，不会返回 custom 哨兵")
    void shouldNotMatchCustomOnUnknownBaseUrl() {
        assertThat(registry.findByBaseUrl("https://api.example.com/v1")).isEmpty();
        assertThat(registry.findByBaseUrl("")).isEmpty();
        assertThat(registry.findByBaseUrl(null)).isEmpty();
    }

    @Test
    @DisplayName("findById 大小写不敏感且可定位 custom")
    void findByIdShouldBeCaseInsensitive() {
        assertThat(registry.findById("OPENAI"))
                .isPresent()
                .get()
                .extracting(AiPreset::id)
                .isEqualTo("openai");

        assertThat(registry.findById("custom"))
                .isPresent()
                .get()
                .extracting(AiPreset::isCustom)
                .isEqualTo(true);
    }

    @Test
    @DisplayName("海外预设默认走代理、国内 / 本地默认不走代理")
    void defaultUseProxyMatchesSpec() {
        assertThat(registry.findById("openai").orElseThrow().defaultUseProxy()).isTrue();
        assertThat(registry.findById("anthropic").orElseThrow().defaultUseProxy()).isTrue();
        assertThat(registry.findById("deepseek").orElseThrow().defaultUseProxy()).isFalse();
        assertThat(registry.findById("ollama").orElseThrow().defaultUseProxy()).isFalse();
    }

    @Test
    @DisplayName("预设的 base-url / 默认模型与设计一致")
    void presetFieldsMatchDesign() {
        AiPreset deepseek = registry.findById("deepseek").orElseThrow();
        assertThat(deepseek.baseUrl()).isEqualTo("https://api.deepseek.com");
        assertThat(deepseek.defaultModel()).isEqualTo("deepseek-v4-flash");

        AiPreset custom = registry.custom();
        assertThat(custom.isCustom()).isTrue();
        assertThat(custom.baseUrl()).isEmpty();
    }
}

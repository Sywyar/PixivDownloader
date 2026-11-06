package top.sywyar.pixivdownload.translation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldContribution;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TranslationSettingsTest {
    @Test
    @DisplayName("AI 配置能力缺席时明确标注选项，保留其他引擎和原有选值")
    void labelsMissingAiConfiguration() {
        var contribution = new TranslationPlugin().guiConfigContributions().get(0);
        var engine = contribution.resolveOptions(java.util.Set.of()).fields().stream()
                .filter(field -> field.key().equals("translation.engine")).findFirst().orElseThrow();
        assertThat(engine.enumValueLabelKeys())
                .containsEntry("ai", "translation.engine.ai-unavailable")
                .containsEntry("youdao", "translation.engine.youdao")
                .containsEntry("libretranslate", "translation.engine.libretranslate");
        assertThat(engine.defaultValue()).isEqualTo("ai");
        assertThat(contribution.resolveOptions(java.util.Set.of("ai.chat.configuration"))).isSameAs(contribution);
    }

    private final List<GuiConfigFieldContribution> fields =
            new TranslationPlugin().guiConfigContributions().get(0).fields();

    @Test
    @DisplayName("切换翻译引擎仅显示所需连接字段，自动翻译设置始终可用")
    void showsOnlySelectedEngineSettings() {
        assertThat(visible("ai")).containsExactly("translation.engine", "translation.ai-segment-size", "translation.max-concurrent");
        for (String engine : List.of("youdao", "libretranslate")) {
            var selected = visible(engine);
            assertThat(selected).contains("translation.engine", "translation.max-concurrent",
                    "translation." + engine + ".request-chars", "translation." + engine + ".batch-size",
                    "translation." + engine + ".request-interval-ms", "translation." + engine + ".read-timeout-seconds");
            assertThat(selected).noneMatch(key -> key.contains(engine.equals("youdao") ? "libretranslate" : "youdao"));
            assertThat(selected).doesNotContain("translation.ai-segment-size");
        }
    }

    @Test
    @DisplayName("仅校验所选服务的必填项，允许沿用已存密钥且不强制本地服务认证")
    void requiresOnlySelectedEngineCredentials() {
        assertThat(missing("ai", false)).isEmpty();
        assertThat(missing("youdao", false)).containsExactly("translation.youdao-app-key", "translation.youdao-app-secret");
        assertThat(missing("youdao", true)).containsExactly("translation.youdao-app-key");
        assertThat(missing("libretranslate", false)).containsExactly("translation.libretranslate-url");
        assertThat(fields.stream().filter(GuiConfigFieldContribution::sensitive).map(GuiConfigFieldContribution::key))
                .containsExactly("translation.youdao-app-secret", "translation.libretranslate-api-key");
    }

    private List<String> visible(String engine) {
        return fields.stream().filter(field -> field.visibleWhen().stream()
                .allMatch(condition -> condition.matches(Map.of("translation.engine", engine))))
                .map(GuiConfigFieldContribution::key).toList();
    }

    private List<String> missing(String engine, boolean savedCredential) {
        return fields.stream().filter(field -> field.missingRequiredValue(
                Map.of("translation.engine", engine), savedCredential))
                .map(GuiConfigFieldContribution::key).toList();
    }
}

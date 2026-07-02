package top.sywyar.pixivdownload.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.ai.preset.AiPreset;
import top.sywyar.pixivdownload.ai.preset.AiPresetRegistry;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigActionContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigActionPayloadField;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigActionPayloadType;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigActionResultSource;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldLayoutContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigGroups;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigPresetContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigPresetMatchMode;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigSectionContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigSectionLayout;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AI 插件 GUI 配置贡献")
class AiPluginGuiConfigContributionTest {

    private final AiPlugin plugin = new AiPlugin();

    @Test
    @DisplayName("只贡献 AI 自己的配置字段")
    void contributesOnlyAiFields() {
        List<GuiConfigFieldContribution> fields = fields();

        assertThat(fields).extracting(GuiConfigFieldContribution::key)
                .containsExactly(
                        "ai.enabled",
                        "ai.base-url",
                        "ai.api-key",
                        "ai.model",
                        "ai.use-proxy");
        assertThat(fields).allSatisfy(field -> {
            assertThat(field.key()).startsWith("ai.");
            assertThat(field.groupId()).isEqualTo(GuiConfigGroups.AI);
            assertThat(field.i18nNamespace()).isEqualTo(AiPlugin.ID);
            assertThat(field.contributesGroupVisibility()).isTrue();
        });
    }

    @Test
    @DisplayName("贡献文本模型卡片、预设与测试动作")
    void contributesAiCardPresetsAndAction() {
        GuiConfigSectionContribution section = section();

        assertThat(section.sectionId()).isEqualTo(AiPlugin.GUI_SECTION_ID);
        assertThat(section.groupId()).isEqualTo(GuiConfigGroups.AI);
        assertThat(section.i18nNamespace()).isEqualTo(AiPlugin.ID);
        assertThat(section.layout()).isEqualTo(GuiConfigSectionLayout.CARD_SWITCHER);
        assertThat(section.layoutLabelKey()).isEqualTo("gui.config.ai.modality.label");
        assertThat(section.layoutHelpKey()).isEqualTo("gui.config.ai.modality.help");
        assertThat(section.presetLabelKey()).isEqualTo("gui.config.ai.preset.label");
        assertThat(section.presetHelpKey()).isEqualTo("gui.config.ai.preset.help");
        assertThat(section.mergeable()).isTrue();
        assertThat(section.contributesGroupVisibility()).isTrue();
        assertThat(section.fieldLayouts()).extracting(GuiConfigFieldLayoutContribution::fieldKey)
                .containsExactlyElementsOf(fields().stream().map(GuiConfigFieldContribution::key).toList());
        assertThat(section.fieldLayouts()).allSatisfy(layout -> {
            assertThat(layout.cardId()).isEqualTo("text");
            assertThat(layout.cardLabelKey()).isEqualTo("gui.config.ai.modality.text");
            assertThat(layout.i18nNamespace()).isEqualTo(AiPlugin.ID);
        });

        GuiConfigActionContribution action = section.actions().get(0);
        assertThat(action.actionId()).isEqualTo("ai.test");
        assertThat(action.endpoint()).isEqualTo("ai-test");
        assertThat(action.cardId()).isEqualTo("text");
        assertThat(action.i18nNamespace()).isEqualTo(AiPlugin.ID);
        assertThat(action.readTimeoutMillis()).isEqualTo(120_000);
        assertThat(action.sendingNoticeKey()).isEqualTo("gui.config.ai.test.notice.sending");
        assertThat(action.payloadFields()).extracting(GuiConfigActionPayloadField::fieldKey)
                .containsExactly("ai.base-url", "ai.api-key", "ai.model", "ai.use-proxy");
        assertThat(action.payloadFields().get(3).valueType()).isEqualTo(GuiConfigActionPayloadType.BOOLEAN);
        assertThat(action.resultRules()).hasSize(5);
        assertThat(action.resultRules()).allSatisfy(rule ->
                assertThat(rule.i18nNamespace()).isEqualTo(AiPlugin.ID));
        assertThat(action.resultRules()).flatExtracting(rule -> rule.arguments())
                .anySatisfy(argument ->
                        assertThat(argument.source()).isEqualTo(GuiConfigActionResultSource.RAW_BODY));

        assertThat(section.presets()).hasSize(new AiPresetRegistry().all().size());
        assertThat(section.presets()).extracting(GuiConfigPresetContribution::cardId)
                .containsOnly("text");
        assertThat(section.presets()).allSatisfy(preset -> {
            assertThat(preset.i18nNamespace()).isEqualTo(AiPlugin.ID);
            assertThat(preset.labelKey()).startsWith("ai.preset.name.");
            assertThat(preset.matchMode()).isEqualTo(
                    GuiConfigPresetMatchMode.TRIMMED_TRAILING_SLASH_IGNORE_CASE);
            assertThat(preset.values().keySet()).allMatch(key -> key.startsWith("ai."));
        });
        assertThat(section.presets())
                .filteredOn(preset -> !preset.values().isEmpty())
                .allSatisfy(preset -> {
                    assertThat(preset.matchFieldKey()).isEqualTo("ai.base-url");
                    assertThat(preset.values().keySet()).isEqualTo(Set.of(
                            "ai.base-url", "ai.model", "ai.use-proxy"));
                    assertThat(preset.lockedFieldKeys()).containsExactly("ai.base-url");
                });
        assertThat(section.presets())
                .filteredOn(preset -> AiPreset.CUSTOM_ID.equals(preset.presetId()))
                .singleElement()
                .satisfies(custom -> {
                    GuiConfigPresetContribution preset = (GuiConfigPresetContribution) custom;
                    assertThat(preset.values()).isEmpty();
                    assertThat(preset.lockedFieldKeys()).isEmpty();
                    assertThat(preset.matchFieldKey()).isNull();
                });
    }

    @Test
    @DisplayName("AI GUI 文案命名空间归 AI 插件所有")
    void ownsAiI18nNamespace() {
        assertThat(plugin.i18n()).anySatisfy(i18n -> {
            assertThat(i18n.namespace()).isEqualTo(AiPlugin.ID);
            assertThat(i18n.baseName()).isEqualTo("i18n.web.ai");
        });
    }

    private GuiConfigSectionContribution section() {
        return contributions().stream()
                .flatMap(contribution -> contribution.sections().stream())
                .filter(section -> AiPlugin.GUI_SECTION_ID.equals(section.sectionId()))
                .findFirst()
                .orElseThrow();
    }

    private List<GuiConfigFieldContribution> fields() {
        return contributions().stream()
                .flatMap(contribution -> contribution.fields().stream())
                .toList();
    }

    private List<GuiConfigContribution> contributions() {
        return plugin.guiConfigContributions();
    }
}

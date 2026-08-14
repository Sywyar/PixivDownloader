package top.sywyar.pixivdownload.tts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigCondition;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldLayoutContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldType;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigGroups;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigSectionContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigSectionLayout;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigSectionNoticeStyle;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TTS 插件 GUI 配置贡献")
class TtsPluginGuiConfigContributionTest {

    private static final Set<String> CREDENTIAL_FIELDS = Set.of(
            "narration-tts.voxcpm.api-key",
            "narration-tts.mimo.api-key",
            "narration-tts.cosyvoice.api-key",
            "narration-tts.fish.api-key",
            "narration-tts.minimax.api-key",
            "narration-tts.elevenlabs.api-key",
            "narration-tts.qwen.api-key",
            "narration-tts.doubao.access-token");

    private final TtsPlugin plugin = new TtsPlugin();

    @Test
    @DisplayName("只贡献 narration-tts 自己的配置字段")
    void contributesOnlyNarrationTtsFields() {
        List<GuiConfigFieldContribution> fields = fields();

        assertThat(fields).extracting(GuiConfigFieldContribution::key)
                .contains("narration-tts.engine")
                .allMatch(key -> ((String) key).startsWith("narration-tts."));
        assertThat(fields).allSatisfy(field -> {
            assertThat(field.key()).startsWith("narration-tts.");
            assertThat(field.groupId()).isEqualTo(GuiConfigGroups.AI);
            assertThat(field.i18nNamespace()).isEqualTo(TtsPlugin.ID);
            assertThat(field.contributesGroupVisibility()).isTrue();
        });
    }

    @Test
    @DisplayName("各 TTS 引擎凭证统一声明为敏感密码字段")
    void credentialFieldsAreSensitivePasswords() {
        List<GuiConfigFieldContribution> passwordFields = fields().stream()
                .filter(field -> field.type() == GuiConfigFieldType.PASSWORD)
                .toList();

        assertThat(passwordFields).hasSize(CREDENTIAL_FIELDS.size());
        assertThat(passwordFields).extracting(GuiConfigFieldContribution::key)
                .containsExactlyInAnyOrderElementsOf(CREDENTIAL_FIELDS);
        assertThat(passwordFields).allSatisfy(field ->
                assertThat(field.sensitive()).isTrue());
        assertThat(fields()).filteredOn(GuiConfigFieldContribution::sensitive)
                .extracting(GuiConfigFieldContribution::key)
                .containsExactlyInAnyOrderElementsOf(CREDENTIAL_FIELDS);
    }

    @Test
    @DisplayName("引擎字段按旧卡片行为声明可见条件")
    void engineFieldsDeclareCardVisibilityConditions() {
        GuiConfigFieldContribution engine = field("narration-tts.engine");
        GuiConfigFieldContribution baseUrl = field("narration-tts.voxcpm.base-url");
        GuiConfigFieldContribution cloneMode = field("narration-tts.voxcpm.clone-mode");
        GuiConfigCondition voxcpm = GuiConfigCondition.equalsTo("narration-tts.engine", "voxcpm");

        assertThat(engine.visibleWhen()).isEmpty();
        assertThat(engine.enumValueLabelKeys())
                .containsEntry("voxcpm", "gui.config.field.narration-tts.engine.value.voxcpm");
        assertThat(baseUrl.enabledWhen()).containsExactly(voxcpm);
        assertThat(baseUrl.visibleWhen()).containsExactly(voxcpm);
        assertThat(cloneMode.enabledWhen()).containsExactly(
                voxcpm,
                GuiConfigCondition.isTrue("narration-tts.voxcpm.enable-clone"));
        assertThat(cloneMode.visibleWhen()).containsExactly(voxcpm);
    }

    @Test
    @DisplayName("贡献 TTS 卡片布局且不新增旧界面没有的 action 或 preset")
    void contributesTtsCardLayoutWithoutNewControls() {
        GuiConfigSectionContribution section = section();

        assertThat(section.sectionId()).isEqualTo(TtsPlugin.GUI_SECTION_ID);
        assertThat(section.groupId()).isEqualTo(GuiConfigGroups.AI);
        assertThat(section.i18nNamespace()).isEqualTo(TtsPlugin.ID);
        assertThat(section.layout()).isEqualTo(GuiConfigSectionLayout.CARD_SWITCHER);
        assertThat(section.layoutLabelKey()).isEqualTo("gui.config.tts.modality.label");
        assertThat(section.layoutHelpKey()).isEqualTo("gui.config.tts.modality.help");
        assertThat(section.mergeable()).isTrue();
        assertThat(section.contributesGroupVisibility()).isTrue();
        assertThat(section.notices()).singleElement().satisfies(notice -> {
            assertThat(notice.noticeId()).isEqualTo("tts.network-target.security");
            assertThat(notice.textKey()).isEqualTo("gui.config.tts.security.notice");
            assertThat(notice.i18nNamespace()).isEqualTo(TtsPlugin.ID);
            assertThat(notice.style()).isEqualTo(GuiConfigSectionNoticeStyle.HINT);
        });
        assertThat(section.fieldLayouts()).extracting(GuiConfigFieldLayoutContribution::fieldKey)
                .containsExactlyElementsOf(fields().stream().map(GuiConfigFieldContribution::key).toList());
        assertThat(section.fieldLayouts()).allSatisfy(layout -> {
            assertThat(layout.cardId()).isEqualTo("tts");
            assertThat(layout.cardLabelKey()).isEqualTo("gui.config.tts.modality.tts");
            assertThat(layout.i18nNamespace()).isEqualTo(TtsPlugin.ID);
        });
        assertThat(section.actions()).isEmpty();
        assertThat(section.presets()).isEmpty();
    }

    @Test
    @DisplayName("TTS GUI 文案命名空间归 TTS 插件所有")
    void ownsTtsI18nNamespace() {
        assertThat(plugin.i18n()).singleElement().satisfies(i18n -> {
            assertThat(i18n.namespace()).isEqualTo(TtsPlugin.ID);
            assertThat(i18n.baseName()).isEqualTo("i18n.web.tts");
        });
    }

    private GuiConfigSectionContribution section() {
        return contributions().stream()
                .flatMap(contribution -> contribution.sections().stream())
                .filter(section -> TtsPlugin.GUI_SECTION_ID.equals(section.sectionId()))
                .findFirst()
                .orElseThrow();
    }

    private GuiConfigFieldContribution field(String key) {
        return fields().stream()
                .filter(field -> key.equals(field.key()))
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

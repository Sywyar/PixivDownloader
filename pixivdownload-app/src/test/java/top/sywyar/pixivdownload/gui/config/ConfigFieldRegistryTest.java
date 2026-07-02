package top.sywyar.pixivdownload.gui.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.gui.i18n.GuiMessages;
import top.sywyar.pixivdownload.notification.NotificationConfigKeys;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigSectionLayout;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配置面板字段注册守卫：桌面 GUI「插件」分组只呈现插件市场 / 受信仓库配置，插件启停由 Web 插件前端控制，
 * 不在 Swing 配置页呈现 {@code plugins.<id>.enabled} 开关。
 */
@DisplayName("配置面板字段注册：插件分组")
class ConfigFieldRegistryTest {

    @Test
    @DisplayName("插件分组出现在配置分组列表（下载之后、代理之前），i18n 已解析")
    void pluginsGroupListedAfterDownload() {
        List<String> groups = ConfigFieldRegistry.groups();
        String plugins = GuiMessages.get("gui.config.group.plugins");
        String download = GuiMessages.get("gui.config.group.download");
        String proxy = GuiMessages.get("gui.config.group.proxy");

        assertThat(plugins).as("分组名 i18n 已解析（非原始 key）").isNotEqualTo("gui.config.group.plugins");
        assertThat(groups).contains(plugins);
        assertThat(groups.indexOf(download)).isLessThan(groups.indexOf(plugins));
        assertThat(groups.indexOf(plugins)).isLessThan(groups.indexOf(proxy));
    }

    @Test
    @DisplayName("插件分组不呈现插件启停开关")
    void pluginToggleFieldsAreNotRegistered() {
        assertThat(ConfigFieldRegistry.allFields())
                .as("插件启停由 Web 插件前端控制，Swing 配置页不应注册 plugins.* 字段")
                .noneMatch(spec -> spec.key().startsWith("plugins."));
    }

    @Test
    @DisplayName("插件市场主开关仍保留在插件分组")
    void pluginCatalogToggleStillRegistered() {
        String pluginsGroup = GuiMessages.get("gui.config.group.plugins");
        ConfigFieldSpec spec = ConfigFieldRegistry.allFields().stream()
                .collect(Collectors.toMap(ConfigFieldSpec::key, field -> field, (a, b) -> a))
                .get("plugin-catalog.enabled");

        assertThat(spec).as("插件市场主开关应保留").isNotNull();
        assertThat(spec.type()).isEqualTo(FieldType.BOOL);
        assertThat(spec.group()).isEqualTo(pluginsGroup);
        assertThat(spec.defaultValue()).isEqualTo("false");
        assertThat(spec.label()).isNotBlank().doesNotStartWith("gui.config.field.");
    }

    @Test
    @DisplayName("没有 AI / TTS 插件字段贡献时不显示 AI 与朗读分组")
    void aiGroupHiddenWithoutAiOrTtsPluginFields() {
        String aiGroup = GuiMessages.get("gui.config.group.ai");
        String narrationTtsGroup = GuiMessages.get("gui.config.group.narration-tts");

        assertThat(ConfigFieldRegistry.groups())
                .doesNotContain(aiGroup)
                .doesNotContain(narrationTtsGroup);
    }

    @Test
    @DisplayName("仅 AI 插件字段贡献时显示 AI 分组")
    void aiGroupVisibleWithAiPluginFields() {
        String aiGroup = GuiMessages.get("gui.config.group.ai");
        String narrationTtsGroup = GuiMessages.get("gui.config.group.narration-tts");
        ConfigFieldSpec aiField = ConfigFieldSpec.builder(
                        "ai.enabled", "AI", FieldType.BOOL, aiGroup)
                .defaultValue("false")
                .hotReloadable()
                .build();

        ConfigFieldSnapshot snapshot = ConfigFieldRegistry.snapshot(
                new GuiConfigContributionSnapshot(List.of(), List.of(aiField), List.of()));

        assertThat(snapshot.groups()).contains(aiGroup);
        assertThat(snapshot.groups()).doesNotContain(narrationTtsGroup);
    }

    @Test
    @DisplayName("没有通知插件字段贡献时不显示通知分组")
    void notificationGroupHiddenWithoutPluginFields() {
        String notificationGroup = GuiMessages.get("gui.config.group.notification");

        assertThat(ConfigFieldRegistry.groups()).doesNotContain(notificationGroup);
        assertThat(ConfigFieldRegistry.allFields())
                .as("notification.scenario.* 字段由 notification 基础插件贡献，不再属于 app 核心字段")
                .noneMatch(spec -> spec.key().startsWith(NotificationConfigKeys.SCENARIO_PREFIX));
    }

    @Test
    @DisplayName("仅 notification 基础插件贡献场景字段时仍不显示通知分组")
    void notificationGroupHiddenWithOnlyScenarioOwnerFields() {
        String notificationGroup = GuiMessages.get("gui.config.group.notification");
        ConfigFieldSpec scenarioField = scenarioPluginField("run-summary");

        ConfigFieldSnapshot snapshot = ConfigFieldRegistry.snapshot(
                new GuiConfigContributionSnapshot(List.of(), List.of(scenarioField), List.of()));

        assertThat(snapshot.groups())
                .as("保持既有行为：没有邮件或推送介质字段时，不因为基础场景字段单独出现通知页")
                .doesNotContain(notificationGroup);
    }

    @Test
    @DisplayName("仅邮件插件字段贡献时显示通知分组")
    void notificationGroupVisibleWithMailPluginFields() {
        String notificationGroup = GuiMessages.get("gui.config.group.notification");

        ConfigFieldSnapshot snapshot = ConfigFieldRegistry.snapshot(
                new GuiConfigContributionSnapshot(List.of(), List.of(notificationPluginField("mail.enabled")), List.of()));

        assertThat(snapshot.groups()).contains(notificationGroup);
    }

    @Test
    @DisplayName("仅推送插件字段贡献时显示通知分组")
    void notificationGroupVisibleWithPushPluginFields() {
        String notificationGroup = GuiMessages.get("gui.config.group.notification");

        ConfigFieldSnapshot snapshot = ConfigFieldRegistry.snapshot(
                new GuiConfigContributionSnapshot(List.of(), List.of(notificationPluginField("push.enabled")), List.of()));

        assertThat(snapshot.groups()).contains(notificationGroup);
    }

    @Test
    @DisplayName("notification 基础插件 + 邮件插件字段贡献时显示通知分组")
    void notificationGroupVisibleWithScenarioOwnerAndMailPluginFields() {
        String notificationGroup = GuiMessages.get("gui.config.group.notification");
        ConfigFieldSpec scenarioField = scenarioPluginField("run-summary");
        ConfigFieldSpec mailField = notificationPluginField("mail.enabled");

        ConfigFieldSnapshot snapshot = ConfigFieldRegistry.snapshot(
                new GuiConfigContributionSnapshot(List.of(), List.of(scenarioField, mailField), List.of()));

        assertThat(snapshot.groups()).contains(notificationGroup);
    }

    @Test
    @DisplayName("notification 基础插件 + 推送插件字段贡献时显示通知分组")
    void notificationGroupVisibleWithScenarioOwnerAndPushPluginFields() {
        String notificationGroup = GuiMessages.get("gui.config.group.notification");
        ConfigFieldSpec scenarioField = scenarioPluginField("run-summary");
        ConfigFieldSpec pushField = notificationPluginField("push.enabled");

        ConfigFieldSnapshot snapshot = ConfigFieldRegistry.snapshot(
                new GuiConfigContributionSnapshot(List.of(), List.of(scenarioField, pushField), List.of()));

        assertThat(snapshot.groups()).contains(notificationGroup);
    }

    @Test
    @DisplayName("邮件和推送插件字段同时贡献时显示通知分组")
    void notificationGroupVisibleWithMailAndPushPluginFields() {
        String notificationGroup = GuiMessages.get("gui.config.group.notification");
        ConfigFieldSpec mailField = notificationPluginField("mail.enabled");
        ConfigFieldSpec pushField = notificationPluginField("push.enabled");

        ConfigFieldSnapshot snapshot = ConfigFieldRegistry.snapshot(
                new GuiConfigContributionSnapshot(List.of(), List.of(mailField, pushField), List.of()));

        assertThat(snapshot.groups()).contains(notificationGroup);
    }

    @Test
    @DisplayName("仅 section contribution 存在时也显示目标分组")
    void groupVisibleWithSectionContributionOnly() {
        ConfigGroupSpec group = new ConfigGroupSpec("fixture-group", "Fixture Group", 50, true);
        GuiConfigSectionSpec section = new GuiConfigSectionSpec(
                "fixture",
                "fixture.section",
                "fixture-group",
                "Fixture Group",
                50,
                "",
                "",
                "",
                "",
                "",
                "",
                List.of(),
                GuiConfigSectionLayout.FIELD_LIST,
                10,
                List.of(),
                List.of(),
                List.of(),
                false,
                true);

        ConfigFieldSnapshot snapshot = ConfigFieldRegistry.snapshot(
                new GuiConfigContributionSnapshot(List.of(group), List.of(), List.of(section), List.of()));

        assertThat(snapshot.groups()).contains("Fixture Group");
    }

    @Test
    @DisplayName("仅 TTS 插件字段贡献时并入 AI 分组，不单独显示朗读分组")
    void aiGroupVisibleWithTtsPluginFields() {
        String aiGroup = GuiMessages.get("gui.config.group.ai");
        String narrationTtsGroup = GuiMessages.get("gui.config.group.narration-tts");
        ConfigFieldSpec ttsField = ConfigFieldSpec.builder(
                        "narration-tts.engine", "Engine", FieldType.ENUM, narrationTtsGroup)
                .defaultValue("voxcpm")
                .enumValues("voxcpm")
                .hotReloadable()
                .build();

        ConfigFieldSnapshot snapshot = ConfigFieldRegistry.snapshot(
                new GuiConfigContributionSnapshot(List.of(), List.of(ttsField), List.of()));

        assertThat(snapshot.groups()).contains(aiGroup);
        assertThat(snapshot.groups()).doesNotContain(narrationTtsGroup);
    }

    @Test
    @DisplayName("AI 和 TTS 插件字段同时贡献时显示组合后的 AI 分组")
    void aiGroupVisibleWithAiAndTtsPluginFields() {
        String aiGroup = GuiMessages.get("gui.config.group.ai");
        String narrationTtsGroup = GuiMessages.get("gui.config.group.narration-tts");
        ConfigFieldSpec aiField = ConfigFieldSpec.builder(
                        "ai.enabled", "AI", FieldType.BOOL, aiGroup)
                .defaultValue("false")
                .hotReloadable()
                .build();
        ConfigFieldSpec ttsField = ConfigFieldSpec.builder(
                        "narration-tts.engine", "Engine", FieldType.ENUM, narrationTtsGroup)
                .defaultValue("voxcpm")
                .enumValues("voxcpm")
                .hotReloadable()
                .build();

        ConfigFieldSnapshot snapshot = ConfigFieldRegistry.snapshot(
                new GuiConfigContributionSnapshot(List.of(), List.of(aiField, ttsField), List.of()));

        assertThat(snapshot.groups()).contains(aiGroup);
        assertThat(snapshot.groups()).doesNotContain(narrationTtsGroup);
    }

    private static ConfigFieldSpec notificationPluginField(String key) {
        return ConfigFieldSpec.builder(
                        key, "Fixture", FieldType.BOOL, GuiMessages.get("gui.config.group.notification"))
                .defaultValue("false")
                .hotReloadable()
                .build();
    }

    private static ConfigFieldSpec scenarioPluginField(String id) {
        return ConfigFieldSpec.builder(
                        NotificationConfigKeys.scenarioEnabledKey(id),
                        "Scenario",
                        FieldType.BOOL,
                        GuiMessages.get("gui.config.group.notification"))
                .defaultValue("true")
                .hotReloadable()
                .contributesGroupVisibility(false)
                .build();
    }
}

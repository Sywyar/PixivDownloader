package top.sywyar.pixivdownload.gui.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.gui.i18n.GuiMessages;
import top.sywyar.pixivdownload.gui.panel.ConfigPanel;
import top.sywyar.pixivdownload.plugin.PluginToggleProperties;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigActionContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigActionPayloadField;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigActionPayloadType;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigActionResultRule;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigCondition;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldLayoutContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldType;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigGroupContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigGroups;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigPresetContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigPresetMatchMode;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigSectionContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigSectionLayout;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigSectionNoticeContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigSectionNoticeStyle;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginSource;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginDiscoveryResult;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginLoadFailure;

import javax.swing.JButton;
import java.awt.Component;
import java.awt.Container;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GUI 配置字段 contribution 聚合")
class GuiConfigContributionAggregatorTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void resetLocale() {
        GuiMessages.clearLocaleOverride();
        System.clearProperty(RuntimeFiles.CONFIG_DIR_PROPERTY);
    }

    @Test
    @DisplayName("默认无插件贡献时字段与分组保持核心清单")
    void noPluginContributionsKeepCoreFieldsUnchanged() {
        ConfigFieldSnapshot snapshot = ConfigFieldRegistry.snapshot(
                GuiConfigContributionAggregator.from(new PluginRegistry(List.of())));

        assertThat(snapshot.groups()).containsExactlyElementsOf(ConfigFieldRegistry.groups());
        assertThat(snapshot.fields()).extracting(ConfigFieldSpec::key)
                .containsExactlyElementsOf(ConfigFieldRegistry.allFields().stream()
                        .map(ConfigFieldSpec::key)
                        .toList());
        assertThat(snapshot.sections()).isEmpty();
        assertThat(snapshot.diagnostics()).isEmpty();
    }

    @Test
    @DisplayName("插件启用后贡献字段进入配置页快照")
    void enabledPluginFieldsAppearInSnapshot() {
        PixivFeaturePlugin plugin = plugin("fixture", () -> List.of(new GuiConfigContribution(
                List.of(new GuiConfigGroupContribution("fixture-group", "fixture.group.label", 50)),
                List.of(
                        field("fixture.mode", "fixture-group", "fixture.mode.label", GuiConfigFieldType.STRING),
                        new GuiConfigFieldContribution(
                                "fixture.secret",
                                GuiConfigGroups.NOTIFICATION,
                                "fixture.secret.label",
                                "fixture.secret.help",
                                null,
                                GuiConfigFieldType.STRING,
                                "",
                                20,
                                true,
                                false,
                                List.of(),
                                List.of(GuiConfigCondition.isTrue("fixture.enabled")),
                                List.of(),
                                null,
                                null)))));

        ConfigFieldSnapshot snapshot = ConfigFieldRegistry.snapshot(
                GuiConfigContributionAggregator.from(new PluginRegistry(List.of(plugin))));
        ConfigFieldSpec mode = field(snapshot, "fixture.mode");
        ConfigFieldSpec secret = field(snapshot, "fixture.secret");

        assertThat(snapshot.groups()).contains("fixture.group.label");
        assertThat(mode.ownerPluginId()).isEqualTo("fixture");
        assertThat(mode.pluginContributed()).isTrue();
        assertThat(mode.label()).isEqualTo("fixture.mode.label");
        assertThat(mode.group()).isEqualTo("fixture.group.label");
        assertThat(secret.type()).isEqualTo(FieldType.PASSWORD);
        assertThat(secret.requiresRestart()).isFalse();
        assertThat(secret.enabledWhen().test(new ConfigSnapshot(Map.of("fixture.enabled", "true")))).isTrue();
        assertThat(secret.enabledWhen().test(new ConfigSnapshot(Map.of("fixture.enabled", "false")))).isFalse();
        assertThat(snapshot.diagnostics()).isEmpty();
    }

    @Test
    @DisplayName("插件 enum 字段可贡献 value 显示文案")
    void enumFieldValueLabelsAreResolvedIntoSnapshot() {
        PixivFeaturePlugin plugin = plugin("fixture", () -> List.of(new GuiConfigContribution(
                List.of(new GuiConfigFieldContribution(
                        "fixture.mode",
                        GuiConfigGroups.PLUGINS,
                        "fixture.mode.label",
                        "fixture.mode.help",
                        null,
                        GuiConfigFieldType.ENUM,
                        "auto",
                        10,
                        false,
                        false,
                        List.of("auto", "manual"),
                        List.of(),
                        List.of(),
                        null,
                        null,
                        true,
                        Map.of("auto", "fixture.mode.auto"))))));

        ConfigFieldSnapshot snapshot = ConfigFieldRegistry.snapshot(
                GuiConfigContributionAggregator.from(new PluginRegistry(List.of(plugin))));

        assertThat(field(snapshot, "fixture.mode").enumValueLabels())
                .containsEntry("auto", "fixture.mode.auto");
        assertThat(snapshot.diagnostics()).isEmpty();
    }

    @Test
    @DisplayName("section contribution 按分组和 section 顺序聚合并保留 action 与 preset")
    void sectionContributionsAreSortedAndCarried() {
        GuiConfigSectionContribution lateSection = new GuiConfigSectionContribution(
                "late.section",
                GuiConfigGroups.NOTIFICATION,
                "late.section.title",
                "late.section.help",
                null,
                GuiConfigSectionLayout.FIELD_LIST,
                10,
                List.of(new GuiConfigFieldLayoutContribution("late.enabled", 20)),
                List.of(new GuiConfigActionContribution(
                        "late.test",
                        "late.test.label",
                        "late.test.help",
                        null,
                        "late-test",
                        15_000,
                        30,
                        List.of(new GuiConfigActionPayloadField(
                                "enabled", "late.enabled", GuiConfigActionPayloadType.BOOLEAN)))),
                List.of(new GuiConfigPresetContribution(
                        "late.default",
                        "late.default.label",
                        "late.default.help",
                        null,
                        40,
                        "late.enabled",
                        "true",
                        Map.of("late.enabled", "true"))));
        GuiConfigSectionContribution earlySection = new GuiConfigSectionContribution(
                "early.section", GuiConfigGroups.PLUGINS,
                GuiConfigSectionLayout.FIELD_LIST, 20);

        PixivFeaturePlugin lateGroup = plugin("late-group", () -> List.of(new GuiConfigContribution(
                List.of(),
                List.of(field("late.enabled", GuiConfigGroups.NOTIFICATION,
                        "late.enabled.label", GuiConfigFieldType.BOOL)),
                List.of(lateSection))));
        PixivFeaturePlugin earlyGroup = plugin("early-group", () -> List.of(new GuiConfigContribution(
                List.of(),
                List.of(field("early.enabled", GuiConfigGroups.PLUGINS,
                        "early.enabled.label", GuiConfigFieldType.BOOL)),
                List.of(earlySection))));

        GuiConfigContributionSnapshot contributions =
                GuiConfigContributionAggregator.from(new PluginRegistry(List.of(lateGroup, earlyGroup)));

        assertThat(contributions.sections()).extracting(GuiConfigSectionSpec::sectionId)
                .containsExactly("early.section", "late.section");
        GuiConfigSectionSpec late = contributions.sections().stream()
                .filter(section -> "late.section".equals(section.sectionId()))
                .findFirst()
                .orElseThrow();
        assertThat(late.fieldLayouts()).extracting(GuiConfigFieldLayoutSpec::fieldKey)
                .containsExactly("late.enabled");
        assertThat(late.actions()).extracting(GuiConfigActionSpec::actionId)
                .containsExactly("late.test");
        assertThat(late.actions().get(0).payloadFields()).extracting(GuiConfigActionPayloadField::payloadPath)
                .containsExactly("enabled");
        assertThat(late.presets()).extracting(GuiConfigPresetSpec::presetId)
                .containsExactly("late.default");
        assertThat(late.presets().get(0).lockedFieldKeys()).containsExactly("late.enabled");
        assertThat(late.presets().get(0).matchMode()).isEqualTo(GuiConfigPresetMatchMode.EQUALS_IGNORE_CASE);
        assertThat(contributions.diagnostics()).isEmpty();
    }

    @Test
    @DisplayName("preset contribution 保留显式锁定字段和匹配模式")
    void presetContributionCarriesExplicitLocksAndMatchMode() {
        GuiConfigSectionContribution section = new GuiConfigSectionContribution(
                "fixture.section",
                GuiConfigGroups.PLUGINS,
                "",
                "",
                null,
                GuiConfigSectionLayout.FIELD_LIST,
                10,
                List.of(new GuiConfigFieldLayoutContribution("fixture.endpoint", 10)),
                List.of(),
                List.of(new GuiConfigPresetContribution(
                        "fixture.default",
                        "fixture.preset.label",
                        "",
                        null,
                        null,
                        20,
                        "fixture.endpoint",
                        "https://api.example.test",
                        Map.of(
                                "fixture.endpoint", "https://api.example.test",
                                "fixture.model", "fixture-model"),
                        List.of("fixture.endpoint"),
                        GuiConfigPresetMatchMode.TRIMMED_TRAILING_SLASH_IGNORE_CASE)));
        PixivFeaturePlugin plugin = plugin("fixture", () -> List.of(new GuiConfigContribution(
                List.of(),
                List.of(field("fixture.endpoint", GuiConfigGroups.PLUGINS,
                        "fixture.endpoint.label", GuiConfigFieldType.STRING)),
                List.of(section))));

        GuiConfigContributionSnapshot contributions =
                GuiConfigContributionAggregator.from(new PluginRegistry(List.of(plugin)));

        assertThat(contributions.sections()).singleElement().satisfies(resolved -> {
            GuiConfigPresetSpec preset = ((GuiConfigSectionSpec) resolved).presets().get(0);
            assertThat(preset.values()).containsEntry("fixture.model", "fixture-model");
            assertThat(preset.lockedFieldKeys()).containsExactly("fixture.endpoint");
            assertThat(preset.matchMode()).isEqualTo(
                    GuiConfigPresetMatchMode.TRIMMED_TRAILING_SLASH_IGNORE_CASE);
        });
        assertThat(contributions.diagnostics()).isEmpty();
    }

    @Test
    @DisplayName("section notice 会聚合到宿主 section spec")
    void sectionNoticeContributionsAreCarried() {
        GuiConfigSectionContribution section = new GuiConfigSectionContribution(
                "fixture.notice",
                GuiConfigGroups.NOTIFICATION,
                "",
                "",
                null,
                "",
                "",
                "",
                "",
                List.of(new GuiConfigSectionNoticeContribution(
                        "fixture.notice.top",
                        "fixture.notice.text",
                        null,
                        GuiConfigSectionNoticeStyle.HINT,
                        5)),
                GuiConfigSectionLayout.FIELD_LIST,
                10,
                List.of(),
                List.of(),
                List.of(),
                false,
                true);
        PixivFeaturePlugin plugin = plugin("fixture", () -> List.of(new GuiConfigContribution(
                List.of(),
                List.of(),
                List.of(section))));

        GuiConfigContributionSnapshot contributions =
                GuiConfigContributionAggregator.from(new PluginRegistry(List.of(plugin)));

        assertThat(contributions.sections()).singleElement().satisfies(spec -> {
            GuiConfigSectionSpec resolved = (GuiConfigSectionSpec) spec;
            assertThat(resolved.notices()).singleElement().satisfies(notice -> {
                GuiConfigSectionNoticeSpec resolvedNotice = (GuiConfigSectionNoticeSpec) notice;
                assertThat(resolvedNotice.noticeId()).isEqualTo("fixture.notice.top");
                assertThat(resolvedNotice.text()).isEqualTo("fixture.notice.text");
                assertThat(resolvedNotice.style()).isEqualTo(GuiConfigSectionNoticeStyle.HINT);
                assertThat(resolvedNotice.order()).isEqualTo(5);
            });
        });
        assertThat(contributions.diagnostics()).isEmpty();
    }

    @Test
    @DisplayName("可合并 section id 会合并多个插件的布局和动作贡献")
    void mergeableSectionsWithSameIdAreCombined() {
        GuiConfigSectionContribution firstSection = mergeableCardSection(
                "fixture.shared",
                new GuiConfigFieldLayoutContribution("first.enabled", "first", "first.card", "first", 10),
                new GuiConfigActionContribution("first.test", "first.test.label", "first-test", 10, List.of()));
        GuiConfigSectionContribution secondSection = mergeableCardSection(
                "fixture.shared",
                new GuiConfigFieldLayoutContribution("second.enabled", "second", "second.card", "second", 20),
                new GuiConfigActionContribution("second.test", "second.test.label", "second-test", 20, List.of()));
        PixivFeaturePlugin first = plugin("first", () -> List.of(new GuiConfigContribution(
                List.of(),
                List.of(field("first.enabled", GuiConfigGroups.NOTIFICATION,
                        "first.enabled.label", GuiConfigFieldType.BOOL)),
                List.of(firstSection))));
        PixivFeaturePlugin second = plugin("second", () -> List.of(new GuiConfigContribution(
                List.of(),
                List.of(field("second.enabled", GuiConfigGroups.NOTIFICATION,
                        "second.enabled.label", GuiConfigFieldType.BOOL)),
                List.of(secondSection))));

        GuiConfigContributionSnapshot contributions =
                GuiConfigContributionAggregator.from(new PluginRegistry(List.of(first, second)));

        assertThat(contributions.sections()).singleElement().satisfies(section -> {
            assertThat(section.sectionId()).isEqualTo("fixture.shared");
            assertThat(section.mergeable()).isTrue();
            assertThat(section.fieldLayouts()).extracting(GuiConfigFieldLayoutSpec::fieldKey)
                    .containsExactly("first.enabled", "second.enabled");
            assertThat(section.actions()).extracting(GuiConfigActionSpec::actionId)
                    .containsExactly("first.test", "second.test");
            assertThat(section.notices()).singleElement().satisfies(notice -> {
                GuiConfigSectionNoticeSpec resolvedNotice = (GuiConfigSectionNoticeSpec) notice;
                assertThat(resolvedNotice.noticeId()).isEqualTo("fixture.notice");
                assertThat(resolvedNotice.text()).isEqualTo("fixture.notice.text");
            });
        });
        assertThat(contributions.diagnostics()).isEmpty();
    }

    @Test
    @DisplayName("section 引用未知分组时只跳过该 section 并保留其它贡献")
    void sectionWithUnknownGroupIsDiagnosedWithoutDroppingFields() {
        PixivFeaturePlugin plugin = plugin("fixture", () -> List.of(new GuiConfigContribution(
                List.of(),
                List.of(field("fixture.visible", GuiConfigGroups.PLUGINS,
                        "fixture.visible.label", GuiConfigFieldType.BOOL)),
                List.of(new GuiConfigSectionContribution(
                        "fixture.section", "missing-group", GuiConfigSectionLayout.FIELD_LIST, 10)))));

        GuiConfigContributionSnapshot contributions =
                GuiConfigContributionAggregator.from(new PluginRegistry(List.of(plugin)));
        ConfigFieldSnapshot snapshot = ConfigFieldRegistry.snapshot(contributions);

        assertThat(snapshot.fields()).extracting(ConfigFieldSpec::key).contains("fixture.visible");
        assertThat(snapshot.sections()).isEmpty();
        assertThat(snapshot.diagnostics()).anySatisfy(diagnostic -> {
            assertThat(diagnostic.pluginId()).isEqualTo("fixture");
            assertThat(diagnostic.key()).isEqualTo("fixture.section");
            assertThat(diagnostic.message()).contains("unknown group id");
        });
    }

    @Test
    @DisplayName("section action endpoint 只能声明为 api/gui 相对路径段")
    void sectionActionEndpointMustBeRelativeGuiPath() {
        PixivFeaturePlugin plugin = plugin("fixture", () -> List.of(new GuiConfigContribution(
                List.of(),
                List.of(field("fixture.enabled", GuiConfigGroups.PLUGINS,
                        "fixture.enabled.label", GuiConfigFieldType.BOOL)),
                List.of(new GuiConfigSectionContribution(
                        "fixture.section",
                        GuiConfigGroups.PLUGINS,
                        "",
                        "",
                        null,
                        GuiConfigSectionLayout.FIELD_LIST,
                        10,
                        List.of(),
                        List.of(
                                new GuiConfigActionContribution(
                                        "ok", "fixture.ok.label", "plugins/status", 10, List.of()),
                                new GuiConfigActionContribution(
                                        "bad", "fixture.bad.label", "../plugins/status", 20, List.of())),
                        List.of())))));

        GuiConfigContributionSnapshot contributions =
                GuiConfigContributionAggregator.from(new PluginRegistry(List.of(plugin)));
        GuiConfigSectionSpec section = contributions.sections().stream()
                .filter(spec -> "fixture.section".equals(spec.sectionId()))
                .findFirst()
                .orElseThrow();

        assertThat(section.actions()).extracting(GuiConfigActionSpec::actionId)
                .containsExactly("ok");
        assertThat(contributions.diagnostics()).anySatisfy(diagnostic -> {
            assertThat(diagnostic.pluginId()).isEqualTo("fixture");
            assertThat(diagnostic.key()).isEqualTo("fixture.section");
            assertThat(diagnostic.message()).contains("relative /api/gui/ path");
        });
    }

    @Test
    @DisplayName("section action 结果规则缺 notice key 时只跳过该规则")
    void invalidActionResultRulesAreDiagnosedWithoutDroppingAction() {
        GuiConfigActionContribution action = new GuiConfigActionContribution(
                "probe",
                "probe.label",
                "",
                null,
                null,
                "probe",
                30_000,
                10,
                List.of(),
                "",
                List.of(new GuiConfigActionResultRule(" ", 10, List.of(), List.of())),
                null);
        PixivFeaturePlugin plugin = plugin("fixture", () -> List.of(new GuiConfigContribution(
                List.of(),
                List.of(field("fixture.enabled", GuiConfigGroups.PLUGINS,
                        "fixture.enabled.label", GuiConfigFieldType.BOOL)),
                List.of(new GuiConfigSectionContribution(
                        "fixture.section",
                        GuiConfigGroups.PLUGINS,
                        "",
                        "",
                        null,
                        GuiConfigSectionLayout.FIELD_LIST,
                        10,
                        List.of(),
                        List.of(action),
                        List.of())))));

        GuiConfigContributionSnapshot contributions =
                GuiConfigContributionAggregator.from(new PluginRegistry(List.of(plugin)));

        assertThat(contributions.sections()).singleElement()
                .satisfies(section -> assertThat(((GuiConfigSectionSpec) section).actions()).singleElement()
                        .satisfies(resolvedAction -> assertThat(
                                ((GuiConfigActionSpec) resolvedAction).resultRules()).isEmpty()));
        assertThat(contributions.diagnostics()).anySatisfy(diagnostic -> {
            assertThat(diagnostic.pluginId()).isEqualTo("fixture");
            assertThat(diagnostic.key()).isEqualTo("fixture.section");
            assertThat(diagnostic.message()).contains("notice key is blank");
        });
    }

    @Test
    @DisplayName("禁用、未安装、损坏和抛异常的插件不暴露字段")
    void inactiveOrBrokenPluginsDoNotExposeFields() {
        PixivFeaturePlugin plugin = plugin("fixture", () -> List.of(new GuiConfigContribution(
                List.of(field("fixture.visible", GuiConfigGroups.PLUGINS,
                        "fixture.visible.label", GuiConfigFieldType.BOOL)))));

        ConfigFieldSnapshot disabled = ConfigFieldRegistry.snapshot(GuiConfigContributionAggregator.from(
                new PluginRegistry(List.of(plugin), toggles("fixture", false))));
        ConfigFieldSnapshot uninstalled = ConfigFieldRegistry.snapshot(GuiConfigContributionAggregator.from(
                new PluginRegistry(List.of())));
        ConfigFieldSnapshot damaged = ConfigFieldRegistry.snapshot(GuiConfigContributionAggregator.from(
                new PluginRegistry(List.of(), new PluginToggleProperties(),
                        new PluginDiscoveryResult(List.of(),
                                List.of(new PluginLoadFailure("broken.jar", "load failed"))))));
        ConfigFieldSnapshot throwing = ConfigFieldRegistry.snapshot(GuiConfigContributionAggregator.from(
                new PluginRegistry(List.of(plugin("throwing", () -> {
                    throw new IllegalStateException("boom");
                })))));

        assertThat(disabled.fields()).extracting(ConfigFieldSpec::key).doesNotContain("fixture.visible");
        assertThat(uninstalled.fields()).extracting(ConfigFieldSpec::key).doesNotContain("fixture.visible");
        assertThat(damaged.fields()).extracting(ConfigFieldSpec::key).doesNotContain("fixture.visible");
        assertThat(throwing.fields()).extracting(ConfigFieldSpec::key).doesNotContain("fixture.visible");
        assertThat(throwing.diagnostics()).anySatisfy(diagnostic -> {
            assertThat(diagnostic.pluginId()).isEqualTo("throwing");
            assertThat(diagnostic.message()).contains("boom");
        });
    }

    @Test
    @DisplayName("插件 displayNamespace 抛异常时只隔离该插件字段")
    void displayNamespaceFailureIsIsolatedPerPlugin() {
        PixivFeaturePlugin broken = plugin("broken-display", List::of,
                () -> {
                    throw new IllegalStateException("namespace boom");
                },
                () -> List.of(new GuiConfigContribution(List.of(field("broken.display",
                        GuiConfigGroups.PLUGINS, "broken.display.label", GuiConfigFieldType.STRING)))));
        PixivFeaturePlugin healthy = plugin("healthy-display", () -> List.of(new GuiConfigContribution(
                List.of(field("healthy.display", GuiConfigGroups.PLUGINS,
                        "healthy.display.label", GuiConfigFieldType.STRING)))));

        ConfigFieldSnapshot snapshot = ConfigFieldRegistry.snapshot(GuiConfigContributionAggregator.from(
                new PluginRegistry(List.of(broken, healthy))));

        assertThat(snapshot.fields()).extracting(ConfigFieldSpec::key)
                .contains("healthy.display")
                .doesNotContain("broken.display");
        assertThat(snapshot.diagnostics()).anySatisfy(diagnostic -> {
            assertThat(diagnostic.pluginId()).isEqualTo("broken-display");
            assertThat(diagnostic.key()).isEqualTo("broken.display");
            assertThat(diagnostic.message()).contains("namespace boom");
        });
    }

    @Test
    @DisplayName("插件 i18n 抛异常时只隔离该插件字段")
    void i18nFailureIsIsolatedPerPlugin() {
        PixivFeaturePlugin broken = plugin("broken-i18n",
                () -> {
                    throw new IllegalStateException("i18n boom");
                },
                null,
                () -> List.of(new GuiConfigContribution(List.of(field("broken.i18n",
                        GuiConfigGroups.PLUGINS, "broken.i18n.label", GuiConfigFieldType.STRING)))));
        PixivFeaturePlugin healthy = plugin("healthy-i18n", () -> List.of(new GuiConfigContribution(
                List.of(field("healthy.i18n", GuiConfigGroups.PLUGINS,
                        "healthy.i18n.label", GuiConfigFieldType.STRING)))));

        ConfigFieldSnapshot snapshot = ConfigFieldRegistry.snapshot(GuiConfigContributionAggregator.from(
                new PluginRegistry(List.of(broken, healthy))));

        assertThat(snapshot.fields()).extracting(ConfigFieldSpec::key)
                .contains("healthy.i18n")
                .doesNotContain("broken.i18n");
        assertThat(snapshot.diagnostics()).anySatisfy(diagnostic -> {
            assertThat(diagnostic.pluginId()).isEqualTo("broken-i18n");
            assertThat(diagnostic.message()).contains("i18n boom");
        });
    }

    @Test
    @DisplayName("插件 i18n 返回 null 时按无 bundle 处理并回退 key")
    void nullI18nFallsBackToKeys() {
        PixivFeaturePlugin plugin = plugin("null-i18n", () -> null, null,
                () -> List.of(new GuiConfigContribution(List.of(new GuiConfigFieldContribution(
                        "null.i18n",
                        GuiConfigGroups.PLUGINS,
                        "null.i18n.label",
                        "null.i18n.help",
                        null,
                        GuiConfigFieldType.STRING,
                        "",
                        10,
                        false,
                        true,
                        List.of(),
                        List.of(),
                        List.of(),
                        null,
                        null)))));

        ConfigFieldSnapshot snapshot = ConfigFieldRegistry.snapshot(GuiConfigContributionAggregator.from(
                new PluginRegistry(List.of(plugin))));
        ConfigFieldSpec field = field(snapshot, "null.i18n");

        assertThat(field.label()).isEqualTo("null.i18n.label");
        assertThat(field.helpText()).isEqualTo("null.i18n.help");
        assertThat(snapshot.diagnostics()).anySatisfy(diagnostic -> {
            assertThat(diagnostic.pluginId()).isEqualTo("null-i18n");
            assertThat(diagnostic.message()).contains("i18n contribution list is null");
        });
    }

    @Test
    @DisplayName("插件 label/help 解析失败时不会清空其它插件字段")
    void textResolutionFailureDoesNotEmptyWholeSnapshot() {
        PixivFeaturePlugin broken = plugin("broken-text",
                List.of(new I18nContribution("broken-text", null)),
                () -> List.of(new GuiConfigContribution(
                        List.of(new GuiConfigGroupContribution("broken-text-group",
                                "broken.group.label", "broken-text", 10, true)),
                        List.of(
                                field("broken.text", GuiConfigGroups.PLUGINS,
                                        "broken.field.label", GuiConfigFieldType.STRING),
                                field("broken.text.group", "broken-text-group",
                                        "broken.field.group.label", GuiConfigFieldType.STRING)))));
        PixivFeaturePlugin healthy = plugin("healthy-text", () -> List.of(new GuiConfigContribution(
                List.of(field("healthy.text", GuiConfigGroups.PLUGINS,
                        "healthy.text.label", GuiConfigFieldType.STRING)))));

        ConfigFieldSnapshot snapshot = ConfigFieldRegistry.snapshot(
                GuiConfigContributionAggregator.fromRegisteredPlugins(List.of(
                        new PluginRegistry.RegisteredPlugin(broken, PluginSource.EXTERNAL,
                                broken.getClass().getClassLoader()),
                        new PluginRegistry.RegisteredPlugin(healthy, PluginSource.EXTERNAL,
                                healthy.getClass().getClassLoader()))));

        assertThat(snapshot.fields()).extracting(ConfigFieldSpec::key)
                .contains("healthy.text")
                .doesNotContain("broken.text", "broken.text.group");
        assertThat(snapshot.groups()).doesNotContain("broken.group.label");
        assertThat(snapshot.diagnostics()).anySatisfy(diagnostic -> {
            assertThat(diagnostic.pluginId()).isEqualTo("broken-text");
            assertThat(diagnostic.key()).isEqualTo("broken-text-group");
            assertThat(diagnostic.message()).contains("text resolution failed");
        });
        assertThat(snapshot.diagnostics()).anySatisfy(diagnostic -> {
            assertThat(diagnostic.pluginId()).isEqualTo("broken-text");
            assertThat(diagnostic.key()).isEqualTo("broken.text");
            assertThat(diagnostic.message()).contains("text resolution failed");
        });
    }

    @Test
    @DisplayName("重复 key 只产生明确诊断，不任选一个插件字段继续")
    void duplicateKeysAreDiagnosedWithoutSelectingAPluginField() {
        PixivFeaturePlugin coreConflict = plugin("core-conflict", () -> List.of(new GuiConfigContribution(
                List.of(field("server.port", GuiConfigGroups.SERVER,
                        "plugin.server.port.label", GuiConfigFieldType.STRING)))));
        PixivFeaturePlugin first = plugin("dup-first", () -> List.of(new GuiConfigContribution(
                List.of(field("fixture.duplicate", GuiConfigGroups.PLUGINS,
                        "first.label", GuiConfigFieldType.STRING)))));
        PixivFeaturePlugin second = plugin("dup-second", () -> List.of(new GuiConfigContribution(
                List.of(field("fixture.duplicate", GuiConfigGroups.PLUGINS,
                        "second.label", GuiConfigFieldType.STRING)))));

        ConfigFieldSnapshot coreSnapshot = ConfigFieldRegistry.snapshot(GuiConfigContributionAggregator.from(
                new PluginRegistry(List.of(coreConflict))));
        ConfigFieldSnapshot pluginSnapshot = ConfigFieldRegistry.snapshot(GuiConfigContributionAggregator.from(
                new PluginRegistry(List.of(first, second))));

        assertThat(coreSnapshot.fields()).filteredOn(spec -> spec.key().equals("server.port")).hasSize(1);
        assertThat(coreSnapshot.diagnostics()).anySatisfy(diagnostic -> {
            assertThat(diagnostic.pluginId()).isEqualTo("core-conflict");
            assertThat(diagnostic.key()).isEqualTo("server.port");
            assertThat(diagnostic.message()).contains("core");
        });
        assertThat(pluginSnapshot.fields()).extracting(ConfigFieldSpec::key)
                .doesNotContain("fixture.duplicate");
        assertThat(pluginSnapshot.diagnostics()).anySatisfy(diagnostic -> {
            assertThat(diagnostic.pluginId()).isEqualTo("dup-second");
            assertThat(diagnostic.key()).isEqualTo("fixture.duplicate");
            assertThat(diagnostic.message()).contains("dup-first", "dup-second");
        });
    }

    @Test
    @DisplayName("null contribution 与 null 列表安全降级为诊断")
    void nullContributionsAreHandledSafely() {
        ArrayList<GuiConfigContribution> listWithNull = new ArrayList<>();
        listWithNull.add(null);
        PixivFeaturePlugin nullList = plugin("null-list", () -> null);
        PixivFeaturePlugin nullContribution = plugin("null-contribution", () -> listWithNull);
        PixivFeaturePlugin emptyContribution = plugin("empty-contribution", () -> List.of(
                new GuiConfigContribution(null, null)));

        ConfigFieldSnapshot snapshot = ConfigFieldRegistry.snapshot(GuiConfigContributionAggregator.from(
                new PluginRegistry(List.of(nullList, nullContribution, emptyContribution))));

        assertThat(snapshot.fields()).extracting(ConfigFieldSpec::key)
                .doesNotContain("null-list.field", "null-contribution.field", "empty-contribution.field");
        assertThat(snapshot.diagnostics()).anySatisfy(diagnostic ->
                assertThat(diagnostic.pluginId()).isEqualTo("null-list"));
        assertThat(snapshot.diagnostics()).anySatisfy(diagnostic ->
                assertThat(diagnostic.pluginId()).isEqualTo("null-contribution"));
    }

    @Test
    @DisplayName("i18n bundle 经注册插件的 classloader 解析")
    void pluginI18nUsesRegisteredClassLoader() throws Exception {
        Path resourceRoot = tempDir.resolve("resources");
        Path bundleDir = resourceRoot.resolve("i18n/web");
        Files.createDirectories(bundleDir);
        Files.writeString(bundleDir.resolve("fixture.properties"), String.join("\n",
                "fixture.group.label=External Group",
                "fixture.field.label=External Label",
                "fixture.field.help=External Help"), StandardCharsets.UTF_8);
        URL[] urls = {resourceRoot.toUri().toURL()};

        PixivFeaturePlugin plugin = plugin("fixture", List.of(new I18nContribution("fixture", "i18n.web.fixture")),
                () -> List.of(new GuiConfigContribution(
                        List.of(new GuiConfigGroupContribution("fixture-i18n", "fixture.group.label", 10)),
                        List.of(new GuiConfigFieldContribution(
                                "fixture.i18n",
                                "fixture-i18n",
                                "fixture.field.label",
                                "fixture.field.help",
                                "fixture",
                                GuiConfigFieldType.STRING,
                                "",
                                10,
                                false,
                                true,
                                List.of(),
                                List.of(),
                                List.of(),
                                null,
                                null)))));

        try (URLClassLoader loader = new URLClassLoader(urls, null)) {
            ConfigFieldSnapshot snapshot = ConfigFieldRegistry.snapshot(
                    GuiConfigContributionAggregator.fromRegisteredPlugins(List.of(
                            new PluginRegistry.RegisteredPlugin(plugin, PluginSource.EXTERNAL, loader))));

            ConfigFieldSpec spec = field(snapshot, "fixture.i18n");
            assertThat(snapshot.groups()).contains("External Group");
            assertThat(spec.label()).isEqualTo("External Label");
            assertThat(spec.helpText()).isEqualTo("External Help");
        }
    }

    @Test
    @DisplayName("重新聚合插件配置贡献时按当前 GUI 语言解析文案")
    void rebuiltPluginContributionsUseCurrentGuiLocale() throws Exception {
        Path resourceRoot = tempDir.resolve("localized-resources");
        Path bundleDir = resourceRoot.resolve("i18n/web");
        Files.createDirectories(bundleDir);
        Files.writeString(bundleDir.resolve("fixture.properties"), String.join("\n",
                "fixture.group.label=中文分组",
                "fixture.field.label=中文标签"), StandardCharsets.UTF_8);
        Files.writeString(bundleDir.resolve("fixture_en.properties"), String.join("\n",
                "fixture.group.label=English Group",
                "fixture.field.label=English Label"), StandardCharsets.UTF_8);
        URL[] urls = {resourceRoot.toUri().toURL()};

        PixivFeaturePlugin plugin = plugin("fixture", List.of(new I18nContribution("fixture", "i18n.web.fixture")),
                () -> List.of(new GuiConfigContribution(
                        List.of(new GuiConfigGroupContribution("fixture-i18n", "fixture.group.label", 10)),
                        List.of(new GuiConfigFieldContribution(
                                "fixture.i18n",
                                "fixture-i18n",
                                "fixture.field.label",
                                GuiConfigFieldType.STRING,
                                "",
                                10)))));

        try (URLClassLoader loader = new URLClassLoader(urls, null)) {
            GuiMessages.setLocale(Locale.SIMPLIFIED_CHINESE);
            ConfigFieldSnapshot zhSnapshot = ConfigFieldRegistry.snapshot(
                    GuiConfigContributionAggregator.fromRegisteredPlugins(List.of(
                            new PluginRegistry.RegisteredPlugin(plugin, PluginSource.EXTERNAL, loader))));
            ConfigFieldSpec zhSpec = field(zhSnapshot, "fixture.i18n");
            assertThat(zhSnapshot.groups()).contains("中文分组");
            assertThat(zhSpec.label()).isEqualTo("中文标签");
            assertThat(zhSpec.group()).isEqualTo("中文分组");

            GuiMessages.setLocale(Locale.US);
            ConfigFieldSnapshot enSnapshot = ConfigFieldRegistry.snapshot(
                    GuiConfigContributionAggregator.fromRegisteredPlugins(List.of(
                            new PluginRegistry.RegisteredPlugin(plugin, PluginSource.EXTERNAL, loader))));
            ConfigFieldSpec enSpec = field(enSnapshot, "fixture.i18n");
            assertThat(enSnapshot.groups()).contains("English Group");
            assertThat(enSpec.label()).isEqualTo("English Label");
            assertThat(enSpec.group()).isEqualTo("English Group");
        }
    }

    @Test
    @DisplayName("ConfigPanel 消费宿主合并后的字段快照")
    void configPanelConsumesAggregatedSnapshot() {
        PixivFeaturePlugin plugin = plugin("fixture", () -> List.of(new GuiConfigContribution(
                List.of(field("fixture.panel", GuiConfigGroups.PLUGINS,
                        "fixture.panel.label", GuiConfigFieldType.BOOL)))));
        ConfigFieldSnapshot snapshot = ConfigFieldRegistry.snapshot(
                GuiConfigContributionAggregator.from(new PluginRegistry(List.of(plugin))));

        ConfigPanel panel = new ConfigPanel(tempDir.resolve("config.yaml"), 6999,
                path -> "http://localhost:6999" + path, snapshot);

        assertThat(panel.allFields()).extracting(ConfigFieldSpec::key).contains("fixture.panel");
        assertThat(panel.findSpec("fixture.panel")).isNotNull();
    }

    @Test
    @DisplayName("ConfigPanel 保存插件贡献字段到插件自有 properties")
    void configPanelSavesPluginFieldsToPluginProperties() throws Exception {
        System.setProperty(RuntimeFiles.CONFIG_DIR_PROPERTY, tempDir.resolve("runtime-config").toString());
        Path configYaml = tempDir.resolve("config.yaml");
        Files.writeString(configYaml, "server.port: 6999\n", StandardCharsets.UTF_8);
        PixivFeaturePlugin plugin = plugin("fixture", () -> List.of(new GuiConfigContribution(
                List.of(field("fixture.panel", GuiConfigGroups.PLUGINS,
                        "fixture.panel.label", GuiConfigFieldType.STRING)))));
        ConfigFieldSnapshot snapshot = ConfigFieldRegistry.snapshot(
                GuiConfigContributionAggregator.from(new PluginRegistry(List.of(plugin))));
        ConfigPanel panel = new ConfigPanel(configYaml, 6999,
                path -> "http://localhost:6999" + path, snapshot);

        panel.setFieldValue("fixture.panel", "custom-value");
        clickButton(panel, GuiMessages.get("gui.button.save"));

        Path pluginConfig = RuntimeFiles.resolvePluginConfigPath("fixture", "properties");
        Properties properties = loadProperties(pluginConfig);
        assertThat(properties.getProperty("fixture.panel")).isEqualTo("custom-value");
        assertThat(Files.readString(configYaml, StandardCharsets.UTF_8)).doesNotContain("fixture.panel");
    }

    @Test
    @DisplayName("ConfigPanel 迁移旧 config.yaml 插件字段并移除旧键")
    void configPanelMigratesLegacyPluginFieldsFromConfigYaml() throws Exception {
        System.setProperty(RuntimeFiles.CONFIG_DIR_PROPERTY, tempDir.resolve("runtime-config").toString());
        Path configYaml = tempDir.resolve("config.yaml");
        Files.writeString(configYaml, String.join("\n",
                "server.port: 6999",
                "fixture.panel: legacy-value",
                ""), StandardCharsets.UTF_8);
        PixivFeaturePlugin plugin = plugin("fixture", () -> List.of(new GuiConfigContribution(
                List.of(field("fixture.panel", GuiConfigGroups.PLUGINS,
                        "fixture.panel.label", GuiConfigFieldType.STRING)))));
        ConfigFieldSnapshot snapshot = ConfigFieldRegistry.snapshot(
                GuiConfigContributionAggregator.from(new PluginRegistry(List.of(plugin))));

        ConfigPanel panel = new ConfigPanel(configYaml, 6999,
                path -> "http://localhost:6999" + path, snapshot);

        Path pluginConfig = RuntimeFiles.resolvePluginConfigPath("fixture", "properties");
        Properties properties = loadProperties(pluginConfig);
        assertThat(panel.currentFieldValue("fixture.panel")).isEqualTo("legacy-value");
        assertThat(properties.getProperty("fixture.panel")).isEqualTo("legacy-value");
        assertThat(Files.readString(configYaml, StandardCharsets.UTF_8)).doesNotContain("fixture.panel");
    }

    @Test
    @DisplayName("ConfigPanel 迁移插件字段时应保持旧值原样")
    void configPanelMigratesLegacyPluginFieldsWithoutChangingValue() throws Exception {
        System.setProperty(RuntimeFiles.CONFIG_DIR_PROPERTY, tempDir.resolve("runtime-config").toString());
        Path configYaml = tempDir.resolve("config.yaml");
        String legacyValue = "  legacy # value  ";
        Files.writeString(configYaml, String.join("\n",
                "server.port: 6999",
                "fixture.panel: \"  legacy # value  \"",
                ""), StandardCharsets.UTF_8);
        PixivFeaturePlugin plugin = plugin("fixture", () -> List.of(new GuiConfigContribution(
                List.of(field("fixture.panel", GuiConfigGroups.PLUGINS,
                        "fixture.panel.label", GuiConfigFieldType.STRING)))));
        ConfigFieldSnapshot snapshot = ConfigFieldRegistry.snapshot(
                GuiConfigContributionAggregator.from(new PluginRegistry(List.of(plugin))));

        ConfigPanel panel = new ConfigPanel(configYaml, 6999,
                path -> "http://localhost:6999" + path, snapshot);

        Path pluginConfig = RuntimeFiles.resolvePluginConfigPath("fixture", "properties");
        Properties properties = loadProperties(pluginConfig);
        assertThat(panel.currentFieldValue("fixture.panel")).isEqualTo(legacyValue.trim());
        assertThat(properties.getProperty("fixture.panel")).isEqualTo(legacyValue);
        assertThat(Files.readString(configYaml, StandardCharsets.UTF_8)).doesNotContain("fixture.panel");
    }

    @Test
    @DisplayName("ConfigPanel 发现插件配置与旧 config.yaml 值冲突时应保留旧键")
    void configPanelKeepsLegacyYamlWhenPluginValueConflicts() throws Exception {
        System.setProperty(RuntimeFiles.CONFIG_DIR_PROPERTY, tempDir.resolve("runtime-config").toString());
        Path pluginConfig = tempDir.resolve("runtime-config").resolve("plugins").resolve("fixture.properties");
        Files.createDirectories(pluginConfig.getParent());
        Files.writeString(pluginConfig, "fixture.panel=plugin-value\n", StandardCharsets.UTF_8);
        Path configYaml = tempDir.resolve("config.yaml");
        Files.writeString(configYaml, String.join("\n",
                "server.port: 6999",
                "fixture.panel: legacy-value",
                ""), StandardCharsets.UTF_8);
        PixivFeaturePlugin plugin = plugin("fixture", () -> List.of(new GuiConfigContribution(
                List.of(field("fixture.panel", GuiConfigGroups.PLUGINS,
                        "fixture.panel.label", GuiConfigFieldType.STRING)))));
        ConfigFieldSnapshot snapshot = ConfigFieldRegistry.snapshot(
                GuiConfigContributionAggregator.from(new PluginRegistry(List.of(plugin))));

        ConfigPanel panel = new ConfigPanel(configYaml, 6999,
                path -> "http://localhost:6999" + path, snapshot);

        assertThat(panel.currentFieldValue("fixture.panel")).isEqualTo("plugin-value");
        assertThat(loadProperties(pluginConfig).getProperty("fixture.panel")).isEqualTo("plugin-value");
        assertThat(Files.readString(configYaml, StandardCharsets.UTF_8)).contains("fixture.panel: legacy-value");
    }

    @Test
    @DisplayName("ConfigPanel 迁移写入失败时应回退到旧 config.yaml 值且不删除旧键")
    void configPanelFallsBackToLegacyYamlWhenMigrationWriteFails() throws Exception {
        Path runtimeConfig = tempDir.resolve("runtime-config");
        Files.createDirectories(runtimeConfig);
        Files.writeString(runtimeConfig.resolve("plugins"), "not-a-directory", StandardCharsets.UTF_8);
        System.setProperty(RuntimeFiles.CONFIG_DIR_PROPERTY, runtimeConfig.toString());
        Path configYaml = tempDir.resolve("config.yaml");
        Files.writeString(configYaml, String.join("\n",
                "server.port: 6999",
                "fixture.panel: legacy-value",
                ""), StandardCharsets.UTF_8);
        PixivFeaturePlugin plugin = plugin("fixture", () -> List.of(new GuiConfigContribution(
                List.of(field("fixture.panel", GuiConfigGroups.PLUGINS,
                        "fixture.panel.label", GuiConfigFieldType.STRING)))));
        ConfigFieldSnapshot snapshot = ConfigFieldRegistry.snapshot(
                GuiConfigContributionAggregator.from(new PluginRegistry(List.of(plugin))));

        ConfigPanel panel = new ConfigPanel(configYaml, 6999,
                path -> "http://localhost:6999" + path, snapshot);

        assertThat(panel.currentFieldValue("fixture.panel")).isEqualTo("legacy-value");
        assertThat(Files.readString(configYaml, StandardCharsets.UTF_8)).contains("fixture.panel: legacy-value");
        assertThat(runtimeConfig.resolve("plugins")).isRegularFile();
    }

    @Test
    @DisplayName("ConfigPanel 保存非法配置值时不应写入插件 properties")
    void configPanelRejectsUnsafePluginConfigValueOnSave() throws Exception {
        System.setProperty(RuntimeFiles.CONFIG_DIR_PROPERTY, tempDir.resolve("runtime-config").toString());
        Path configYaml = tempDir.resolve("config.yaml");
        Files.writeString(configYaml, "server.port: 6999\n", StandardCharsets.UTF_8);
        PixivFeaturePlugin plugin = plugin("fixture", () -> List.of(new GuiConfigContribution(
                List.of(field("fixture.panel", GuiConfigGroups.PLUGINS,
                        "fixture.panel.label", GuiConfigFieldType.STRING)))));
        ConfigFieldSnapshot snapshot = ConfigFieldRegistry.snapshot(
                GuiConfigContributionAggregator.from(new PluginRegistry(List.of(plugin))));
        ConfigPanel panel = new ConfigPanel(configYaml, 6999,
                path -> "http://localhost:6999" + path, snapshot);

        panel.setFieldValue("fixture.panel", "bad\0value");
        clickButton(panel, GuiMessages.get("gui.button.save"));

        Path pluginConfig = RuntimeFiles.resolvePluginConfigPath("fixture", "properties");
        assertThat(loadProperties(pluginConfig).getProperty("fixture.panel")).isEmpty();
        assertThat(Files.readString(configYaml, StandardCharsets.UTF_8)).doesNotContain("bad");
    }

    private static GuiConfigFieldContribution field(String key, String groupId,
                                                    String labelKey, GuiConfigFieldType type) {
        return new GuiConfigFieldContribution(key, groupId, labelKey, type, "", 10);
    }

    private static GuiConfigSectionContribution mergeableCardSection(String sectionId,
                                                                     GuiConfigFieldLayoutContribution layout,
                                                                     GuiConfigActionContribution action) {
        return new GuiConfigSectionContribution(
                sectionId,
                GuiConfigGroups.NOTIFICATION,
                "",
                "",
                null,
                "layout.label",
                "layout.help",
                "",
                "",
                List.of(new GuiConfigSectionNoticeContribution(
                        "fixture.notice",
                        "fixture.notice.text",
                        null,
                        GuiConfigSectionNoticeStyle.HINT,
                        0)),
                GuiConfigSectionLayout.CARD_SWITCHER,
                200,
                List.of(layout),
                List.of(action),
                List.of(),
                true,
                true);
    }

    private static ConfigFieldSpec field(ConfigFieldSnapshot snapshot, String key) {
        return snapshot.fields().stream()
                .filter(spec -> spec.key().equals(key))
                .findFirst()
                .orElseThrow();
    }

    private static Properties loadProperties(Path path) throws Exception {
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    private static void clickButton(Container container, String text) {
        for (Component component : container.getComponents()) {
            if (component instanceof JButton button && text.equals(button.getText())) {
                button.doClick();
                return;
            }
            if (component instanceof Container child) {
                clickButton(child, text);
            }
        }
    }

    private static PluginToggleProperties toggles(String pluginId, boolean enabled) {
        PluginToggleProperties toggles = new PluginToggleProperties();
        PluginToggleProperties.PluginToggle toggle = new PluginToggleProperties.PluginToggle();
        toggle.setEnabled(enabled);
        toggles.put(pluginId, toggle);
        return toggles;
    }

    private static PixivFeaturePlugin plugin(String id, Supplier<List<GuiConfigContribution>> contributions) {
        return plugin(id, List.of(), contributions);
    }

    private static PixivFeaturePlugin plugin(String id, List<I18nContribution> i18n,
                                             Supplier<List<GuiConfigContribution>> contributions) {
        return plugin(id, () -> i18n, null, contributions);
    }

    private static PixivFeaturePlugin plugin(String id, Supplier<List<I18nContribution>> i18n,
                                             Supplier<String> displayNamespace,
                                             Supplier<List<GuiConfigContribution>> contributions) {
        return new TestPlugin(id, i18n, displayNamespace, contributions);
    }

    private static final class TestPlugin implements PixivFeaturePlugin {
        private final String id;
        private final Supplier<List<I18nContribution>> i18n;
        private final Supplier<String> displayNamespace;
        private final Supplier<List<GuiConfigContribution>> contributions;

        private TestPlugin(String id,
                           Supplier<List<I18nContribution>> i18n,
                           Supplier<String> displayNamespace,
                           Supplier<List<GuiConfigContribution>> contributions) {
            this.id = id;
            this.i18n = i18n;
            this.displayNamespace = displayNamespace;
            this.contributions = contributions;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String displayName() {
            return "plugin.label";
        }

        @Override
        public String description() {
            return "plugin.description";
        }

        @Override
        public PluginKind kind() {
            return PluginKind.FEATURE;
        }

        @Override
        public String displayNamespace() {
            return displayNamespace == null ? PixivFeaturePlugin.super.displayNamespace() : displayNamespace.get();
        }

        @Override
        public List<I18nContribution> i18n() {
            return i18n.get();
        }

        @Override
        public List<GuiConfigContribution> guiConfigContributions() {
            return contributions.get();
        }
    }
}

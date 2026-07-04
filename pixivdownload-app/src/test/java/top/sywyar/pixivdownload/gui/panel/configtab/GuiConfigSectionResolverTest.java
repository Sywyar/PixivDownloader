package top.sywyar.pixivdownload.gui.panel.configtab;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.gui.config.ConfigFieldSnapshot;
import top.sywyar.pixivdownload.gui.config.ConfigFieldRegistry;
import top.sywyar.pixivdownload.gui.config.ConfigFieldSpec;
import top.sywyar.pixivdownload.gui.config.ConfigSnapshot;
import top.sywyar.pixivdownload.gui.config.FieldRenderer;
import top.sywyar.pixivdownload.gui.config.FieldType;
import top.sywyar.pixivdownload.gui.config.GuiConfigFieldLayoutSpec;
import top.sywyar.pixivdownload.gui.config.GuiConfigPresetSpec;
import top.sywyar.pixivdownload.gui.config.GuiConfigSectionNoticeSpec;
import top.sywyar.pixivdownload.gui.config.GuiConfigSectionSpec;
import top.sywyar.pixivdownload.gui.i18n.GuiMessages;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigCondition;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigGroups;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigPresetMatchMode;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigSectionLayout;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigSectionNoticeStyle;
import top.sywyar.pixivdownload.gui.panel.ConfigPanel;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GUI 配置 section resolver")
class GuiConfigSectionResolverTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("没有声明式 section 时不再为 AI 分组创建过渡 adapter")
    void transitionAdaptersRemainPlainSectionsWithoutDeclaredSections() {
        List<String> groups = List.of(
                ConfigFieldRegistry.groupAi(),
                ConfigFieldRegistry.groupNotification(),
                ConfigFieldRegistry.groupPlugins());

        List<ConfigSection> sections = GuiConfigSectionResolver.createSections(
                new RecordingContext(List.of()),
                groups,
                List.of(),
                tempDir.resolve("config.yaml"),
                path -> "http://localhost:6999" + path);

        assertThat(sections).hasSize(1);
        assertThat(sections).noneMatch(section -> ConfigFieldRegistry.groupAi().equals(section.group()));
        assertThat(sections).noneMatch(section -> ConfigFieldRegistry.groupNotification().equals(section.group()));
        assertThat(section(sections, ConfigFieldRegistry.groupPlugins()))
                .isInstanceOf(PluginMarketConfigSection.class);
    }

    @Test
    @DisplayName("同一分组存在声明式 section 时优先使用声明式 section")
    void declaredSectionTakesPrecedenceOverTransitionAdapter() {
        String group = ConfigFieldRegistry.groupAi();
        GuiConfigSectionSpec declared = section(
                "fixture-ai", "fixture.ai.section", GuiConfigGroups.AI, group, 10, "ai.base-url");

        ConfigSection resolved = singleSection(group, List.of(declared));

        assertThat(resolved).isInstanceOf(DeclaredGuiConfigSection.class);
    }

    @Test
    @DisplayName("通知分组声明式 section 不再被过渡 adapter 挡住")
    void notificationDeclaredSectionIsNotBlockedByAdapter() {
        String group = ConfigFieldRegistry.groupNotification();
        GuiConfigSectionSpec declared = section(
                "fixture-notification", "fixture.notification.section",
                GuiConfigGroups.NOTIFICATION, group, 10, "fixture.notification.enabled");

        ConfigSection resolved = singleSection(group, List.of(declared));

        assertThat(resolved).isInstanceOf(DeclaredGuiConfigSection.class);
    }

    @Test
    @DisplayName("同一分组多个声明式 section 在单个容器中按 order、pluginId、sectionId 稳定排序")
    void declaredSectionsAreSortedWithinGroup() {
        String group = "Fixture Group";
        List<GuiConfigSectionSpec> sections = List.of(
                section("plugin-b", "section-b", "fixture.group", group, 20, "fixture.b"),
                section("plugin-b", "section-a", "fixture.group", group, 10, "fixture.a"),
                section("plugin-a", "section-z", "fixture.group", group, 10, "fixture.z"));
        RecordingContext ctx = new RecordingContext(List.of(
                field("fixture.a", group),
                field("fixture.b", group),
                field("fixture.z", group)));

        ConfigSection resolved = GuiConfigSectionResolver.createSections(
                        ctx,
                        List.of(group),
                        sections,
                        tempDir.resolve("config.yaml"),
                        path -> path)
                .get(0);

        assertThat(resolved).isInstanceOf(DeclaredGuiConfigSection.class);
        resolved.build();
        assertThat(ctx.registrationOrder())
                .containsExactly("fixture.z", "fixture.a", "fixture.b");
    }

    @Test
    @DisplayName("声明式 notice 渲染在 section 内容前并保留旧提示行样式")
    void sectionNoticeRendersBeforeContentWithLegacyHintStyle() {
        String group = "Fixture Group";
        RecordingContext ctx = new RecordingContext(List.of(field("fixture.enabled", group)));
        GuiConfigSectionSpec declared = section(
                "fixture",
                "fixture.notice",
                "fixture.group",
                group,
                10,
                List.of(new GuiConfigSectionNoticeSpec(
                        "fixture.top",
                        "已启用的服务会同时生效；下拉仅用于切换当前编辑的服务",
                        GuiConfigSectionNoticeStyle.HINT,
                        0)),
                "fixture.enabled");

        ConfigSection resolved = singleSection(ctx, group, List.of(declared));
        JComponent built = resolved.build();
        JPanel content = (JPanel) ((JScrollPane) built).getViewport().getView();
        Component first = content.getComponent(0);

        assertThat(first).isInstanceOf(JLabel.class);
        JLabel notice = (JLabel) first;
        assertThat(notice.getText()).isEqualTo("已启用的服务会同时生效；下拉仅用于切换当前编辑的服务");
        assertThat(notice.getForeground()).isEqualTo(new Color(0, 128, 96));
        assertThat(notice.getFont().getStyle()).isEqualTo(Font.PLAIN);
        assertThat(notice.getFont().getSize2D()).isEqualTo(11f);
        assertThat(notice.getClientProperty(DeclaredGuiConfigSection.NOTICE_ID_PROPERTY))
                .isEqualTo("fixture.top");
        assertThat(notice.getClientProperty(DeclaredGuiConfigSection.NOTICE_STYLE_PROPERTY))
                .isEqualTo(GuiConfigSectionNoticeStyle.HINT);
        assertThat(content.getComponentZOrder(notice))
                .isLessThan(content.getComponentZOrder(ctx.renderedField("fixture.enabled").panel()));
    }

    @Test
    @DisplayName("声明式 section 优先后不会额外注册过渡 adapter 字段")
    void declaredSectionDoesNotDuplicateTransitionAdapterFields() {
        String group = ConfigFieldRegistry.groupAi();
        ConfigFieldSpec aiBaseUrl = field("ai.base-url", group);
        GuiConfigSectionSpec declared = section(
                "fixture-ai", "fixture.ai.section", GuiConfigGroups.AI, group, 10, "ai.base-url");
        RecordingContext ctx = new RecordingContext(List.of(aiBaseUrl));
        ConfigSection resolved = GuiConfigSectionResolver.createSections(
                        ctx,
                        List.of(group),
                        List.of(declared),
                        tempDir.resolve("config.yaml"),
                        path -> path)
                .get(0);

        resolved.build();

        assertThat(ctx.registrationCount("ai.base-url")).isEqualTo(1);
    }

    @Test
    @DisplayName("插件市场分组保持专用 section，不被普通声明式 section 破坏")
    void pluginMarketSectionRemainsExclusive() {
        String group = ConfigFieldRegistry.groupPlugins();
        GuiConfigSectionSpec declared = section(
                "fixture-market", "fixture.market.section",
                GuiConfigGroups.PLUGINS, group, 10, "plugin-catalog.enabled");

        ConfigSection resolved = GuiConfigSectionResolver.createSections(
                        new RecordingContext(List.of()),
                        List.of(group),
                        List.of(declared),
                        tempDir.resolve("config.yaml"),
                        path -> path)
                .get(0);

        assertThat(resolved).isInstanceOf(PluginMarketConfigSection.class);
    }

    @Test
    @DisplayName("block 声明的消费键会阻止后续声明式 section 重复渲染")
    void blockConsumedKeysAreHonored() {
        String group = "Fixture Group";
        RecordingContext ctx = new RecordingContext(List.of(field("fixture.claimed", group)));
        ConfigSectionBlock claimingBlock = new ConfigSectionBlock() {
            @Override
            public String group() {
                return group;
            }

            @Override
            public String pluginId() {
                return "claimer";
            }

            @Override
            public String sectionId() {
                return "claimer.section";
            }

            @Override
            public int order() {
                return 0;
            }

            @Override
            public ConfigSection createSection(ConfigSectionContext ctx) {
                return new EmptyConfigSection(group);
            }

            @Override
            public Set<String> consumedFieldKeys(ConfigSectionContext ctx) {
                return Set.of("fixture.claimed");
            }
        };
        ConfigSectionBlock declaredBlock = new ConfigSectionBlock() {
            @Override
            public String group() {
                return group;
            }

            @Override
            public String pluginId() {
                return "fixture";
            }

            @Override
            public String sectionId() {
                return "fixture.section";
            }

            @Override
            public int order() {
                return 10;
            }

            @Override
            public ConfigSection createSection(ConfigSectionContext ctx) {
                return new DeclaredGuiConfigSection(ctx, group, List.of(section(
                        "fixture", "fixture.section", "fixture.group", group, 10, "fixture.claimed")));
            }
        };

        new CompositeConfigSection(ctx, group, List.of(claimingBlock, declaredBlock)).build();

        assertThat(ctx.registrationCount("fixture.claimed")).isZero();
    }

    @Test
    @DisplayName("单卡片声明式 section 仍保留迁移前的卡片切换控件")
    void singleCardSectionStillRendersCardSwitcher() {
        String group = "Fixture Group";
        RecordingContext ctx = new RecordingContext(List.of(field("fixture.enabled", group)));
        GuiConfigSectionSpec declared = cardSection(
                "fixture", "fixture.single-card", "fixture.group", group, 10,
                List.of(new GuiConfigFieldLayoutSpec("fixture.enabled", "only", "Only", 10)));

        ConfigSection resolved = singleSection(ctx, group, List.of(declared));
        JComponent built = resolved.build();
        JPanel content = (JPanel) ((JScrollPane) built).getViewport().getView();

        assertThat(countComponents(content, JComboBox.class)).isEqualTo(1);
        assertThat(ctx.registrationOrder()).containsExactly("fixture.enabled");
    }

    @Test
    @DisplayName("声明式字段 visibleWhen 隐藏时同步隐藏字段后间距")
    void declaredVisibleWhenHidesFieldRowSpacing() {
        String group = "Fixture Group";
        List<ConfigFieldSpec> fields = switchingFields(group);
        RecordingContext ctx = new RecordingContext(fields);
        GuiConfigSectionSpec declared = section(
                "fixture", "fixture.visible", "fixture.group", group, 10,
                "fixture.engine", "fixture.first", "fixture.second");

        ConfigSection resolved = singleSection(ctx, group, List.of(declared));
        JComponent built = resolved.build();
        JPanel content = (JPanel) ((JScrollPane) built).getViewport().getView();

        ctx.setFieldValue("fixture.engine", "second");
        ctx.updateEnabledStates();

        assertThat(ctx.renderedField("fixture.first").panel().isVisible()).isFalse();
        assertThat(ctx.renderedField("fixture.second").panel().isVisible()).isTrue();
        assertThat(visibleFieldRows(content))
                .containsExactly("fixture.engine", "fixture.second");
        assertThat(visibleFieldRowSpacingCount(content)).isEqualTo(2);
        assertThat(visibleDirectFixedStruts(content)).isZero();

        ctx.setFieldValue("fixture.engine", "first");
        ctx.updateEnabledStates();

        assertThat(ctx.renderedField("fixture.first").panel().isVisible()).isTrue();
        assertThat(ctx.renderedField("fixture.second").panel().isVisible()).isFalse();
        assertThat(visibleFieldRows(content))
                .containsExactly("fixture.engine", "fixture.first");
        assertThat(visibleFieldRowSpacingCount(content)).isEqualTo(2);
        assertThat(visibleDirectFixedStruts(content)).isZero();
    }

    @Test
    @DisplayName("普通分组字段 visibleWhen 隐藏时不保留独立间距")
    void plainGroupVisibleWhenHidesFieldRowSpacing() {
        String group = "Fixture Group";
        ConfigFieldSnapshot snapshot = new ConfigFieldSnapshot(
                List.of("Server Group", group), switchingFields(group), List.of(), List.of());
        ConfigPanel panel = new ConfigPanel(tempDir.resolve("config.yaml"), 6999,
                path -> path, snapshot);
        JPanel content = configTabContent(panel, group);

        panel.setFieldValue("fixture.engine", "second");
        panel.updateEnabledStates();

        assertThat(visibleFieldRows(content))
                .containsExactly("fixture.engine", "fixture.second");
        assertThat(visibleFieldRowSpacingCount(content)).isEqualTo(2);
        assertThat(visibleDirectFixedStruts(content)).isZero();

        panel.setFieldValue("fixture.engine", "first");
        panel.updateEnabledStates();

        assertThat(visibleFieldRows(content))
                .containsExactly("fixture.engine", "fixture.first");
        assertThat(visibleFieldRowSpacingCount(content)).isEqualTo(2);
        assertThat(visibleDirectFixedStruts(content)).isZero();
    }

    @Test
    @DisplayName("配置页一级页签区分宿主配置与插件设置")
    void configPanelSeparatesHostAndPluginSettings() {
        String group = GuiMessages.get("gui.config.group.server");
        ConfigFieldSpec hostField = field("host.plugins", group);
        ConfigFieldSpec pluginField = pluginField("plugin.plugins", group, "fixture");
        GuiConfigSectionSpec pluginSection = section(
                "fixture", "fixture.plugins", GuiConfigGroups.SERVER, group, 10, "plugin.plugins");
        ConfigFieldSnapshot snapshot = new ConfigFieldSnapshot(
                List.of(group),
                List.of(hostField, pluginField),
                List.of(pluginSection),
                List.of());

        ConfigPanel panel = new ConfigPanel(tempDir.resolve("config.yaml"), 6999,
                path -> path, snapshot);
        JTabbedPane topTabs = firstTabbedPane(panel);

        assertThat(tabTitles(topTabs)).containsExactly(
                group,
                GuiMessages.get("gui.config.scope.plugins"));
        assertThat(topTabs.getComponentAt(0)).isNotInstanceOf(JTabbedPane.class);
        assertThat(tabTitles((JTabbedPane) topTabs.getComponentAt(1))).containsExactly(group);

        JPanel hostContent = configTabContent(panel, group);
        JPanel pluginContent = configTabContent(topTabs.getComponentAt(1), group);
        assertThat(visibleFieldRows(hostContent)).contains("host.plugins");
        assertThat(visibleFieldRows(hostContent)).doesNotContain("plugin.plugins");
        assertThat(visibleFieldRows(pluginContent)).contains("plugin.plugins");
        assertThat(visibleFieldRows(pluginContent)).doesNotContain("host.plugins");
    }

    @Test
    @DisplayName("声明式单卡片 section 可按枚举字段恢复子卡片切换布局")
    void singleCardSectionRestoresNestedEnumSwitcherLayout() {
        String group = "Fixture Group";
        List<ConfigFieldSpec> fields = nestedSwitchingFields(group);
        RecordingContext ctx = new RecordingContext(fields);
        GuiConfigSectionSpec declared = cardSection(
                "fixture", "fixture.nested-card", "fixture.group", group, 10,
                List.of(
                        new GuiConfigFieldLayoutSpec("fixture.engine", "tts", "TTS", 10),
                        new GuiConfigFieldLayoutSpec("fixture.first", "tts", "TTS", 20),
                        new GuiConfigFieldLayoutSpec("fixture.second", "tts", "TTS", 30)));

        ConfigSection resolved = singleSection(ctx, group, List.of(declared));
        JComponent built = resolved.build();
        JPanel content = (JPanel) ((JScrollPane) built).getViewport().getView();

        assertThat(countComponents(content, JComboBox.class)).isEqualTo(2);
        assertThat(visibleFieldRowsDeep(content))
                .containsExactly("fixture.engine", "fixture.first");

        ctx.setFieldValue("fixture.engine", "second");
        ctx.updateEnabledStates();

        assertThat(visibleFieldRowsDeep(content))
                .containsExactly("fixture.engine", "fixture.second");
    }

    @Test
    @DisplayName("通知声明式 section 恢复旧版紧凑类型与服务卡片切换布局")
    void notificationSectionsRestoreLegacyCompactScenarioAndServiceCards() {
        String group = "Notification Group";
        RecordingContext ctx = new RecordingContext(List.of(
                boolField("push.enabled", group),
                boolField("notification.scenario.run-summary.enabled", group),
                boolField("notification.scenario.run-failed.enabled", group),
                boolField("mail.enabled", group),
                field("mail.host", group),
                boolField("push.bark.enabled", group),
                field("push.bark.server", group)));
        GuiConfigSectionSpec notice = new GuiConfigSectionSpec(
                "mail",
                "notification.service.notice",
                "notification",
                group,
                0,
                "",
                "",
                "",
                "",
                "",
                "",
                List.of(new GuiConfigSectionNoticeSpec(
                        "notification.service.concurrent",
                        "所有已启用的通知服务会同时生效",
                        GuiConfigSectionNoticeStyle.HINT,
                        0)),
                GuiConfigSectionLayout.FIELD_LIST,
                70,
                List.of(),
                List.of(),
                List.of(),
                true,
                false);
        GuiConfigSectionSpec master = section("push", "push.master", "notification", group, 80, "push.enabled");
        GuiConfigSectionSpec scenarios = new GuiConfigSectionSpec(
                "notification",
                "notification.scenarios",
                "notification",
                group,
                0,
                "",
                "",
                "需要通知的类型",
                "取消勾选某类型后，该类型的通知都不再发送",
                "",
                "",
                List.of(),
                GuiConfigSectionLayout.COMPACT_GRID,
                100,
                List.of(
                        new GuiConfigFieldLayoutSpec("notification.scenario.run-summary.enabled", null, "", 10),
                        new GuiConfigFieldLayoutSpec("notification.scenario.run-failed.enabled", null, "", 20)),
                List.of(),
                List.of(),
                false,
                false);
        GuiConfigSectionSpec services = cardSection(
                "mail", "notification.services", "notification", group, 200,
                List.of(
                        new GuiConfigFieldLayoutSpec("mail.enabled", "mail", "邮件 / SMTP", 10),
                        new GuiConfigFieldLayoutSpec("mail.host", "mail", "邮件 / SMTP", 20),
                        new GuiConfigFieldLayoutSpec("push.bark.enabled", "bark", "Bark", 30),
                        new GuiConfigFieldLayoutSpec("push.bark.server", "bark", "Bark", 40)));

        ConfigSection resolved = singleSection(ctx, group, List.of(
                services,
                scenarios,
                master,
                notice));
        JComponent built = resolved.build();
        JPanel content = (JPanel) ((JScrollPane) built).getViewport().getView();
        List<JComboBox> combos = components(content, JComboBox.class);

        assertThat(visibleFieldRowsDeep(content))
                .containsExactly("push.enabled", "mail.enabled", "mail.host");
        assertThat(components(content, JCheckBox.class).stream()
                .map(JCheckBox::getText)
                .filter(text -> text != null && text.startsWith("notification.scenario."))
                .toList())
                .containsExactly(
                        "notification.scenario.run-summary.enabled",
                        "notification.scenario.run-failed.enabled");
        assertThat(combos).hasSize(1);
        assertThat(componentIndex(content, combos.get(0)))
                .isGreaterThan(componentIndex(content, ctx.renderedField("push.enabled").panel()))
                .isLessThan(componentIndex(content, ctx.renderedField("mail.enabled").panel()));

        combos.get(0).setSelectedItem("bark");

        assertThat(visibleFieldRowsDeep(content))
                .containsExactly("push.enabled", "push.bark.enabled", "push.bark.server");
    }

    @Test
    @DisplayName("被声明式 section 跨分组消费的插件字段不再生成平铺配置页")
    void claimedPluginFieldsDoNotCreatePlainGroupTabs() {
        String aiGroup = "AI Group";
        String ttsGroup = "TTS Group";
        ConfigFieldSpec ttsField = pluginField("fixture.tts.engine", ttsGroup, "fixture");
        GuiConfigSectionSpec aiSection = cardSection(
                "fixture", "fixture.ai", "fixture.ai.group", aiGroup, 10,
                List.of(new GuiConfigFieldLayoutSpec("fixture.tts.engine", "tts", "TTS", 10)));
        ConfigFieldSnapshot snapshot = new ConfigFieldSnapshot(
                List.of(aiGroup, ttsGroup),
                List.of(ttsField),
                List.of(aiSection),
                List.of());

        ConfigPanel panel = new ConfigPanel(tempDir.resolve("config.yaml"), 6999,
                path -> path, snapshot);
        JTabbedPane topTabs = firstTabbedPane(panel);
        JTabbedPane pluginTabs = (JTabbedPane) topTabs.getComponentAt(0);

        assertThat(tabTitles(topTabs)).containsExactly(GuiMessages.get("gui.config.scope.plugins"));
        assertThat(tabTitles(pluginTabs)).containsExactly(aiGroup);
        assertThat(visibleFieldRowsDeep(pluginTabs)).containsExactly("fixture.tts.engine");
    }

    @Test
    @DisplayName("声明式 preset 可只锁定贡献方声明的字段")
    void presetLocksDeclaredKeysOnly() {
        String group = "Fixture Group";
        RecordingContext ctx = new RecordingContext(List.of(
                field("fixture.endpoint", group),
                field("fixture.model", group)));
        GuiConfigSectionSpec declared = presetSection(group);

        ConfigSection resolved = singleSection(ctx, group, List.of(declared));
        resolved.build();
        ctx.setFieldValue("fixture.endpoint", "https://api.example.test/");
        resolved.onValuesLoaded();
        resolved.afterEnabledStates();

        assertThat(ctx.lockedFields()).containsExactly("fixture.endpoint");
    }

    private ConfigSection singleSection(String group, List<GuiConfigSectionSpec> declaredSections) {
        return singleSection(new RecordingContext(List.of()), group, declaredSections);
    }

    private ConfigSection singleSection(RecordingContext ctx, String group,
                                        List<GuiConfigSectionSpec> declaredSections) {
        return GuiConfigSectionResolver.createSections(
                        ctx,
                        List.of(group),
                        declaredSections,
                        tempDir.resolve("config.yaml"),
                        path -> path)
                .get(0);
    }

    private static ConfigSection section(List<ConfigSection> sections, String group) {
        return sections.stream()
                .filter(section -> group.equals(section.group()))
                .findFirst()
                .orElseThrow();
    }

    private static GuiConfigSectionSpec section(String pluginId, String sectionId, String groupId,
                                                String group, int order, String... fieldKeys) {
        return section(pluginId, sectionId, groupId, group, order, List.of(), fieldKeys);
    }

    private static GuiConfigSectionSpec section(String pluginId, String sectionId, String groupId,
                                                String group, int order,
                                                List<GuiConfigSectionNoticeSpec> notices,
                                                String... fieldKeys) {
        List<GuiConfigFieldLayoutSpec> layouts = java.util.Arrays.stream(fieldKeys)
                .map(key -> new GuiConfigFieldLayoutSpec(key, null, "", 10))
                .toList();
        return new GuiConfigSectionSpec(
                pluginId,
                sectionId,
                groupId,
                group,
                0,
                "",
                "",
                "",
                "",
                "",
                "",
                notices,
                GuiConfigSectionLayout.FIELD_LIST,
                order,
                layouts,
                List.of(),
                List.of(),
                false,
                true);
    }

    private static GuiConfigSectionSpec cardSection(String pluginId, String sectionId, String groupId,
                                                    String group, int order,
                                                    List<GuiConfigFieldLayoutSpec> layouts) {
        return new GuiConfigSectionSpec(
                pluginId,
                sectionId,
                groupId,
                group,
                0,
                "",
                "",
                "Card",
                "Choose card",
                "",
                "",
                List.of(),
                GuiConfigSectionLayout.CARD_SWITCHER,
                order,
                layouts,
                List.of(),
                List.of(),
                false,
                true);
    }

    private static GuiConfigSectionSpec presetSection(String group) {
        return new GuiConfigSectionSpec(
                "fixture",
                "fixture.preset",
                "fixture.group",
                group,
                0,
                "",
                "",
                "",
                "",
                "Preset",
                "",
                List.of(),
                GuiConfigSectionLayout.FIELD_LIST,
                10,
                List.of(
                        new GuiConfigFieldLayoutSpec("fixture.endpoint", null, "", 10),
                        new GuiConfigFieldLayoutSpec("fixture.model", null, "", 20)),
                List.of(),
                List.of(new GuiConfigPresetSpec(
                        "default",
                        "Default",
                        "",
                        null,
                        0,
                        "fixture.endpoint",
                        "https://api.example.test",
                        Map.of(
                                "fixture.endpoint", "https://api.example.test",
                                "fixture.model", "fixture-model"),
                        List.of("fixture.endpoint"),
                        GuiConfigPresetMatchMode.TRIMMED_TRAILING_SLASH_IGNORE_CASE)),
                false,
                true);
    }

    private static ConfigFieldSpec field(String key, String group) {
        return ConfigFieldSpec.builder(key, key, FieldType.STRING, group)
                .defaultValue("")
                .build();
    }

    private static ConfigFieldSpec boolField(String key, String group) {
        return ConfigFieldSpec.builder(key, key, FieldType.BOOL, group)
                .defaultValue("true")
                .build();
    }

    private static ConfigFieldSpec pluginField(String key, String group, String pluginId) {
        return ConfigFieldSpec.builder(key, key, FieldType.STRING, group)
                .ownerPluginId(pluginId)
                .defaultValue("")
                .build();
    }

    private static List<ConfigFieldSpec> switchingFields(String group) {
        ConfigFieldSpec engine = ConfigFieldSpec.builder("fixture.engine", "Engine", FieldType.ENUM, group)
                .defaultValue("first")
                .enumValues("first", "second")
                .build();
        ConfigFieldSpec first = ConfigFieldSpec.builder("fixture.first", "First", FieldType.STRING, group)
                .visibleWhen(snap -> snap.equals("fixture.engine", "first"))
                .build();
        ConfigFieldSpec second = ConfigFieldSpec.builder("fixture.second", "Second", FieldType.STRING, group)
                .visibleWhen(snap -> snap.equals("fixture.engine", "second"))
                .build();
        return List.of(engine, first, second);
    }

    private static List<ConfigFieldSpec> nestedSwitchingFields(String group) {
        ConfigFieldSpec engine = ConfigFieldSpec.builder("fixture.engine", "Engine", FieldType.ENUM, group)
                .defaultValue("first")
                .enumValues("first", "second")
                .build();
        ConfigFieldSpec first = ConfigFieldSpec.builder("fixture.first", "First", FieldType.STRING, group)
                .visibleWhen(snap -> snap.equals("fixture.engine", "first"))
                .visibleWhenConditions(List.of(GuiConfigCondition.equalsTo("fixture.engine", "first")))
                .build();
        ConfigFieldSpec second = ConfigFieldSpec.builder("fixture.second", "Second", FieldType.STRING, group)
                .visibleWhen(snap -> snap.equals("fixture.engine", "second"))
                .visibleWhenConditions(List.of(GuiConfigCondition.equalsTo("fixture.engine", "second")))
                .build();
        return List.of(engine, first, second);
    }

    private static JPanel configTabContent(ConfigPanel panel, String group) {
        return configTabContent((Component) panel, group);
    }

    private static JPanel configTabContent(Component root, String group) {
        if (root instanceof JTabbedPane tabs) {
            for (int i = 0; i < tabs.getTabCount(); i++) {
                Component tab = tabs.getComponentAt(i);
                if (group.equals(tabs.getTitleAt(i))
                        && tab instanceof JScrollPane scrollPane
                        && scrollPane.getViewport().getView() instanceof JPanel content) {
                    return content;
                }
                try {
                    return configTabContent(tab, group);
                } catch (AssertionError ignored) {
                    // Continue searching sibling tabs.
                }
            }
        }
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                try {
                    return configTabContent(child, group);
                } catch (AssertionError ignored) {
                    // Continue searching sibling components.
                }
            }
        }
        throw new AssertionError("ConfigPanel tab content not found: " + group);
    }

    private static JTabbedPane firstTabbedPane(Container container) {
        for (Component component : container.getComponents()) {
            if (component instanceof JTabbedPane tabs) {
                return tabs;
            }
        }
        throw new AssertionError("Tabbed pane not found");
    }

    private static List<String> tabTitles(JTabbedPane tabs) {
        List<String> titles = new ArrayList<>();
        for (int i = 0; i < tabs.getTabCount(); i++) {
            titles.add(tabs.getTitleAt(i));
        }
        return titles;
    }

    private static List<String> visibleFieldRows(JPanel content) {
        return Arrays.stream(content.getComponents())
                .filter(Component::isVisible)
                .filter(JComponent.class::isInstance)
                .map(JComponent.class::cast)
                .map(component -> component.getClientProperty(ConfigFieldRows.FIELD_KEY_PROPERTY))
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .toList();
    }

    private static List<String> visibleFieldRowsDeep(Component root) {
        List<String> keys = new ArrayList<>();
        collectVisibleFieldRows(root, keys);
        return keys;
    }

    private static void collectVisibleFieldRows(Component component, List<String> keys) {
        if (!component.isVisible()) {
            return;
        }
        if (component instanceof JComponent jComponent) {
            Object key = jComponent.getClientProperty(ConfigFieldRows.FIELD_KEY_PROPERTY);
            if (key instanceof String fieldKey) {
                keys.add(fieldKey);
            }
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                collectVisibleFieldRows(child, keys);
            }
        }
    }

    private static int visibleFieldRowSpacingCount(JPanel content) {
        int count = 0;
        for (Component component : content.getComponents()) {
            if (!component.isVisible()
                    || !(component instanceof JComponent row)
                    || row.getClientProperty(ConfigFieldRows.FIELD_KEY_PROPERTY) == null) {
                continue;
            }
            Container container = row;
            for (Component child : container.getComponents()) {
                if (child.isVisible()
                        && child instanceof JComponent childComponent
                        && Boolean.TRUE.equals(childComponent.getClientProperty(
                        ConfigFieldRows.TRAILING_SPACING_PROPERTY))) {
                    count++;
                }
            }
        }
        return count;
    }

    private static int visibleDirectFixedStruts(JPanel content) {
        int count = 0;
        for (Component component : content.getComponents()) {
            if (component.isVisible() && isFixedVerticalStrut(component)) {
                count++;
            }
        }
        return count;
    }

    private static boolean isFixedVerticalStrut(Component component) {
        return component instanceof Box.Filler filler
                && filler.getPreferredSize().height == 2
                && filler.getMaximumSize().height == 2;
    }

    private static int countComponents(Component root, Class<?> type) {
        int count = type.isInstance(root) ? 1 : 0;
        if (root instanceof java.awt.Container container) {
            for (Component child : container.getComponents()) {
                count += countComponents(child, type);
            }
        }
        return count;
    }

    private static <T extends Component> List<T> components(Component root, Class<T> type) {
        List<T> found = new ArrayList<>();
        collectComponents(root, type, found);
        return found;
    }

    private static <T extends Component> void collectComponents(Component root, Class<T> type, List<T> found) {
        if (type.isInstance(root)) {
            found.add(type.cast(root));
        }
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                collectComponents(child, type, found);
            }
        }
    }

    private static int componentIndex(Container root, Component target) {
        int[] index = {0};
        int found = componentIndex(root, target, index);
        if (found < 0) {
            throw new AssertionError("Component not found: " + target);
        }
        return found;
    }

    private static int componentIndex(Component current, Component target, int[] index) {
        if (current == target) {
            return index[0];
        }
        index[0]++;
        if (current instanceof Container container) {
            for (Component child : container.getComponents()) {
                int found = componentIndex(child, target, index);
                if (found >= 0) {
                    return found;
                }
            }
        }
        return -1;
    }

    private static final class EmptyConfigSection implements ConfigSection {
        private final String group;

        private EmptyConfigSection(String group) {
            this.group = group;
        }

        @Override
        public String group() {
            return group;
        }

        @Override
        public JComponent build() {
            return new JPanel();
        }
    }

    private static final class RecordingContext implements ConfigSectionContext {
        private final List<ConfigFieldSpec> fields;
        private final Map<String, FieldRenderer.RenderedField> renderedFields = new LinkedHashMap<>();
        private final Map<String, Integer> registrations = new LinkedHashMap<>();
        private final List<String> registrationOrder = new ArrayList<>();
        private final List<String> lockedFields = new ArrayList<>();

        private RecordingContext(List<ConfigFieldSpec> fields) {
            this.fields = fields == null ? List.of() : List.copyOf(fields);
        }

        @Override
        public List<ConfigFieldSpec> allFields() {
            return fields;
        }

        @Override
        public ConfigFieldSpec findSpec(String key) {
            return fields.stream()
                    .filter(field -> key.equals(field.key()))
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public void registerField(ConfigFieldSpec spec, FieldRenderer.RenderedField rf) {
            registrations.merge(spec.key(), 1, Integer::sum);
            renderedFields.put(spec.key(), rf);
            registrationOrder.add(spec.key());
        }

        @Override
        public void addFields(JPanel content, List<ConfigFieldSpec> specs) {
            for (ConfigFieldSpec spec : specs) {
                FieldRenderer.RenderedField rf = ConfigFieldRows.render(spec);
                registerField(spec, rf);
                content.add(rf.panel());
            }
        }

        @Override
        public String currentFieldValue(String key) {
            FieldRenderer.RenderedField rf = renderedFields.get(key);
            return rf == null ? "" : rf.getValue().get();
        }

        @Override
        public void setFieldValue(String key, String value) {
            FieldRenderer.RenderedField rf = renderedFields.get(key);
            if (rf != null) {
                rf.setValue().accept(value);
            }
        }

        @Override
        public void lockField(String key) {
            lockedFields.add(key);
        }

        @Override
        public JPanel newContentPanel() {
            JPanel panel = new JPanel();
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
            return panel;
        }

        @Override
        public void resetScrollToTopOnFirstShow(JScrollPane sp) {
        }

        @Override
        public JLabel effectLabel(boolean requiresRestart) {
            return new JLabel();
        }

        @Override
        public JTextArea hiddenValidationError() {
            return new JTextArea();
        }

        @Override
        public void showNotice(String msg) {
        }

        @Override
        public void updateEnabledStates() {
            Map<String, String> values = new LinkedHashMap<>();
            renderedFields.forEach((key, renderedField) ->
                    values.put(key, renderedField.getValue().get()));
            ConfigSnapshot snapshot = new ConfigSnapshot(values);
            for (ConfigFieldSpec field : fields) {
                FieldRenderer.RenderedField renderedField = renderedFields.get(field.key());
                if (renderedField != null) {
                    renderedField.panel().setVisible(field.visibleWhen().test(snapshot));
                }
            }
        }

        @Override
        public GuiConfigTestClient testClient() {
            return new GuiConfigTestClient(0);
        }

        private int registrationCount(String key) {
            return registrations.getOrDefault(key, 0);
        }

        private List<String> registrationOrder() {
            return List.copyOf(registrationOrder);
        }

        private FieldRenderer.RenderedField renderedField(String key) {
            return renderedFields.get(key);
        }

        private List<String> lockedFields() {
            return List.copyOf(lockedFields);
        }
    }
}

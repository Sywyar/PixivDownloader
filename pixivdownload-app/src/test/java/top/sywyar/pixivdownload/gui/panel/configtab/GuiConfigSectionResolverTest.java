package top.sywyar.pixivdownload.gui.panel.configtab;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.gui.config.ConfigFieldRegistry;
import top.sywyar.pixivdownload.gui.config.ConfigFieldSpec;
import top.sywyar.pixivdownload.gui.config.FieldRenderer;
import top.sywyar.pixivdownload.gui.config.FieldType;
import top.sywyar.pixivdownload.gui.config.GuiConfigFieldLayoutSpec;
import top.sywyar.pixivdownload.gui.config.GuiConfigSectionNoticeSpec;
import top.sywyar.pixivdownload.gui.config.GuiConfigSectionSpec;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigGroups;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigSectionLayout;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigSectionNoticeStyle;

import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.nio.file.Path;
import java.util.ArrayList;
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
    @DisplayName("没有声明式 section 时只为未迁移分组保留过渡 adapter")
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

        assertThat(sections).hasSize(2);
        assertThat(section(sections, ConfigFieldRegistry.groupAi())).isInstanceOf(AiConfigSection.class);
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

    private static ConfigFieldSpec field(String key, String group) {
        return ConfigFieldSpec.builder(key, key, FieldType.STRING, group)
                .defaultValue("")
                .build();
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
                FieldRenderer.RenderedField rf = FieldRenderer.render(spec);
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
    }
}

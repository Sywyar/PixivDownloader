package top.sywyar.pixivdownload.notificationbase;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.notification.NotificationConfigKeys;
import top.sywyar.pixivdownload.notification.NotificationScenario;
import top.sywyar.pixivdownload.notification.NotificationSink;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldLayoutContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldType;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigGroups;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigSectionContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigSectionLayout;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("notification 基础插件")
class NotificationPluginTest {

    private final NotificationPlugin plugin = new NotificationPlugin();

    @Test
    @DisplayName("只贡献 notification.scenario.* GUI 字段，默认启用且可热重载")
    void contributesScenarioFieldsWithDefaultEnabled() {
        List<GuiConfigFieldContribution> fields = plugin.guiConfigContributions().stream()
                .flatMap(contribution -> contribution.fields().stream())
                .toList();
        Set<String> expectedKeys = java.util.Arrays.stream(NotificationScenario.values())
                .map(NotificationScenario::id)
                .map(NotificationConfigKeys::scenarioEnabledKey)
                .collect(Collectors.toSet());

        assertThat(fields).hasSize(NotificationScenario.values().length);
        assertThat(fields).extracting(GuiConfigFieldContribution::key)
                .containsExactlyInAnyOrderElementsOf(expectedKeys);
        assertThat(fields).allSatisfy(field -> {
            assertThat(field.groupId()).isEqualTo(GuiConfigGroups.NOTIFICATION);
            assertThat(field.i18nNamespace()).isEqualTo(NotificationPlugin.ID);
            assertThat(field.type()).isEqualTo(GuiConfigFieldType.BOOL);
            assertThat(field.defaultValue()).isEqualTo("true");
            assertThat(field.requiresRestart()).isFalse();
            assertThat(field.contributesGroupVisibility()).isFalse();
        });
    }

    @Test
    @DisplayName("贡献中性的通知场景紧凑网格 section")
    void contributesNeutralScenarioSection() {
        List<GuiConfigSectionContribution> sections = plugin.guiConfigContributions().stream()
                .flatMap(contribution -> contribution.sections().stream())
                .toList();
        Set<String> expectedKeys = java.util.Arrays.stream(NotificationScenario.values())
                .map(NotificationScenario::id)
                .map(NotificationConfigKeys::scenarioEnabledKey)
                .collect(Collectors.toSet());

        assertThat(sections).singleElement().satisfies(section -> {
            assertThat(section.sectionId()).isEqualTo("notification.scenarios");
            assertThat(section.groupId()).isEqualTo(GuiConfigGroups.NOTIFICATION);
            assertThat(section.i18nNamespace()).isEqualTo(NotificationPlugin.ID);
            assertThat(section.layoutLabelKey()).isEqualTo("gui.config.notification.scenario.section.label");
            assertThat(section.layoutHelpKey()).isEqualTo("gui.config.notification.scenario.section.help");
            assertThat(section.layout()).isEqualTo(GuiConfigSectionLayout.COMPACT_GRID);
            assertThat(section.mergeable()).isFalse();
            assertThat(section.contributesGroupVisibility()).isFalse();
            assertThat(section.notices()).isEmpty();
            assertThat(section.actions()).isEmpty();
            assertThat(section.presets()).isEmpty();
            assertThat(section.fieldLayouts()).extracting(GuiConfigFieldLayoutContribution::fieldKey)
                    .containsExactlyInAnyOrderElementsOf(expectedKeys);
            assertThat(section.fieldLayouts()).allSatisfy(layout -> {
                assertThat(layout.cardId()).isNull();
                assertThat(layout.i18nNamespace()).isNull();
            });
        });
    }

    @Test
    @DisplayName("基础插件不注册任何发送 Sink 或 Spring 配置")
    void doesNotRegisterNotificationSink() {
        NotificationPf4jPlugin pf4j = new NotificationPf4jPlugin();
        List<Class<?>> pluginClasses = List.of(NotificationPlugin.class, NotificationPf4jPlugin.class);

        assertThat(plugin.routes()).isEmpty();
        assertThat(plugin.staticResources()).isEmpty();
        assertThat(plugin.navigation()).isEmpty();
        assertThat(pf4j.configurationClasses()).isEmpty();
        assertThat(pluginClasses).noneMatch(NotificationSink.class::isAssignableFrom);
    }

    @Test
    @DisplayName("插件 id 与展示命名空间为 notification")
    void usesNeutralNotificationIdentity() {
        assertThat(plugin.id()).isEqualTo(NotificationPlugin.ID);
        assertThat(plugin.id()).isEqualTo(NotificationConfigKeys.OWNER_PLUGIN_ID);
        assertThat(plugin.id()).isEqualTo("notification");
        assertThat(plugin.kind()).isEqualTo(PluginKind.FEATURE);
        assertThat(plugin.i18n()).singleElement().satisfies(i18n -> {
            assertThat(i18n.namespace()).isEqualTo("notification");
            assertThat(i18n.baseName()).isEqualTo("i18n.web.notification");
        });
    }
}

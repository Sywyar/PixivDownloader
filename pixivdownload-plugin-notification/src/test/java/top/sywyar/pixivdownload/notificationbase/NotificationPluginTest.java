package top.sywyar.pixivdownload.notificationbase;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.notification.NotificationConfigKeys;
import top.sywyar.pixivdownload.notification.NotificationScenario;
import top.sywyar.pixivdownload.notification.NotificationSink;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigActionContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldLayoutContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldType;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigGroups;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigSectionContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigSectionLayout;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("notification 基础插件")
class NotificationPluginTest {

    private final NotificationPlugin plugin = new NotificationPlugin();

    @Test
    @DisplayName("贡献 notification.scenario.* GUI 字段，默认启用且可热重载")
    void contributesScenarioFieldsWithDefaultEnabled() {
        List<GuiConfigFieldContribution> fields = plugin.guiConfigContributions().stream()
                .flatMap(contribution -> contribution.fields().stream())
                .filter(field -> field.key().startsWith(NotificationConfigKeys.SCENARIO_PREFIX))
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
                .filter(section -> section.sectionId().equals("notification.scenarios"))
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
    @DisplayName("站内信作为默认启用的通知服务卡片")
    void contributesInboxServiceCard() {
        List<GuiConfigFieldContribution> fields = plugin.guiConfigContributions().stream()
                .flatMap(contribution -> contribution.fields().stream())
                .toList();
        List<GuiConfigSectionContribution> sections = plugin.guiConfigContributions().stream()
                .flatMap(contribution -> contribution.sections().stream())
                .toList();

        assertThat(fields).filteredOn(field -> field.key().equals(NotificationPlugin.INBOX_ENABLED_KEY))
                .singleElement()
                .satisfies(field -> {
                    assertThat(field.groupId()).isEqualTo(GuiConfigGroups.NOTIFICATION);
                    assertThat(field.type()).isEqualTo(GuiConfigFieldType.BOOL);
                    assertThat(field.defaultValue()).isEqualTo("true");
                    assertThat(field.requiresRestart()).isFalse();
                    assertThat(field.contributesGroupVisibility()).isTrue();
                });
        assertThat(fields).filteredOn(field -> field.key().equals(NotificationPlugin.INBOX_MAX_MESSAGES_KEY))
                .singleElement()
                .satisfies(field -> {
                    assertThat(field.type()).isEqualTo(GuiConfigFieldType.INT);
                    assertThat(field.defaultValue()).isEqualTo("500");
                    assertThat(field.minValue()).isEqualTo(1);
                    assertThat(field.requiresRestart()).isFalse();
                });
        assertThat(fields).filteredOn(field -> field.key().equals(NotificationPlugin.INBOX_RETENTION_DAYS_KEY))
                .singleElement()
                .satisfies(field -> {
                    assertThat(field.type()).isEqualTo(GuiConfigFieldType.INT);
                    assertThat(field.defaultValue()).isEqualTo("90");
                    assertThat(field.minValue()).isEqualTo(1);
                    assertThat(field.requiresRestart()).isFalse();
                });
        assertThat(sections).filteredOn(section -> section.sectionId().equals("notification.services"))
                .singleElement()
                .satisfies(section -> {
                    assertThat(section.layout()).isEqualTo(GuiConfigSectionLayout.CARD_SWITCHER);
                    assertThat(section.mergeable()).isTrue();
                    assertThat(section.contributesGroupVisibility()).isTrue();
                    assertThat(section.fieldLayouts()).hasSize(3).allSatisfy(layout -> {
                        assertThat(layout.cardId()).isEqualTo("inbox");
                        assertThat(layout.cardLabelKey()).isEqualTo("gui.config.notification.service.inbox");
                        assertThat(layout.i18nNamespace()).isEqualTo(NotificationPlugin.ID);
                    });
                    assertThat(section.fieldLayouts()).extracting(GuiConfigFieldLayoutContribution::fieldKey)
                            .containsExactly(NotificationPlugin.INBOX_ENABLED_KEY,
                                    NotificationPlugin.INBOX_MAX_MESSAGES_KEY,
                                    NotificationPlugin.INBOX_RETENTION_DAYS_KEY);
                    assertThat(section.actions()).hasSize(2)
                            .extracting(GuiConfigActionContribution::actionId)
                            .containsExactly("notification.inbox.test", "notification.inbox.test-all");
                    assertThat(section.actions()).allSatisfy(action -> {
                        assertThat(action.cardId()).isEqualTo("inbox");
                        assertThat(action.payloadFields()).isEmpty();
                    });
                    assertThat(section.actions()).extracting(GuiConfigActionContribution::endpoint)
                            .containsExactly("notification-inbox-test", "notification-inbox-test-all");
                });
    }

    @Test
    @DisplayName("站内信页面、API 与下载页入口均为插件自有且仅管理员可达")
    void contributesAdministratorInbox() {
        NotificationPf4jPlugin pf4j = new NotificationPf4jPlugin();

        assertThat(plugin.routes()).extracting(route -> route.pathPattern())
                .containsExactly(
                        "/pixiv-notifications.html",
                        "/pixiv-notifications/**",
                        "/api/notifications",
                        "/api/notifications/**",
                        "/api/gui/notification-inbox-test",
                        "/api/gui/notification-inbox-test-all");
        assertThat(plugin.routes()).filteredOn(route -> route.pathPattern().startsWith("/api/gui/"))
                .allSatisfy(route -> assertThat(route.accessPolicy()).isEqualTo(AccessPolicy.GUI));
        assertThat(plugin.routes()).filteredOn(route -> !route.pathPattern().startsWith("/api/gui/"))
                .allSatisfy(route -> assertThat(route.accessPolicy()).isEqualTo(AccessPolicy.ADMIN));
        assertThat(plugin.staticResources()).hasSize(2);
        assertThat(plugin.uiSlots()).singleElement().satisfies(slot -> {
            assertThat(slot.target()).isEqualTo("topbar-actions");
            assertThat(slot.moduleUrl()).isEqualTo("/pixiv-notifications/batch-inbox-slot.js");
        });
        assertThat(plugin.schema()).singleElement().satisfies(schema -> {
            assertThat(schema.tables()).hasSize(2);
            assertThat(schema.tables()).filteredOn(table -> table.name().equals("notification_messages"))
                    .singleElement().satisfies(table -> {
                    assertThat(table.name()).isEqualTo("notification_messages");
                    assertThat(table.columns()).extracting(column -> column.name())
                            .containsExactly("id", "category", "severity", "scenario_id", "title", "body",
                                    "content_url", "content_html", "action_url", "created_time", "read_time",
                                    "deleted_time", "active");
                    assertThat(table.indexes()).extracting(index -> index.name())
                            .containsExactly("idx_notification_messages_created_time",
                                    "idx_notification_messages_unread_created");
                    assertThat(table.checkExpression())
                            .contains("category IN ('download','announcement','survey','system')")
                            .contains("severity IN ('INFO','WARNING','ERROR')")
                            .contains("content_html IS NULL OR length(content_html) > 0")
                            .contains("active IN (0,1)")
                            .contains("deleted_time IS NULL OR deleted_time >= created_time");
                    });
            assertThat(schema.tables())
                    .filteredOn(table -> table.name().equals("notification_announcement_translations"))
                    .singleElement().satisfies(table -> {
                        assertThat(table.columns()).extracting(column -> column.name())
                                .containsExactly("announcement_id", "locale", "title", "summary",
                                        "content_url", "content_html");
                        assertThat(table.columns()).extracting(column -> column.primaryKeyPosition())
                                .containsExactly(1, 2, 0, 0, 0, 0);
                        assertThat(table.indexes()).isEmpty();
                    });
        });
        assertThat(plugin.navigation()).isEmpty();
        assertThat(pf4j.configurationClasses()).containsExactly(NotificationPluginConfiguration.class);
        assertThat(NotificationSink.class).isAssignableFrom(InboxNotificationSink.class);
    }

    @Test
    @DisplayName("插件 id 与展示命名空间为 notification")
    void usesNeutralNotificationIdentity() {
        assertThat(plugin.id()).isEqualTo(NotificationPlugin.ID);
        assertThat(plugin.id()).isEqualTo("notification");
        assertThat(plugin.kind()).isEqualTo(PluginKind.FEATURE);
        assertThat(plugin.i18n()).singleElement().satisfies(i18n -> {
            assertThat(i18n.namespace()).isEqualTo("notification");
            assertThat(i18n.baseName()).isEqualTo("i18n.web.notification");
        });
    }
}

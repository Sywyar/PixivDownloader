package top.sywyar.pixivdownload.notificationbase;

import top.sywyar.pixivdownload.notification.NotificationConfigKeys;
import top.sywyar.pixivdownload.notification.NotificationScenario;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigActionContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigActionResultArgument;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigActionResultCondition;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigActionResultRule;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigActionResultSummary;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldLayoutContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldType;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigGroups;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigSectionContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigSectionLayout;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.schema.SchemaContribution;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;
import top.sywyar.pixivdownload.plugin.api.web.StaticResourceContribution;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;
import top.sywyar.pixivdownload.plugin.api.web.WebUiSlotContribution;

import java.util.Arrays;
import java.util.List;

/**
 * 通知基础插件：拥有 {@code notification.scenario.*} 配置与管理员站内信介质。
 * 邮件和推送仍由各自插件提供，业务场景模板仍由场景所有者贡献。
 */
public class NotificationPlugin implements PixivFeaturePlugin {

    public static final String ID = "notification";
    static final String INBOX_ENABLED_KEY = "notification.inbox.enabled";
    static final String INBOX_MAX_MESSAGES_KEY = "notification.inbox.max-messages";
    static final String INBOX_RETENTION_DAYS_KEY = "notification.inbox.retention-days";
    static final int DEFAULT_INBOX_MAX_MESSAGES = 500;
    static final int DEFAULT_INBOX_RETENTION_DAYS = 90;

    private static final String NOTIFICATION_SERVICES_SECTION = "notification.services";
    private static final String INBOX_CARD_ID = "inbox";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "plugin.name";
    }

    @Override
    public String description() {
        return "plugin.summary";
    }

    @Override
    public String iconKey() {
        return "bell";
    }

    @Override
    public String colorToken() {
        return "teal";
    }

    @Override
    public PluginKind kind() {
        return PluginKind.FEATURE;
    }

    @Override
    public List<SchemaContribution> schema() {
        return List.of(NotificationInboxSchema.CONTRIBUTION);
    }

    @Override
    public List<WebRouteContribution> routes() {
        return List.of(
                WebRouteContribution.admin("/pixiv-notifications.html"),
                WebRouteContribution.admin("/pixiv-notifications/**"),
                WebRouteContribution.admin("/api/notifications"),
                WebRouteContribution.admin("/api/notifications/**"),
                WebRouteContribution.gui("/api/gui/notification-inbox-test"),
                WebRouteContribution.gui("/api/gui/notification-inbox-test-all"));
    }

    @Override
    public List<StaticResourceContribution> staticResources() {
        return List.of(
                new StaticResourceContribution("classpath:/static/", "/pixiv-notifications.html", true),
                new StaticResourceContribution("classpath:/static/pixiv-notifications/", "/pixiv-notifications/"));
    }

    @Override
    public List<WebUiSlotContribution> uiSlots() {
        return List.of(new WebUiSlotContribution(
                ID + ".batch-topbar", "topbar-actions",
                "/pixiv-notifications/batch-inbox-slot.js", 10));
    }

    @Override
    public List<I18nContribution> i18n() {
        return List.of(new I18nContribution(ID, "i18n.web.notification", 7));
    }

    @Override
    public List<GuiConfigContribution> guiConfigContributions() {
        List<GuiConfigFieldContribution> fields = new java.util.ArrayList<>();
        fields.add(inboxField());
        fields.add(inboxRetentionField(
                INBOX_MAX_MESSAGES_KEY, DEFAULT_INBOX_MAX_MESSAGES, 100));
        fields.add(inboxRetentionField(
                INBOX_RETENTION_DAYS_KEY, DEFAULT_INBOX_RETENTION_DAYS, 110));
        fields.addAll(Arrays.stream(NotificationScenario.values())
                .map(NotificationPlugin::scenarioField)
                .toList());
        List<GuiConfigFieldLayoutContribution> layouts = Arrays.stream(NotificationScenario.values())
                .map(scenario -> new GuiConfigFieldLayoutContribution(
                        NotificationConfigKeys.scenarioEnabledKey(scenario.id()),
                        10 + scenario.ordinal() * 10))
                .toList();
        GuiConfigSectionContribution section = new GuiConfigSectionContribution(
                "notification.scenarios",
                GuiConfigGroups.NOTIFICATION,
                "",
                "",
                ID,
                "gui.config.notification.scenario.section.label",
                "gui.config.notification.scenario.section.help",
                "",
                "",
                List.of(),
                GuiConfigSectionLayout.COMPACT_GRID,
                100,
                layouts,
                List.of(),
                List.of(),
                false,
                false);
        GuiConfigSectionContribution services = new GuiConfigSectionContribution(
                NOTIFICATION_SERVICES_SECTION,
                GuiConfigGroups.NOTIFICATION,
                "",
                "",
                ID,
                "gui.config.notification.service.label",
                "gui.config.notification.service.help",
                "",
                "",
                List.of(),
                GuiConfigSectionLayout.CARD_SWITCHER,
                200,
                List.of(
                        inboxLayout(INBOX_ENABLED_KEY, 90),
                        inboxLayout(INBOX_MAX_MESSAGES_KEY, 100),
                        inboxLayout(INBOX_RETENTION_DAYS_KEY, 110)),
                List.of(inboxTestAction(), inboxTestAllAction()),
                List.of(),
                true,
                true);
        return List.of(new GuiConfigContribution(List.of(), fields, List.of(section, services)));
    }

    private static GuiConfigFieldContribution inboxField() {
        return new GuiConfigFieldContribution(
                INBOX_ENABLED_KEY,
                GuiConfigGroups.NOTIFICATION,
                "gui.config.field.notification.inbox.enabled.label",
                "gui.config.field.notification.inbox.enabled.help",
                ID,
                GuiConfigFieldType.BOOL,
                "true",
                90,
                false,
                false,
                List.of(),
                List.of(),
                List.of(),
                null,
                null);
    }

    private static GuiConfigFieldContribution inboxRetentionField(String key, int defaultValue, int order) {
        return new GuiConfigFieldContribution(
                key,
                GuiConfigGroups.NOTIFICATION,
                "gui.config.field." + key + ".label",
                "gui.config.field." + key + ".help",
                ID,
                GuiConfigFieldType.INT,
                Integer.toString(defaultValue),
                order,
                false,
                false,
                List.of(),
                List.of(),
                List.of(),
                1,
                null,
                false);
    }

    private static GuiConfigFieldLayoutContribution inboxLayout(String fieldKey, int order) {
        return new GuiConfigFieldLayoutContribution(
                fieldKey,
                INBOX_CARD_ID,
                "gui.config.notification.service.inbox",
                ID,
                order);
    }

    private static GuiConfigActionContribution inboxTestAction() {
        return new GuiConfigActionContribution(
                "notification.inbox.test",
                "gui.config.notification.inbox.test-button.label",
                "gui.config.notification.inbox.test-button.help",
                ID,
                INBOX_CARD_ID,
                "notification-inbox-test",
                30_000,
                1090,
                List.of(),
                "gui.config.notification.inbox.test.notice.sending",
                List.of(
                        new GuiConfigActionResultRule(
                                "gui.config.notification.inbox.test.notice.unreachable",
                                10,
                                List.of(GuiConfigActionResultCondition.reachable(false)),
                                List.of()),
                        new GuiConfigActionResultRule(
                                "gui.config.notification.inbox.test.notice.success",
                                20,
                                List.of(
                                        GuiConfigActionResultCondition.reachable(true),
                                        GuiConfigActionResultCondition.http2xx(true),
                                        GuiConfigActionResultCondition.jsonTrue("success")),
                                List.of()),
                        new GuiConfigActionResultRule(
                                "gui.config.notification.inbox.test.notice.failed",
                                30,
                                List.of(GuiConfigActionResultCondition.reachable(true)),
                                List.of())),
                null);
    }

    private static GuiConfigActionContribution inboxTestAllAction() {
        return new GuiConfigActionContribution(
                "notification.inbox.test-all",
                "gui.config.notification.inbox.test-all.button.label",
                "gui.config.notification.inbox.test-all.button.help",
                ID,
                INBOX_CARD_ID,
                "notification-inbox-test-all",
                30_000,
                1100,
                List.of(),
                "gui.config.notification.inbox.test-all.notice.sending",
                List.of(
                        new GuiConfigActionResultRule(
                                "gui.config.notification.inbox.test.notice.unreachable",
                                10,
                                List.of(GuiConfigActionResultCondition.reachable(false)),
                                List.of()),
                        new GuiConfigActionResultRule(
                                "gui.config.notification.inbox.test-all.notice.success",
                                20,
                                List.of(
                                        GuiConfigActionResultCondition.reachable(true),
                                        GuiConfigActionResultCondition.http2xx(true),
                                        GuiConfigActionResultCondition.jsonTrue("success")),
                                List.of(GuiConfigActionResultArgument.json("total"))),
                        new GuiConfigActionResultRule(
                                "gui.config.notification.inbox.test-all.notice.partial",
                                30,
                                List.of(
                                        GuiConfigActionResultCondition.reachable(true),
                                        GuiConfigActionResultCondition.http2xx(true),
                                        GuiConfigActionResultCondition.jsonGreaterThan("succeeded", 0)),
                                List.of(
                                        GuiConfigActionResultArgument.json("succeeded"),
                                        GuiConfigActionResultArgument.json("total"),
                                        GuiConfigActionResultArgument.summary())),
                        new GuiConfigActionResultRule(
                                "gui.config.notification.inbox.test.notice.failed",
                                40,
                                List.of(GuiConfigActionResultCondition.reachable(true)),
                                List.of())),
                GuiConfigActionResultSummary.allItems("failures", "scenarioId", ""));
    }

    private static GuiConfigFieldContribution scenarioField(NotificationScenario scenario) {
        String key = NotificationConfigKeys.scenarioEnabledKey(scenario.id());
        String prefix = "gui.config.field.notification.scenario." + scenario.id();
        return new GuiConfigFieldContribution(
                key,
                GuiConfigGroups.NOTIFICATION,
                prefix + ".label",
                prefix + ".help",
                ID,
                GuiConfigFieldType.BOOL,
                "true",
                10 + scenario.ordinal() * 10,
                false,
                false,
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
                false);
    }
}

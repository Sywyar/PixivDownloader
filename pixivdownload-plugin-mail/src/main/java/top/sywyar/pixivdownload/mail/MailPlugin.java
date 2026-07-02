package top.sywyar.pixivdownload.mail;

import top.sywyar.pixivdownload.mail.preset.MailPreset;
import top.sywyar.pixivdownload.mail.preset.MailPresetRegistry;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigActionContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigActionPayloadField;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigActionPayloadType;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigActionResultArgument;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigActionResultCondition;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigActionResultRule;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigActionResultSummary;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigCondition;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldLayoutContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldType;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigGroups;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigPresetContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigSectionContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigSectionLayout;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigSectionNoticeContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigSectionNoticeStyle;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;

import java.util.List;
import java.util.Map;

public class MailPlugin implements PixivFeaturePlugin {

    public static final String ID = "mail";
    private static final String NOTIFICATION_NOTICE_SECTION = "notification.service.notice";
    private static final String NOTIFICATION_SERVICE_NOTICE = "notification.service.concurrent";
    private static final String NOTIFICATION_SERVICES_SECTION = "notification.services";
    private static final String CARD_ID = "mail";

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
        return "mail";
    }

    @Override
    public String colorToken() {
        return "green";
    }

    @Override
    public PluginKind kind() {
        return PluginKind.FEATURE;
    }

    @Override
    public List<WebRouteContribution> routes() {
        return List.of(
                WebRouteContribution.gui("/api/gui/mail-test"),
                WebRouteContribution.gui("/api/gui/mail-test-all"));
    }

    @Override
    public List<I18nContribution> i18n() {
        return List.of(new I18nContribution(ID, "i18n.web.mail", 9));
    }

    @Override
    public List<GuiConfigContribution> guiConfigContributions() {
        GuiConfigCondition enabled = GuiConfigCondition.isTrue("mail.enabled");
        List<GuiConfigFieldContribution> fields = List.of(
                bool("mail.enabled", "false", 100),
                string("mail.host", "", 110, enabled),
                port("mail.port", "587", 120, enabled),
                enumeration("mail.security", "starttls", 130, List.of("none", "ssl", "starttls"), enabled),
                string("mail.username", "", 140, enabled),
                password("mail.password", "", 150, enabled),
                string("mail.from", "", 160, enabled),
                string("mail.to", "", 170, enabled),
                string("mail.socks-proxy", "", 180, enabled),
                string("mail.subject-prefix", "[PixivDownloader]", 190, enabled)
        );
        GuiConfigSectionContribution section = new GuiConfigSectionContribution(
                NOTIFICATION_SERVICES_SECTION,
                GuiConfigGroups.NOTIFICATION,
                "",
                "",
                ID,
                "gui.config.notification.service.label",
                "gui.config.notification.service.help",
                "gui.config.mail.preset.label",
                "gui.config.mail.preset.help",
                List.of(),
                GuiConfigSectionLayout.CARD_SWITCHER,
                200,
                fields.stream()
                        .map(field -> new GuiConfigFieldLayoutContribution(
                                field.key(), CARD_ID, "gui.config.notification.service.mail", ID, field.order()))
                        .toList(),
                List.of(mailTestAction(), mailTestAllAction()),
                mailPresets(),
                true,
                true);
        return List.of(new GuiConfigContribution(
                List.of(), fields, List.of(notificationNoticeSection(), section)));
    }

    private static GuiConfigSectionContribution notificationNoticeSection() {
        return new GuiConfigSectionContribution(
                NOTIFICATION_NOTICE_SECTION,
                GuiConfigGroups.NOTIFICATION,
                "",
                "",
                ID,
                "",
                "",
                "",
                "",
                List.of(new GuiConfigSectionNoticeContribution(
                        NOTIFICATION_SERVICE_NOTICE,
                        "gui.config.notification.hint",
                        ID,
                        GuiConfigSectionNoticeStyle.HINT,
                        0)),
                GuiConfigSectionLayout.FIELD_LIST,
                70,
                List.of(),
                List.of(),
                List.of(),
                true,
                false);
    }

    private static GuiConfigActionContribution mailTestAction() {
        return new GuiConfigActionContribution(
                "mail.test",
                "gui.config.mail.test-button.label",
                "gui.config.mail.test-button.help",
                ID,
                CARD_ID,
                "mail-test",
                30_000,
                1000,
                mailPayload(),
                "gui.config.mail.test.notice.sending",
                List.of(
                        new GuiConfigActionResultRule(
                                "gui.config.mail.test.notice.unreachable",
                                10,
                                List.of(GuiConfigActionResultCondition.reachable(false)),
                                List.of()),
                        new GuiConfigActionResultRule(
                                "gui.config.mail.test.notice.success",
                                20,
                                List.of(
                                        GuiConfigActionResultCondition.reachable(true),
                                        GuiConfigActionResultCondition.http2xx(true),
                                        GuiConfigActionResultCondition.jsonTrue("success")),
                                List.of()),
                        new GuiConfigActionResultRule(
                                "gui.config.mail.test.notice.failed",
                                30,
                                List.of(GuiConfigActionResultCondition.reachable(true)),
                                List.of(GuiConfigActionResultArgument.json("error")))),
                null);
    }

    private static GuiConfigActionContribution mailTestAllAction() {
        return new GuiConfigActionContribution(
                "mail.test-all",
                "gui.config.mail.test-all.button.label",
                "gui.config.mail.test-all.button.help",
                ID,
                CARD_ID,
                "mail-test-all",
                180_000,
                1010,
                mailPayload(),
                "gui.config.mail.test-all.notice.sending",
                List.of(
                        new GuiConfigActionResultRule(
                                "gui.config.mail.test.notice.unreachable",
                                10,
                                List.of(GuiConfigActionResultCondition.reachable(false)),
                                List.of()),
                        new GuiConfigActionResultRule(
                                "gui.config.mail.test-all.notice.success",
                                20,
                                List.of(
                                        GuiConfigActionResultCondition.reachable(true),
                                        GuiConfigActionResultCondition.http2xx(true),
                                        GuiConfigActionResultCondition.jsonTrue("success")),
                                List.of(GuiConfigActionResultArgument.json("total"))),
                        new GuiConfigActionResultRule(
                                "gui.config.mail.test-all.notice.partial",
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
                                "gui.config.mail.test.notice.failed",
                                40,
                                List.of(GuiConfigActionResultCondition.reachable(true)),
                                List.of(GuiConfigActionResultArgument.summary()))),
                GuiConfigActionResultSummary.allItems("failures", "templateId", "error"));
    }

    private static List<GuiConfigActionPayloadField> mailPayload() {
        return List.of(
                new GuiConfigActionPayloadField("host", "mail.host"),
                new GuiConfigActionPayloadField("port", "mail.port", GuiConfigActionPayloadType.INT),
                new GuiConfigActionPayloadField("security", "mail.security"),
                new GuiConfigActionPayloadField("username", "mail.username"),
                new GuiConfigActionPayloadField("password", "mail.password"),
                new GuiConfigActionPayloadField("from", "mail.from"),
                new GuiConfigActionPayloadField("to", "mail.to"),
                new GuiConfigActionPayloadField("socksProxy", "mail.socks-proxy"),
                new GuiConfigActionPayloadField("subjectPrefix", "mail.subject-prefix"));
    }

    private static List<GuiConfigPresetContribution> mailPresets() {
        return new MailPresetRegistry().all().stream()
                .map(MailPlugin::mailPreset)
                .toList();
    }

    private static GuiConfigPresetContribution mailPreset(MailPreset preset) {
        Map<String, String> values = preset.isCustom()
                ? Map.of()
                : Map.of(
                        "mail.host", preset.host(),
                        "mail.port", Integer.toString(preset.port()),
                        "mail.security", preset.security().value());
        return new GuiConfigPresetContribution(
                preset.id(),
                preset.displayNameKey(),
                preset.credentialHelpKey(),
                ID,
                CARD_ID,
                preset.isCustom() ? 10_000 : valuesOrder(preset.id()),
                preset.isCustom() ? null : "mail.host",
                preset.host(),
                values);
    }

    private static int valuesOrder(String id) {
        List<MailPreset> presets = new MailPresetRegistry().all();
        for (int i = 0; i < presets.size(); i++) {
            if (presets.get(i).id().equals(id)) {
                return 10 + i * 10;
            }
        }
        return 10_000;
    }

    private static GuiConfigFieldContribution bool(String key, String defaultValue, int order,
                                                   GuiConfigCondition... enabledWhen) {
        return field(key, GuiConfigFieldType.BOOL, defaultValue, order, List.of(), enabledWhen);
    }

    private static GuiConfigFieldContribution string(String key, String defaultValue, int order,
                                                     GuiConfigCondition... enabledWhen) {
        return field(key, GuiConfigFieldType.STRING, defaultValue, order, List.of(), enabledWhen);
    }

    private static GuiConfigFieldContribution password(String key, String defaultValue, int order,
                                                       GuiConfigCondition... enabledWhen) {
        return field(key, GuiConfigFieldType.PASSWORD, defaultValue, order, List.of(), enabledWhen);
    }

    private static GuiConfigFieldContribution port(String key, String defaultValue, int order,
                                                   GuiConfigCondition... enabledWhen) {
        return field(key, GuiConfigFieldType.PORT, defaultValue, order, List.of(), enabledWhen);
    }

    private static GuiConfigFieldContribution enumeration(String key, String defaultValue, int order,
                                                          List<String> enumValues,
                                                          GuiConfigCondition... enabledWhen) {
        return field(key, GuiConfigFieldType.ENUM, defaultValue, order, enumValues, enabledWhen);
    }

    private static GuiConfigFieldContribution field(String key, GuiConfigFieldType type, String defaultValue,
                                                    int order, List<String> enumValues,
                                                    GuiConfigCondition... enabledWhen) {
        String prefix = "gui.config.field." + key;
        return new GuiConfigFieldContribution(key, GuiConfigGroups.NOTIFICATION, prefix + ".label", prefix + ".help",
                ID, type, defaultValue, order, false, false, enumValues,
                List.of(enabledWhen), List.of(), null, null);
    }
}

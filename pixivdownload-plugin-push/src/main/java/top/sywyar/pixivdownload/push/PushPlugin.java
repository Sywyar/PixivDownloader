package top.sywyar.pixivdownload.push;

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
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigSectionContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigSectionLayout;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigSectionNoticeContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigSectionNoticeStyle;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;

import java.util.List;

public class PushPlugin implements PixivFeaturePlugin {

    public static final String ID = "push";
    private static final String NOTIFICATION_NOTICE_SECTION = "notification.service.notice";
    private static final String NOTIFICATION_SERVICE_NOTICE = "notification.service.concurrent";
    private static final String NOTIFICATION_SERVICES_SECTION = "notification.services";
    private static final List<PushChannelLayout> CHANNELS = List.of(
            new PushChannelLayout("bark", 110, List.of(
                    mapping("push.bark.server", "server"),
                    mapping("push.bark.device-key", "deviceKey"),
                    mapping("push.bark.sound", "sound"),
                    mapping("push.bark.use-proxy", "useProxy", GuiConfigActionPayloadType.BOOLEAN))),
            new PushChannelLayout("dingtalk", 120, List.of(
                    mapping("push.dingtalk.access-token", "accessToken"),
                    mapping("push.dingtalk.secret", "secret"),
                    mapping("push.dingtalk.use-proxy", "useProxy", GuiConfigActionPayloadType.BOOLEAN))),
            new PushChannelLayout("telegram", 130, List.of(
                    mapping("push.telegram.bot-token", "botToken"),
                    mapping("push.telegram.chat-id", "chatId"),
                    mapping("push.telegram.use-proxy", "useProxy", GuiConfigActionPayloadType.BOOLEAN))),
            new PushChannelLayout("feishu", 140, List.of(
                    mapping("push.feishu.webhook-key", "webhookKey"),
                    mapping("push.feishu.secret", "secret"),
                    mapping("push.feishu.use-proxy", "useProxy", GuiConfigActionPayloadType.BOOLEAN))),
            new PushChannelLayout("wecom", 150, List.of(
                    mapping("push.wecom.key", "key"),
                    mapping("push.wecom.use-proxy", "useProxy", GuiConfigActionPayloadType.BOOLEAN))),
            new PushChannelLayout("pushplus", 160, List.of(
                    mapping("push.pushplus.token", "token"),
                    mapping("push.pushplus.use-proxy", "useProxy", GuiConfigActionPayloadType.BOOLEAN))),
            new PushChannelLayout("serverchan", 170, List.of(
                    mapping("push.serverchan.send-key", "sendKey"),
                    mapping("push.serverchan.use-proxy", "useProxy", GuiConfigActionPayloadType.BOOLEAN))),
            new PushChannelLayout("webhook", 180, List.of(
                    mapping("push.webhook.url", "url"),
                    mapping("push.webhook.content-type", "contentType"),
                    mapping("push.webhook.body-template", "bodyTemplate"),
                    mapping("push.webhook.use-proxy", "useProxy", GuiConfigActionPayloadType.BOOLEAN))));

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
        return "blue";
    }

    @Override
    public PluginKind kind() {
        return PluginKind.FEATURE;
    }

    @Override
    public List<WebRouteContribution> routes() {
        return List.of(
                WebRouteContribution.gui("/api/gui/push-test"),
                WebRouteContribution.gui("/api/gui/push-test-all"));
    }

    @Override
    public List<I18nContribution> i18n() {
        return List.of(new I18nContribution(ID, "i18n.web.push", 8));
    }

    @Override
    public List<GuiConfigContribution> guiConfigContributions() {
        GuiConfigCondition enabled = GuiConfigCondition.isTrue("push.enabled");
        List<GuiConfigFieldContribution> fields = List.of(
                bool("push.enabled", "false", 100),

                bool("push.bark.enabled", "false", 110, enabled),
                string("push.bark.server", "https://api.day.app", 111, enabled, on("push.bark.enabled")),
                password("push.bark.device-key", "", 112, enabled, on("push.bark.enabled")),
                string("push.bark.sound", "", 113, enabled, on("push.bark.enabled")),
                useProxy("push.bark.use-proxy", "false", 114, enabled, on("push.bark.enabled")),

                bool("push.dingtalk.enabled", "false", 120, enabled),
                password("push.dingtalk.access-token", "", 121, enabled, on("push.dingtalk.enabled")),
                password("push.dingtalk.secret", "", 122, enabled, on("push.dingtalk.enabled")),
                useProxy("push.dingtalk.use-proxy", "false", 123, enabled, on("push.dingtalk.enabled")),

                bool("push.telegram.enabled", "false", 130, enabled),
                password("push.telegram.bot-token", "", 131, enabled, on("push.telegram.enabled")),
                string("push.telegram.chat-id", "", 132, enabled, on("push.telegram.enabled")),
                useProxy("push.telegram.use-proxy", "true", 133, enabled, on("push.telegram.enabled")),

                bool("push.feishu.enabled", "false", 140, enabled),
                password("push.feishu.webhook-key", "", 141, enabled, on("push.feishu.enabled")),
                password("push.feishu.secret", "", 142, enabled, on("push.feishu.enabled")),
                useProxy("push.feishu.use-proxy", "false", 143, enabled, on("push.feishu.enabled")),

                bool("push.wecom.enabled", "false", 150, enabled),
                password("push.wecom.key", "", 151, enabled, on("push.wecom.enabled")),
                useProxy("push.wecom.use-proxy", "false", 152, enabled, on("push.wecom.enabled")),

                bool("push.pushplus.enabled", "false", 160, enabled),
                password("push.pushplus.token", "", 161, enabled, on("push.pushplus.enabled")),
                useProxy("push.pushplus.use-proxy", "false", 162, enabled, on("push.pushplus.enabled")),

                bool("push.serverchan.enabled", "false", 170, enabled),
                password("push.serverchan.send-key", "", 171, enabled, on("push.serverchan.enabled")),
                useProxy("push.serverchan.use-proxy", "false", 172, enabled, on("push.serverchan.enabled")),

                bool("push.webhook.enabled", "false", 180, enabled),
                string("push.webhook.url", "", 181, enabled, on("push.webhook.enabled")),
                string("push.webhook.content-type", "application/json", 182, enabled, on("push.webhook.enabled")),
                string("push.webhook.body-template", "", 183, enabled, on("push.webhook.enabled")),
                useProxy("push.webhook.use-proxy", "false", 184, enabled, on("push.webhook.enabled"))
        );
        GuiConfigSectionContribution master = new GuiConfigSectionContribution(
                "push.master",
                GuiConfigGroups.NOTIFICATION,
                GuiConfigSectionLayout.FIELD_LIST,
                80,
                List.of(new GuiConfigFieldLayoutContribution("push.enabled", 80)));
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
                channelFieldLayouts(),
                channelActions(),
                List.of(),
                true,
                true);
        return List.of(new GuiConfigContribution(
                List.of(), fields, List.of(notificationNoticeSection(), master, services)));
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

    private static List<GuiConfigFieldLayoutContribution> channelFieldLayouts() {
        return CHANNELS.stream()
                .flatMap(channel -> channel.configKeys().stream()
                        .map(key -> new GuiConfigFieldLayoutContribution(
                                key,
                                channel.id(),
                                "gui.config.notification.service." + channel.id(),
                                ID,
                                channel.orderOf(key))))
                .toList();
    }

    private static List<GuiConfigActionContribution> channelActions() {
        return CHANNELS.stream()
                .flatMap(channel -> List.of(pushTestAction(channel), pushTestAllAction(channel)).stream())
                .toList();
    }

    private static GuiConfigActionContribution pushTestAction(PushChannelLayout channel) {
        return new GuiConfigActionContribution(
                "push." + channel.id() + ".test",
                "gui.config.push.test-current-button.label",
                "gui.config.push.test-current-button.help",
                ID,
                channel.id(),
                "push-test",
                30_000,
                channel.order() + 1000,
                pushPayload(channel),
                "gui.config.push.test.notice.sending",
                List.of(
                        new GuiConfigActionResultRule(
                                "gui.config.push.test.notice.unreachable",
                                10,
                                List.of(GuiConfigActionResultCondition.reachable(false)),
                                List.of()),
                        new GuiConfigActionResultRule(
                                "gui.config.push.test.notice.none",
                                20,
                                List.of(GuiConfigActionResultCondition.jsonEquals("total", "0")),
                                List.of()),
                        new GuiConfigActionResultRule(
                                "gui.config.push.test.notice.current-success",
                                30,
                                List.of(
                                        GuiConfigActionResultCondition.reachable(true),
                                        GuiConfigActionResultCondition.http2xx(true),
                                        GuiConfigActionResultCondition.jsonTrue("success")),
                                List.of()),
                        new GuiConfigActionResultRule(
                                "gui.config.push.test.notice.current-skipped",
                                40,
                                List.of(GuiConfigActionResultCondition.summaryContains("SKIPPED")),
                                List.of()),
                        new GuiConfigActionResultRule(
                                "gui.config.push.test.notice.current-failed",
                                50,
                                List.of(GuiConfigActionResultCondition.reachable(true)),
                                List.of(GuiConfigActionResultArgument.summary()))),
                pushSummary());
    }

    private static GuiConfigActionContribution pushTestAllAction(PushChannelLayout channel) {
        return new GuiConfigActionContribution(
                "push." + channel.id() + ".test-all",
                "gui.config.push.test-all.button.label",
                "gui.config.push.test-all.button.help",
                ID,
                channel.id(),
                "push-test-all",
                10 * 60 * 1000,
                channel.order() + 1010,
                pushPayload(channel),
                "gui.config.push.test-all.notice.sending",
                List.of(
                        new GuiConfigActionResultRule(
                                "gui.config.push.test.notice.unreachable",
                                10,
                                List.of(GuiConfigActionResultCondition.reachable(false)),
                                List.of()),
                        new GuiConfigActionResultRule(
                                "gui.config.push.test-all.notice.skipped",
                                20,
                                List.of(GuiConfigActionResultCondition.jsonEquals("total", "0")),
                                List.of()),
                        new GuiConfigActionResultRule(
                                "gui.config.push.test-all.notice.skipped",
                                30,
                                List.of(
                                        GuiConfigActionResultCondition.jsonEquals("succeeded", "0"),
                                        GuiConfigActionResultCondition.summaryContains("SKIPPED")),
                                List.of()),
                        new GuiConfigActionResultRule(
                                "gui.config.push.test-all.notice.success",
                                40,
                                List.of(
                                        GuiConfigActionResultCondition.reachable(true),
                                        GuiConfigActionResultCondition.http2xx(true),
                                        GuiConfigActionResultCondition.jsonTrue("success")),
                                List.of(GuiConfigActionResultArgument.json("total"))),
                        new GuiConfigActionResultRule(
                                "gui.config.push.test-all.notice.partial",
                                50,
                                List.of(GuiConfigActionResultCondition.reachable(true)),
                                List.of(
                                        GuiConfigActionResultArgument.json("succeeded"),
                                        GuiConfigActionResultArgument.json("total"),
                                        GuiConfigActionResultArgument.summary()))),
                pushSummary());
    }

    private static List<GuiConfigActionPayloadField> pushPayload(PushChannelLayout channel) {
        List<GuiConfigActionPayloadField> fields = new java.util.ArrayList<>();
        fields.add(GuiConfigActionPayloadField.literal(
                channel.id() + ".enabled", "true", GuiConfigActionPayloadType.BOOLEAN));
        channel.mappings().stream()
                .map(mapping -> new GuiConfigActionPayloadField(
                        channel.id() + "." + mapping.payloadName(), mapping.configKey(), mapping.type()))
                .forEach(fields::add);
        return List.copyOf(fields);
    }

    private static GuiConfigActionResultSummary pushSummary() {
        return GuiConfigActionResultSummary.nonSuccessItems("results", "channel", "status", "OK", "detail");
    }

    private static PayloadMapping mapping(String configKey, String payloadName) {
        return mapping(configKey, payloadName, GuiConfigActionPayloadType.STRING);
    }

    private static PayloadMapping mapping(String configKey, String payloadName,
                                          GuiConfigActionPayloadType type) {
        return new PayloadMapping(configKey, payloadName, type);
    }

    private static GuiConfigCondition on(String key) {
        return GuiConfigCondition.isTrue(key);
    }

    private static GuiConfigFieldContribution bool(String key, String defaultValue, int order,
                                                   GuiConfigCondition... enabledWhen) {
        return field(key, keyPrefix(key) + ".help", GuiConfigFieldType.BOOL, defaultValue, order, enabledWhen);
    }

    private static GuiConfigFieldContribution string(String key, String defaultValue, int order,
                                                     GuiConfigCondition... enabledWhen) {
        return field(key, keyPrefix(key) + ".help", GuiConfigFieldType.STRING, defaultValue, order, enabledWhen);
    }

    private static GuiConfigFieldContribution password(String key, String defaultValue, int order,
                                                       GuiConfigCondition... enabledWhen) {
        return field(key, keyPrefix(key) + ".help", GuiConfigFieldType.PASSWORD, defaultValue, order, enabledWhen);
    }

    private static GuiConfigFieldContribution useProxy(String key, String defaultValue, int order,
                                                       GuiConfigCondition... enabledWhen) {
        return field(key, "gui.config.field.push.use-proxy.help",
                GuiConfigFieldType.BOOL, defaultValue, order, enabledWhen);
    }

    private static GuiConfigFieldContribution field(String key, String helpKey, GuiConfigFieldType type,
                                                    String defaultValue, int order,
                                                    GuiConfigCondition... enabledWhen) {
        return new GuiConfigFieldContribution(key, GuiConfigGroups.NOTIFICATION, keyPrefix(key) + ".label", helpKey,
                ID, type, defaultValue, order, false, false, List.of(),
                List.of(enabledWhen), List.of(), null, null);
    }

    private static String keyPrefix(String key) {
        return "gui.config.field." + key;
    }

    private record PushChannelLayout(String id, int order, List<PayloadMapping> mappings) {
        private List<String> configKeys() {
            List<String> keys = new java.util.ArrayList<>();
            keys.add("push." + id + ".enabled");
            mappings.stream().map(PayloadMapping::configKey).forEach(keys::add);
            return List.copyOf(keys);
        }

        private int orderOf(String key) {
            if (key.equals("push." + id + ".enabled")) {
                return order;
            }
            for (int i = 0; i < mappings.size(); i++) {
                if (mappings.get(i).configKey().equals(key)) {
                    return order + i + 1;
                }
            }
            return order + 99;
        }
    }

    private record PayloadMapping(String configKey, String payloadName, GuiConfigActionPayloadType type) {
    }
}

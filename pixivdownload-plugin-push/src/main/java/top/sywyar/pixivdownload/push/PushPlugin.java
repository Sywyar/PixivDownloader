package top.sywyar.pixivdownload.push;

import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigCondition;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldType;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigGroups;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;

import java.util.List;

public class PushPlugin implements PixivFeaturePlugin {

    public static final String ID = "push";

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
        return List.of(new GuiConfigContribution(List.of(
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
        )));
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
}

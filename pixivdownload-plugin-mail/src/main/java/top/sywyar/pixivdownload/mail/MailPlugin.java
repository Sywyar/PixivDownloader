package top.sywyar.pixivdownload.mail;

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

public class MailPlugin implements PixivFeaturePlugin {

    public static final String ID = "mail";

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
        return List.of(new GuiConfigContribution(List.of(
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
        )));
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

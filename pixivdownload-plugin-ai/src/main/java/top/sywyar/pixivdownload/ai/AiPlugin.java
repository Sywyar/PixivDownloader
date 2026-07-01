package top.sywyar.pixivdownload.ai;

import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigCondition;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldType;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigGroups;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;
import top.sywyar.pixivdownload.plugin.api.web.StaticResourceContribution;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;
import top.sywyar.pixivdownload.plugin.api.web.WebUiSlotContribution;

import java.util.List;

public class AiPlugin implements PixivFeaturePlugin {

    public static final String ID = "ai";

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
        return "sparkles";
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
    public List<WebRouteContribution> routes() {
        return List.of(
                WebRouteContribution.gui("/api/gui/ai-test"),
                WebRouteContribution.admin("/api/admin/ai/**"),
                WebRouteContribution.visitorAndInvitedGuest("/pixiv-ai/**"));
    }

    @Override
    public List<StaticResourceContribution> staticResources() {
        return List.of(new StaticResourceContribution(ID, "classpath:/static/pixiv-ai/", "/pixiv-ai/"));
    }

    @Override
    public List<WebUiSlotContribution> uiSlots() {
        return List.of(
                new WebUiSlotContribution(ID, ID + ".novel-detail-translate", "novel-detail-ai-translate",
                        "/pixiv-ai/novel-detail-ai-translate-slot.js", 20),
                new WebUiSlotContribution(ID, ID + ".series-detail-translate", "series-detail-ai-translate",
                        "/pixiv-ai/series-detail-ai-translate-slot.js", 20),
                new WebUiSlotContribution(ID, ID + ".download-novel-translate-settings",
                        "settings-card-ai", "/pixiv-ai/download-novel-ai-settings-slot.js", 20));
    }

    @Override
    public List<I18nContribution> i18n() {
        return List.of(
                new I18nContribution(ID, "i18n.web.ai", 10),
                new I18nContribution("translate", "i18n.web.translate", 13));
    }

    @Override
    public List<GuiConfigContribution> guiConfigContributions() {
        return List.of(new GuiConfigContribution(List.of(
                bool("ai.enabled", "false", 100),
                string("ai.base-url", "", 110, GuiConfigCondition.isTrue("ai.enabled")),
                password("ai.api-key", "", 120, GuiConfigCondition.isTrue("ai.enabled")),
                string("ai.model", "", 130, GuiConfigCondition.isTrue("ai.enabled")),
                bool("ai.use-proxy", "false", 140, GuiConfigCondition.isTrue("ai.enabled"))
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

    private static GuiConfigFieldContribution field(String key, GuiConfigFieldType type, String defaultValue,
                                                    int order, List<String> enumValues,
                                                    GuiConfigCondition... enabledWhen) {
        String prefix = "gui.config.field." + key;
        return new GuiConfigFieldContribution(key, GuiConfigGroups.AI, prefix + ".label", prefix + ".help",
                ID, type, defaultValue, order, false, false, enumValues,
                List.of(enabledWhen), List.of(), null, null);
    }
}

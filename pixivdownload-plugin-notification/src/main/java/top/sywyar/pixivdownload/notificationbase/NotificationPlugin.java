package top.sywyar.pixivdownload.notificationbase;

import top.sywyar.pixivdownload.notification.NotificationConfigKeys;
import top.sywyar.pixivdownload.notification.NotificationScenario;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldType;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigGroups;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;

import java.util.Arrays;
import java.util.List;

/**
 * 中性的通知基础插件：只拥有 {@code notification.scenario.*} 配置字段与对应展示文案，
 * 不注册任何发送介质、Sink、控制器或静态资源。
 */
public class NotificationPlugin implements PixivFeaturePlugin {

    public static final String ID = "notification";

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
    public List<I18nContribution> i18n() {
        return List.of(new I18nContribution(ID, "i18n.web.notification", 7));
    }

    @Override
    public List<GuiConfigContribution> guiConfigContributions() {
        return List.of(new GuiConfigContribution(Arrays.stream(NotificationScenario.values())
                .map(NotificationPlugin::scenarioField)
                .toList()));
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
                null);
    }
}

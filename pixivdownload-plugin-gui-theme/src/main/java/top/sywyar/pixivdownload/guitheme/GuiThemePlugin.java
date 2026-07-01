package top.sywyar.pixivdownload.guitheme;

import top.sywyar.pixivdownload.plugin.api.gui.GuiThemeAppearance;
import top.sywyar.pixivdownload.plugin.api.gui.GuiThemeContribution;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;

import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

public class GuiThemePlugin implements PixivFeaturePlugin {

    public static final String ID = "gui-theme";
    private static final String I18N_BUNDLE = "i18n.web.gui-theme";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "plugin.label";
    }

    @Override
    public String description() {
        return "plugin.summary";
    }

    @Override
    public String iconKey() {
        return "palette";
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
    public List<I18nContribution> i18n() {
        return List.of(new I18nContribution(ID, "i18n.web.gui-theme", 8));
    }

    @Override
    public List<GuiThemeContribution> guiThemes() {
        return List.of(
                contribution(ThemePreference.SYSTEM, GuiThemeAppearance.SYSTEM),
                contribution(ThemePreference.LIGHT, GuiThemeAppearance.LIGHT),
                contribution(ThemePreference.DARK, GuiThemeAppearance.DARK),
                contribution(ThemePreference.MOONLIGHT, GuiThemeAppearance.DARK));
    }

    private static GuiThemeContribution contribution(ThemePreference preference,
                                                    GuiThemeAppearance appearance) {
        return new GuiThemeContribution(
                preference.toConfigValue(),
                locale -> displayName(preference, locale),
                appearance,
                () -> FlatLafSetup.apply(preference),
                listener -> FlatLafSetup.openListener(preference, listener));
    }

    private static String displayName(ThemePreference preference, Locale locale) {
        String key = "theme." + preference.toConfigValue();
        Locale effective = locale == null ? Locale.getDefault() : locale;
        try {
            String name = ResourceBundle.getBundle(I18N_BUNDLE, effective, GuiThemePlugin.class.getClassLoader())
                    .getString(key);
            return name == null || name.isBlank() ? key : name;
        } catch (MissingResourceException e) {
            return key;
        }
    }
}

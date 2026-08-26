package top.sywyar.pixivdownload.guitheme;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.gui.GuiThemeAppearance;

import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Swing GUI 插件")
class GuiSwingPluginTest {

    private static final String BUNDLE = "i18n.web.gui-swing";

    @Test
    @DisplayName("公开官方主题标识与本地化名称")
    void exposesOfficialThemeIdsAndLocaleNames() {
        GuiSwingPlugin plugin = new GuiSwingPlugin();

        assertThat(plugin.defaultProvider()).isFalse();
        assertThat(plugin.guiThemes())
                .extracting(theme -> theme.themeId())
                .containsExactly("system", "light", "dark", "moonlight");
        assertThat(plugin.guiThemes())
                .extracting(theme -> theme.appearance())
                .containsExactly(GuiThemeAppearance.SYSTEM, GuiThemeAppearance.LIGHT,
                        GuiThemeAppearance.DARK, GuiThemeAppearance.DARK);
        assertThat(plugin.guiThemes())
                .extracting(theme -> theme.displayName(Locale.US))
                .containsExactlyElementsOf(themeNames(Locale.US));
        assertThat(plugin.guiThemes())
                .extracting(theme -> theme.displayName(Locale.SIMPLIFIED_CHINESE))
                .containsExactlyElementsOf(themeNames(Locale.SIMPLIFIED_CHINESE));
        assertThat(plugin.guiThemes())
                .extracting(theme -> theme.displayName(Locale.forLanguageTag("zh-Hant")))
                .containsExactlyElementsOf(themeNames(Locale.forLanguageTag("zh-Hant")));
    }

    private static List<String> themeNames(Locale locale) {
        ResourceBundle bundle = ResourceBundle.getBundle(BUNDLE, locale);
        return List.of(
                bundle.getString("theme.system"),
                bundle.getString("theme.light"),
                bundle.getString("theme.dark"),
                bundle.getString("theme.moonlight"));
    }
}

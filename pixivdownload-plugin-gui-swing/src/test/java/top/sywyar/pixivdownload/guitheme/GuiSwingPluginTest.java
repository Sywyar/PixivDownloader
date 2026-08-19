package top.sywyar.pixivdownload.guitheme;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiCapability;
import top.sywyar.pixivdownload.plugin.api.gui.GuiThemeAppearance;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode;

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
    }

    @Test
    @DisplayName("显式声明稳定桌面节点与语义能力")
    void declaresStableDesktopUiCapabilities() {
        GuiSwingPlugin plugin = new GuiSwingPlugin();

        assertThat(plugin.supportedNodeKinds()).containsExactlyInAnyOrder(
                DesktopUiNode.Kind.CONTAINER, DesktopUiNode.Kind.DOCK, DesktopUiNode.Kind.SURFACE,
                DesktopUiNode.Kind.GROUP, DesktopUiNode.Kind.FORM, DesktopUiNode.Kind.TABS,
                DesktopUiNode.Kind.SCROLL, DesktopUiNode.Kind.SPLIT, DesktopUiNode.Kind.TEXT,
                DesktopUiNode.Kind.IMAGE, DesktopUiNode.Kind.SEPARATOR, DesktopUiNode.Kind.SPACER,
                DesktopUiNode.Kind.PROGRESS, DesktopUiNode.Kind.TEXT_INPUT, DesktopUiNode.Kind.TOGGLE,
                DesktopUiNode.Kind.CHOICE, DesktopUiNode.Kind.NUMBER_INPUT, DesktopUiNode.Kind.TABLE,
                DesktopUiNode.Kind.TREE, DesktopUiNode.Kind.BUTTON, DesktopUiNode.Kind.LINK);
        assertThat(plugin.supportedCapabilities()).containsExactlyInAnyOrder(
                DesktopUiCapability.SPLIT_USER_RESIZABLE,
                DesktopUiCapability.TREE_EXPAND_COLLAPSE,
                DesktopUiCapability.TABLE_LARGE_DATA_SCROLL,
                DesktopUiCapability.INPUT_NUMERIC,
                DesktopUiCapability.INPUT_PATH_FILE,
                DesktopUiCapability.INPUT_PATH_DIRECTORY,
                DesktopUiCapability.SELECTION_MULTIPLE);
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

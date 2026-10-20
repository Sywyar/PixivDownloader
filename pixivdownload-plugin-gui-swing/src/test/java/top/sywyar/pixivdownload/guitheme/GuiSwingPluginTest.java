package top.sywyar.pixivdownload.guitheme;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import top.sywyar.pixivdownload.gui.theme.GuiThemeManager;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiContext;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiPluginSnapshot;
import top.sywyar.pixivdownload.plugin.api.gui.GuiThemeAppearance;
import top.sywyar.pixivdownload.plugin.api.gui.GuiThemeContribution;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("首窗创建失败时关闭主题监听并保留原始错误")
    void failedLaunchClosesThemeListener(boolean closeFails) {
        AtomicInteger closed = new AtomicInteger();
        var theme = new GuiThemeContribution(
                "system",
                locale -> "System",
                GuiThemeAppearance.DARK,
                () -> { },
                listener -> () -> {
                    closed.incrementAndGet();
                    if (closeFails) throw new IllegalStateException("listener close failed");
                }
        );
        try {
            failBeforeFirstWindow(List.of(theme));
            assertThat(closed).hasValue(1);
        } finally {
            GuiThemeManager.close();
        }
    }

    static void failBeforeFirstWindow(List<GuiThemeContribution> themes) {
        var snapshot = new DesktopUiPluginSnapshot(
                "theme", false, "theme", 1, true, null, "",
                themes, List.of(), List.of(), List.of(), List.of()
        );
        IllegalStateException failure = new IllegalStateException("locale unavailable");
        DesktopUiHost host = (DesktopUiHost) Proxy.newProxyInstance(
                GuiSwingPluginTest.class.getClassLoader(),
                new Class<?>[]{DesktopUiHost.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("applicationConfig")) return null;
                    throw failure;
                }
        );
        var context = new DesktopUiContext(
                false, 6999, ".", Path.of("config.yaml"), host,
                List.of(snapshot), () -> List.of(snapshot), text -> "", () -> "system"
        );
        assertThatThrownBy(() -> new GuiSwingPlugin().launch(context)).isSameAs(failure);
    }
}

package top.sywyar.pixivdownload.gui.theme;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.gui.GuiThemeAppearance;
import top.sywyar.pixivdownload.plugin.api.gui.GuiThemeChangeListener;
import top.sywyar.pixivdownload.plugin.api.gui.GuiThemeContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiThemeListenerSession;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;

import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("GUI 主题管理器")
class GuiThemeManagerTest {

    @AfterEach
    void tearDown() {
        GuiThemeManager.resetForTests();
    }

    @Test
    @DisplayName("主题插件缺失时保留配置并回退系统主题")
    void missingPluginFallsBackWithoutOverwritingConfiguredTheme() throws Exception {
        Path config = Files.createTempFile("pixiv-theme-", ".yaml");
        Files.writeString(config, "app.theme: moonlight\n");

        SwingUtilities.invokeAndWait(() -> GuiThemeManager.applyBeforeFirstWindow(
                config, GuiThemeManager.readPersistedThemeId(config), List.of()));

        assertThat(GuiThemeManager.configuredThemeId()).isEqualTo("moonlight");
        assertThat(GuiThemeManager.activeThemeId()).isEqualTo("system");
        assertThat(Files.readString(config)).contains("app.theme: moonlight");
        assertThat(GuiThemeManager.choices(Locale.US, "Unavailable", "System fallback"))
                .extracting(GuiThemeManager.ThemeChoice::id)
                .containsExactly("moonlight", "system");
    }

    @Test
    @DisplayName("主题应用和监听会话在 EDT 上受管")
    void contributionApplyAndListenerLifecycleAreManagedOnEdt() throws Exception {
        AtomicBoolean applied = new AtomicBoolean();
        AtomicInteger managerNotifications = new AtomicInteger();
        AtomicInteger listenerClosed = new AtomicInteger();
        AtomicReference<GuiThemeChangeListener> pluginListener = new AtomicReference<>();

        GuiThemeContribution contribution = new GuiThemeContribution(
                "moonlight",
                locale -> "Moonlight",
                GuiThemeAppearance.DARK,
                () -> applied.set(SwingUtilities.isEventDispatchThread()),
                listener -> {
                    pluginListener.set(listener);
                    return listenerClosed::incrementAndGet;
                });
        TestPlugin plugin = new TestPlugin("gui-theme", List.of(contribution));
        GuiThemeListenerSession session = GuiThemeManager.addChangeListener(managerNotifications::incrementAndGet);

        SwingUtilities.invokeAndWait(() -> GuiThemeManager.applyBeforeFirstWindow(
                null, "moonlight", List.of(source(plugin))));

        assertThat(applied).isTrue();
        assertThat(GuiThemeManager.activeThemeId()).isEqualTo("moonlight");
        assertThat(GuiThemeManager.isCurrentDark()).isTrue();
        assertThat(GuiThemeManager.choices(Locale.US, "Unavailable", "System fallback"))
                .extracting(GuiThemeManager.ThemeChoice::displayName)
                .containsExactly("Moonlight");

        pluginListener.get().appearanceChanged(GuiThemeAppearance.LIGHT);
        SwingUtilities.invokeAndWait(() -> {
        });
        assertThat(GuiThemeManager.isCurrentDark()).isFalse();
        assertThat(managerNotifications.get()).isGreaterThanOrEqualTo(2);

        session.close();
        GuiThemeManager.resetForTests();
        assertThat(listenerClosed).hasValue(1);
    }

    @Test
    @DisplayName("重复主题 id 被整体拒绝并回退")
    void duplicateThemeIdIsRejectedAndFallsBack() throws Exception {
        GuiThemeContribution first = contribution("moonlight", GuiThemeAppearance.DARK);
        GuiThemeContribution second = contribution("moonlight", GuiThemeAppearance.DARK);

        SwingUtilities.invokeAndWait(() -> GuiThemeManager.applyBeforeFirstWindow(
                null, "moonlight",
                List.of(source(new TestPlugin("one", List.of(first))),
                        source(new TestPlugin("two", List.of(second))))));

        assertThat(GuiThemeManager.activeThemeId()).isEqualTo("system");
        assertThat(GuiThemeManager.choices(Locale.US, "Unavailable", "System fallback"))
                .extracting(GuiThemeManager.ThemeChoice::id)
                .containsExactly("moonlight", "system");
    }

    @Test
    @DisplayName("插件返回 null 主题列表时保留配置并回退")
    void nullGuiThemesFromPluginFallsBackWithoutOverwritingConfiguredTheme() throws Exception {
        Path config = Files.createTempFile("pixiv-theme-", ".yaml");
        Files.writeString(config, "app.theme: moonlight\n");
        TestPlugin brokenPlugin = new TestPlugin("broken-theme", null);

        assertThatCode(() -> SwingUtilities.invokeAndWait(() -> GuiThemeManager.applyBeforeFirstWindow(
                config, GuiThemeManager.readPersistedThemeId(config), List.of(source(brokenPlugin)))))
                .doesNotThrowAnyException();

        assertThat(GuiThemeManager.configuredThemeId()).isEqualTo("moonlight");
        assertThat(GuiThemeManager.activeThemeId()).isEqualTo("system");
        assertThat(Files.readString(config)).contains("app.theme: moonlight");
        assertThat(GuiThemeManager.choices(Locale.US, "Unavailable", "System fallback"))
                .extracting(GuiThemeManager.ThemeChoice::id)
                .containsExactly("moonlight", "system");
    }

    @Test
    @DisplayName("主题应用抛出非致命 Error 时不逃逸首窗启动边界")
    void contributionFailureDoesNotEscapeFirstWindowBootstrap() throws Exception {
        GuiThemeContribution failing = new GuiThemeContribution(
                "dark",
                locale -> "Dark",
                GuiThemeAppearance.DARK,
                () -> {
                    throw new AssertionError("boom");
                });

        assertThatCode(() -> SwingUtilities.invokeAndWait(() -> GuiThemeManager.applyBeforeFirstWindow(
                null, "dark", List.of(source(new TestPlugin("gui-theme", List.of(failing)))))))
                .doesNotThrowAnyException();
        assertThat(GuiThemeManager.activeThemeId()).isEqualTo("system");
        assertThat(GuiThemeManager.configuredThemeId()).isEqualTo("dark");
    }

    @Test
    @DisplayName("坏插件枚举主题抛出非致命 Error 时隔离并保留正常主题")
    void pluginThemeEnumerationErrorIsIsolatedFromHealthyPlugin() throws Exception {
        PixivFeaturePlugin broken = new TestPlugin("broken-theme", List.of()) {
            @Override
            public List<GuiThemeContribution> guiThemes() {
                throw new AssertionError("theme enumeration boom");
            }
        };
        TestPlugin healthy = new TestPlugin(
                "healthy-theme", List.of(contribution("healthy", GuiThemeAppearance.DARK)));

        assertThatCode(() -> SwingUtilities.invokeAndWait(() -> GuiThemeManager.applyBeforeFirstWindow(
                null, "healthy",
                List.of(new GuiThemeManager.ThemePluginSource("broken-theme", broken), source(healthy)))))
                .doesNotThrowAnyException();

        assertThat(GuiThemeManager.activeThemeId()).isEqualTo("healthy");
        assertThat(GuiThemeManager.choices(Locale.US, "Unavailable", "System fallback"))
                .extracting(GuiThemeManager.ThemeChoice::id)
                .containsExactly("healthy");
    }

    @Test
    @DisplayName("主题聚合只使用宿主盖章 id 不重读插件自报 id")
    void themeAggregationUsesCapturedPluginId() throws Exception {
        GuiThemeContribution contribution = contribution("trusted", GuiThemeAppearance.DARK);
        PixivFeaturePlugin plugin = new PixivFeaturePlugin() {
            @Override
            public String id() {
                throw new IllegalStateException("id must not be read after discovery");
            }

            @Override
            public String displayName() {
                return "trusted.name";
            }

            @Override
            public String description() {
                return "trusted.description";
            }

            @Override
            public PluginKind kind() {
                return PluginKind.FEATURE;
            }

            @Override
            public List<GuiThemeContribution> guiThemes() {
                return List.of(contribution);
            }
        };

        assertThatCode(() -> SwingUtilities.invokeAndWait(() -> GuiThemeManager.applyBeforeFirstWindow(
                null, "trusted", List.of(new GuiThemeManager.ThemePluginSource("trusted-owner", plugin)))))
                .doesNotThrowAnyException();
        assertThat(GuiThemeManager.activeThemeId()).isEqualTo("trusted");
    }

    private static GuiThemeContribution contribution(String id, GuiThemeAppearance appearance) {
        return new GuiThemeContribution(id, locale -> id, appearance, () -> {
        });
    }

    private static GuiThemeManager.ThemePluginSource source(TestPlugin plugin) {
        return new GuiThemeManager.ThemePluginSource(plugin.id(), plugin);
    }

    private static class TestPlugin implements PixivFeaturePlugin {
        private final String id;
        private final List<GuiThemeContribution> themes;

        private TestPlugin(String id, List<GuiThemeContribution> themes) {
            this.id = id;
            this.themes = themes;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String displayName() {
            return id;
        }

        @Override
        public String description() {
            return id;
        }

        @Override
        public PluginKind kind() {
            return PluginKind.FEATURE;
        }

        @Override
        public List<GuiThemeContribution> guiThemes() {
            return themes;
        }
    }
}

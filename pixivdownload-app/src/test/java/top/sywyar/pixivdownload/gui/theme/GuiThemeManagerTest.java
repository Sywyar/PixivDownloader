package top.sywyar.pixivdownload.gui.theme;

import org.junit.jupiter.api.AfterEach;
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

class GuiThemeManagerTest {

    @AfterEach
    void tearDown() {
        GuiThemeManager.resetForTests();
    }

    @Test
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
                null, "moonlight", List.of(plugin)));

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
    void duplicateThemeIdIsRejectedAndFallsBack() throws Exception {
        GuiThemeContribution first = contribution("moonlight", GuiThemeAppearance.DARK);
        GuiThemeContribution second = contribution("moonlight", GuiThemeAppearance.DARK);

        SwingUtilities.invokeAndWait(() -> GuiThemeManager.applyBeforeFirstWindow(
                null, "moonlight",
                List.of(new TestPlugin("one", List.of(first)), new TestPlugin("two", List.of(second)))));

        assertThat(GuiThemeManager.activeThemeId()).isEqualTo("system");
        assertThat(GuiThemeManager.choices(Locale.US, "Unavailable", "System fallback"))
                .extracting(GuiThemeManager.ThemeChoice::id)
                .containsExactly("moonlight", "system");
    }

    @Test
    void nullGuiThemesFromPluginFallsBackWithoutOverwritingConfiguredTheme() throws Exception {
        Path config = Files.createTempFile("pixiv-theme-", ".yaml");
        Files.writeString(config, "app.theme: moonlight\n");
        TestPlugin brokenPlugin = new TestPlugin("broken-theme", null);

        assertThatCode(() -> SwingUtilities.invokeAndWait(() -> GuiThemeManager.applyBeforeFirstWindow(
                config, GuiThemeManager.readPersistedThemeId(config), List.of(brokenPlugin))))
                .doesNotThrowAnyException();

        assertThat(GuiThemeManager.configuredThemeId()).isEqualTo("moonlight");
        assertThat(GuiThemeManager.activeThemeId()).isEqualTo("system");
        assertThat(Files.readString(config)).contains("app.theme: moonlight");
        assertThat(GuiThemeManager.choices(Locale.US, "Unavailable", "System fallback"))
                .extracting(GuiThemeManager.ThemeChoice::id)
                .containsExactly("moonlight", "system");
    }

    @Test
    void contributionFailureDoesNotEscapeFirstWindowBootstrap() throws Exception {
        GuiThemeContribution failing = new GuiThemeContribution(
                "dark",
                locale -> "Dark",
                GuiThemeAppearance.DARK,
                () -> {
                    throw new IllegalStateException("boom");
                });

        assertThatCode(() -> SwingUtilities.invokeAndWait(() -> GuiThemeManager.applyBeforeFirstWindow(
                null, "dark", List.of(new TestPlugin("gui-theme", List.of(failing))))))
                .doesNotThrowAnyException();
        assertThat(GuiThemeManager.activeThemeId()).isEqualTo("system");
        assertThat(GuiThemeManager.configuredThemeId()).isEqualTo("dark");
    }

    private static GuiThemeContribution contribution(String id, GuiThemeAppearance appearance) {
        return new GuiThemeContribution(id, locale -> id, appearance, () -> {
        });
    }

    private record TestPlugin(String id, List<GuiThemeContribution> themes) implements PixivFeaturePlugin {
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

package top.sywyar.pixivdownload.gui.panel;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.gui.theme.GuiThemeManager;
import top.sywyar.pixivdownload.plugin.api.gui.GuiThemeAppearance;
import top.sywyar.pixivdownload.plugin.api.gui.GuiThemeChangeListener;
import top.sywyar.pixivdownload.plugin.api.gui.GuiThemeContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiThemeListenerSession;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;

import javax.swing.JComboBox;
import javax.swing.SwingUtilities;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class StatusPanelThemeModelTest {

    @BeforeEach
    void setUp() {
        resetThemeManager();
    }

    @AfterEach
    void tearDown() {
        resetThemeManager();
    }

    @Test
    void dynamicOptionsUseContributionLabelsAndSelectConfiguredTheme() {
        GuiThemeContribution system = theme("system", GuiThemeAppearance.SYSTEM, "System");
        GuiThemeContribution moonlight = theme("moonlight", GuiThemeAppearance.DARK, "Moonlight");
        GuiThemeManager.applyBeforeFirstWindow(null, "moonlight",
                List.of(new TestPlugin("gui-theme", List.of(system, moonlight))));

        JComboBox<StatusPanelThemeOption> combo = new JComboBox<>();
        StatusPanelThemeModel.refreshOptions(combo, Locale.US, "Unavailable", "System fallback",
                GuiThemeManager.configuredThemeId());

        assertThat(items(combo)).extracting(StatusPanelThemeOption::id)
                .containsExactly("system", "moonlight");
        assertThat(items(combo)).extracting(StatusPanelThemeOption::label)
                .containsExactly("System/en", "Moonlight/en");
        assertThat((StatusPanelThemeOption) combo.getSelectedItem())
                .extracting(StatusPanelThemeOption::id)
                .isEqualTo("moonlight");
        assertThat(GuiThemeManager.isCurrentDark()).isTrue();
    }

    @Test
    void unavailableConfiguredThemeStaysVisibleWithoutOverwritingSelection() {
        GuiThemeManager.applyBeforeFirstWindow(null, "moonlight", List.of());

        JComboBox<StatusPanelThemeOption> combo = new JComboBox<>();
        StatusPanelThemeModel.refreshOptions(combo, Locale.US, "Unavailable", "System fallback",
                GuiThemeManager.configuredThemeId());
        StatusPanelThemeModel.syncSelection(combo);

        assertThat(items(combo)).extracting(StatusPanelThemeOption::id)
                .containsExactly("moonlight", "system");
        assertThat(items(combo)).extracting(StatusPanelThemeOption::unavailable)
                .containsExactly(true, false);
        assertThat((StatusPanelThemeOption) combo.getSelectedItem())
                .extracting(StatusPanelThemeOption::id)
                .isEqualTo("moonlight");
        assertThat(GuiThemeManager.configuredThemeId()).isEqualTo("moonlight");
        assertThat(GuiThemeManager.activeThemeId()).isEqualTo("system");
    }

    @Test
    void lightDarkChangeUpdatesManagerStateAndListenerSessionCanBeReleased() throws Exception {
        AtomicReference<GuiThemeChangeListener> listenerRef = new AtomicReference<>();
        AtomicInteger closed = new AtomicInteger();
        GuiThemeContribution dark = new GuiThemeContribution(
                "dark",
                locale -> "Dark",
                GuiThemeAppearance.DARK,
                () -> {
                },
                listener -> {
                    listenerRef.set(listener);
                    return closed::incrementAndGet;
                });
        GuiThemeManager.applyBeforeFirstWindow(null, "dark",
                List.of(new TestPlugin("gui-theme", List.of(dark))));

        assertThat(GuiThemeManager.isCurrentDark()).isTrue();
        listenerRef.get().appearanceChanged(GuiThemeAppearance.LIGHT);
        SwingUtilities.invokeAndWait(() -> {
        });
        assertThat(GuiThemeManager.isCurrentDark()).isFalse();

        GuiThemeListenerSession next = StatusPanel.closeThemeListenerSession(closed::incrementAndGet);
        assertThat(closed).hasValue(1);
        next.close();
        assertThat(closed).hasValue(1);
    }

    private static GuiThemeContribution theme(String id, GuiThemeAppearance appearance, String label) {
        return new GuiThemeContribution(id, locale -> label + "/" + locale.getLanguage(), appearance, () -> {
        });
    }

    private static List<StatusPanelThemeOption> items(JComboBox<StatusPanelThemeOption> combo) {
        return java.util.stream.IntStream.range(0, combo.getItemCount())
                .mapToObj(combo::getItemAt)
                .toList();
    }

    private static void resetThemeManager() {
        try {
            Method reset = GuiThemeManager.class.getDeclaredMethod("resetForTests");
            reset.setAccessible(true);
            reset.invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to reset GUI theme manager test state", e);
        }
    }

    private record TestPlugin(String id, List<GuiThemeContribution> guiThemes) implements PixivFeaturePlugin {
        @Override
        public String displayName() {
            return "test.name";
        }

        @Override
        public String description() {
            return "test.description";
        }

        @Override
        public PluginKind kind() {
            return PluginKind.FEATURE;
        }
    }
}

package top.sywyar.pixivdownload.gui.theme;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiPluginSnapshot;
import top.sywyar.pixivdownload.plugin.api.gui.GuiThemeAppearance;
import top.sywyar.pixivdownload.plugin.api.gui.GuiThemeChangeListener;
import top.sywyar.pixivdownload.plugin.api.gui.GuiThemeContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiThemeListenerSession;

import javax.swing.SwingUtilities;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    void missingThemeFallsBackWithoutOverwritingThePreference() {
        MemoryConfigFile config = new MemoryConfigFile(Map.of("app.theme", "moonlight"));

        GuiThemeManager.applyBeforeFirstWindow(config, "moonlight", List.of());

        assertThat(GuiThemeManager.configuredThemeId()).isEqualTo("moonlight");
        assertThat(GuiThemeManager.activeThemeId()).isEqualTo("system");
        assertThat(config.values).containsEntry("app.theme", "moonlight");
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
                "moonlight", locale -> "Moonlight", GuiThemeAppearance.DARK,
                () -> applied.set(SwingUtilities.isEventDispatchThread()), listener -> {
                    pluginListener.set(listener);
                    return listenerClosed::incrementAndGet;
                });
        GuiThemeListenerSession session = GuiThemeManager.addChangeListener(managerNotifications::incrementAndGet);

        GuiThemeManager.applyBeforeFirstWindow(new MemoryConfigFile(Map.of()), "moonlight",
                List.of(snapshot("gui-swing", List.of(contribution))));

        assertThat(applied).isTrue();
        assertThat(GuiThemeManager.activeThemeId()).isEqualTo("moonlight");
        assertThat(GuiThemeManager.isCurrentDark()).isTrue();
        assertThat(GuiThemeManager.choices(Locale.US, "Unavailable", "System fallback"))
                .extracting(GuiThemeManager.ThemeChoice::displayName)
                .containsExactly("Moonlight");

        pluginListener.get().appearanceChanged(GuiThemeAppearance.LIGHT);
        SwingUtilities.invokeAndWait(() -> { });
        assertThat(GuiThemeManager.isCurrentDark()).isFalse();
        assertThat(managerNotifications.get()).isGreaterThanOrEqualTo(2);

        session.close();
        GuiThemeManager.resetForTests();
        assertThat(listenerClosed).hasValue(1);
    }

    @Test
    void duplicateThemeIdIsRejectedAndFallsBack() {
        GuiThemeContribution first = contribution("moonlight", GuiThemeAppearance.DARK);
        GuiThemeContribution second = contribution("moonlight", GuiThemeAppearance.DARK);

        GuiThemeManager.applyBeforeFirstWindow(new MemoryConfigFile(Map.of()), "moonlight", List.of(
                snapshot("one", List.of(first)), snapshot("two", List.of(second))));

        assertThat(GuiThemeManager.activeThemeId()).isEqualTo("system");
        assertThat(GuiThemeManager.choices(Locale.US, "Unavailable", "System fallback"))
                .extracting(GuiThemeManager.ThemeChoice::id)
                .containsExactly("moonlight", "system");
    }

    @Test
    void contributionFailureDoesNotEscapeFirstWindowBootstrap() {
        GuiThemeContribution failing = new GuiThemeContribution(
                "dark", locale -> "Dark", GuiThemeAppearance.DARK,
                () -> { throw new AssertionError("boom"); });

        assertThatCode(() -> GuiThemeManager.applyBeforeFirstWindow(
                new MemoryConfigFile(Map.of()), "dark", List.of(snapshot("gui-swing", List.of(failing)))))
                .doesNotThrowAnyException();
        assertThat(GuiThemeManager.activeThemeId()).isEqualTo("system");
        assertThat(GuiThemeManager.configuredThemeId()).isEqualTo("dark");
    }

    private static GuiThemeContribution contribution(String id, GuiThemeAppearance appearance) {
        return new GuiThemeContribution(id, locale -> id, appearance, () -> { });
    }

    private static DesktopUiPluginSnapshot snapshot(String id, List<GuiThemeContribution> themes) {
        return new DesktopUiPluginSnapshot(id, false, id, 1L, false, null, "", themes,
                List.of(), List.of(), List.of(), List.of());
    }

    private static final class MemoryConfigFile implements DesktopUiHost.ConfigFile {
        private final Map<String, String> values = new LinkedHashMap<>();

        private MemoryConfigFile(Map<String, String> values) {
            this.values.putAll(values);
        }

        @Override
        public Map<String, String> readAll(Collection<String> keys) {
            Map<String, String> result = new LinkedHashMap<>();
            keys.forEach(key -> {
                if (values.containsKey(key)) result.put(key, values.get(key));
            });
            return result;
        }

        @Override public void writeAll(Map<String, String> updates) { values.putAll(updates); }
        @Override public void removeAll(Collection<String> keys) { keys.forEach(values::remove); }
        @Override public DesktopUiHost.ConfigSnapshot snapshot() { return new DesktopUiHost.ConfigSnapshot(true, List.of()); }
        @Override public void restore(DesktopUiHost.ConfigSnapshot snapshot) { }
    }
}

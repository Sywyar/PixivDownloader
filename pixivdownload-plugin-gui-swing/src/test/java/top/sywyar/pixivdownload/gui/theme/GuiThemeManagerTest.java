package top.sywyar.pixivdownload.gui.theme;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
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

        GuiThemeManager.applyBeforeFirstWindow(
                GuiThemeManager.readPersistedThemeId(config), List.of());

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

        GuiThemeManager.applyBeforeFirstWindow("moonlight",
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

        GuiThemeManager.applyBeforeFirstWindow("moonlight", List.of(
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
                "dark", List.of(snapshot("gui-swing", List.of(failing)))))
                .doesNotThrowAnyException();
        assertThat(GuiThemeManager.activeThemeId()).isEqualTo("system");
        assertThat(GuiThemeManager.configuredThemeId()).isEqualTo("dark");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("重新应用同一主题后忽略旧监听回调，包括关闭失败时已排队的通知")
    void reappliedThemeRejectsOldListener(boolean closeFails) throws Exception {
        AtomicReference<GuiThemeChangeListener> listener = new AtomicReference<>();
        AtomicInteger closed = new AtomicInteger();
        AtomicInteger notifications = new AtomicInteger();
        GuiThemeContribution theme = new GuiThemeContribution(
                "dark", locale -> "Dark", GuiThemeAppearance.DARK, () -> { }, callback -> {
                    listener.set(callback);
                    return () -> {
                        closed.incrementAndGet();
                        callback.appearanceChanged(GuiThemeAppearance.LIGHT);
                        if (closeFails) throw new IllegalStateException("close failed");
                    };
                });
        GuiThemeManager.addChangeListener(notifications::incrementAndGet);
        GuiThemeManager.applyBeforeFirstWindow("dark",
                List.of(snapshot("theme", List.of(theme))));

        SwingUtilities.invokeAndWait(() -> {
            listener.get().appearanceChanged(GuiThemeAppearance.LIGHT);
            GuiThemeManager.applyThemeId("dark");
        });
        SwingUtilities.invokeAndWait(() -> { });

        assertThat(closed).hasValue(1);
        assertThat(GuiThemeManager.activeThemeId()).isEqualTo("dark");
        assertThat(GuiThemeManager.isCurrentDark()).isTrue();
        assertThat(notifications).hasValue(2);
        listener.get().appearanceChanged(GuiThemeAppearance.LIGHT);
        SwingUtilities.invokeAndWait(() -> { });
        assertThat(GuiThemeManager.isCurrentDark()).isFalse();
        assertThat(notifications).hasValue(3);
    }

    @Test
    @DisplayName("主题监听启动失败后丢弃该监听已排队的外观通知")
    void failedListenerCannotChangeAppliedAppearance() throws Exception {
        GuiThemeContribution theme = new GuiThemeContribution(
                "dark", locale -> "Dark", GuiThemeAppearance.DARK, () -> { }, callback -> {
                    callback.appearanceChanged(GuiThemeAppearance.LIGHT);
                    throw new IllegalStateException("listener failed");
                });

        GuiThemeManager.applyBeforeFirstWindow("dark",
                List.of(snapshot("theme", List.of(theme))));
        SwingUtilities.invokeAndWait(() -> { });

        assertThat(GuiThemeManager.activeThemeId()).isEqualTo("dark");
        assertThat(GuiThemeManager.isCurrentDark()).isTrue();
    }

    @Test
    @DisplayName("监听启动时同步报告的外观仍由当前监听正常更新")
    void currentListenerPublishesInitialAppearance() throws Exception {
        GuiThemeContribution theme = new GuiThemeContribution(
                "dark", locale -> "Dark", GuiThemeAppearance.DARK, () -> { }, callback -> {
                    callback.appearanceChanged(GuiThemeAppearance.LIGHT);
                    return GuiThemeListenerSession.none();
                });

        GuiThemeManager.applyBeforeFirstWindow("dark", List.of(snapshot("theme", List.of(theme))));
        SwingUtilities.invokeAndWait(() -> { });

        assertThat(GuiThemeManager.activeThemeId()).isEqualTo("dark");
        assertThat(GuiThemeManager.isCurrentDark()).isFalse();
    }

    @Test
    @DisplayName("系统回退后旧主题的迟到通知不能覆盖当前外观")
    void fallbackRejectsOldListener() throws Exception {
        AtomicReference<GuiThemeChangeListener> listener = new AtomicReference<>();
        GuiThemeContribution theme = new GuiThemeContribution(
                "dark", locale -> "Dark", GuiThemeAppearance.DARK, () -> { }, callback -> {
                    listener.set(callback);
                    return GuiThemeListenerSession.none();
                });
        GuiThemeManager.applyBeforeFirstWindow("dark",
                List.of(snapshot("theme", List.of(theme))));
        AtomicBoolean fallbackDark = new AtomicBoolean();

        SwingUtilities.invokeAndWait(() -> {
            GuiThemeManager.applyThemeId("missing");
            fallbackDark.set(GuiThemeManager.isCurrentDark());
            listener.get().appearanceChanged(fallbackDark.get()
                    ? GuiThemeAppearance.LIGHT : GuiThemeAppearance.DARK);
        });
        SwingUtilities.invokeAndWait(() -> { });

        assertThat(GuiThemeManager.activeThemeId()).isEqualTo("system");
        assertThat(GuiThemeManager.isCurrentDark()).isEqualTo(fallbackDark.get());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("关闭后丢弃已排队的主题应用，重新启动仍可应用主题")
    void closeDiscardsPendingThemeChanges(boolean emptyThemes) throws Exception {
        AtomicInteger applied = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        AtomicInteger notifications = new AtomicInteger();
        var theme = new GuiThemeContribution(
                "dark",
                locale -> "Dark",
                GuiThemeAppearance.DARK,
                applied::incrementAndGet,
                listener -> closed::incrementAndGet
        );
        List<DesktopUiPluginSnapshot> plugins = emptyThemes
                ? List.of() : List.of(snapshot("theme", List.of(theme)));
        GuiThemeManager.applyBeforeFirstWindow("dark", plugins);
        SwingUtilities.invokeAndWait(() -> {
            try {
                CompletableFuture.runAsync(() -> GuiThemeManager.applyThemeId("dark"))
                        .get(5, TimeUnit.SECONDS);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError(failure);
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
            GuiThemeManager.close();
            GuiThemeManager.applyThemeId("dark");
            GuiThemeManager.applyBeforeFirstWindow("dark", plugins);
            GuiThemeManager.addChangeListener(notifications::incrementAndGet);
        });
        SwingUtilities.invokeAndWait(() -> { });

        assertThat(applied).hasValue(emptyThemes ? 0 : 2);
        assertThat(closed).hasValue(emptyThemes ? 0 : 1);
        assertThat(notifications).hasValue(0);
        assertThat(GuiThemeManager.activeThemeId()).isEqualTo(emptyThemes ? "system" : "dark");
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

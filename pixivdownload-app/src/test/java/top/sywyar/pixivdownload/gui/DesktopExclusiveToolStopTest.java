package top.sywyar.pixivdownload.gui;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;
import top.sywyar.pixivdownload.gui.panel.ToolsPanel;
import top.sywyar.pixivdownload.gui.tools.FolderChecker;
import top.sywyar.pixivdownload.guiswing.SwingHost;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiContext;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiText;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DesktopExclusiveToolStopTest {
    private record Request(Runnable stopped, Consumer<Throwable> failed) {}

    @ParameterizedTest
    @CsvSource({"false,false,false", "false,true,false", "true,false,false", "true,true,false",
            "true,false,true", "true,true,true"})
    void swingToolsWaitForTheirOwnStopResult(boolean migration, boolean initiallyRunning, boolean preparationFails) throws Exception {
        var config = Path.of("test-config.yaml");
        var previous = SwingHost.installed() ? SwingHost.context() : null;
        var host = mock(DesktopUiHost.class);
        var locale = new DesktopUiHost.UiLocale("en-US", "English", "en");
        when(host.resolveLocale(any())).thenReturn(new DesktopUiHost.UiLocaleResolution(locale, List.of(locale)));
        var messages = top.sywyar.pixivdownload.i18n.TestI18nBeans.appMessages();
        when(host.message(anyString(), any(Object[].class))).thenAnswer(call ->
                messages.get(java.util.Locale.ENGLISH, (String) call.getArgument(0), (Object[]) call.getRawArguments()[1]));
        when(host.readDownloadRootFromConfig(any(), any())).thenReturn(".");
        when(host.defaultBackfillOptions()).thenReturn(new DesktopUiHost.BackfillOptions("data/test.db", "", 8080, false, 1000, 0, false));
        var running = new DesktopUiHost.BackendSnapshot(DesktopUiHost.BackendState.RUNNING, null);
        var state = new AtomicReference<>(running);
        var notify = new AtomicReference<Consumer<DesktopUiHost.BackendSnapshot>>();
        when(host.backendSnapshot()).thenAnswer(ignored -> state.get());
        when(host.subscribeBackend(any())).thenAnswer(call -> { notify.set(call.getArgument(0)); return (AutoCloseable) () -> {}; });
        var requests = new CopyOnWriteArrayList<Request>();
        when(host.stopBackend(any(), any())).thenAnswer(call -> {
            requests.add(new Request(call.getArgument(0), call.getArgument(1)));
            return requests.size() > 1;
        });
        when(host.openToolLog(anyString())).thenReturn(mock(DesktopUiHost.ToolLogSession.class));
        when(host.startBackend(any())).thenReturn(true);
        if (preparationFails) when(host.countMigrationCandidates(any())).thenThrow(new IllegalStateException("count failed"));
        var errorDialog = new AtomicReference<org.mockito.MockedStatic<GuiErrorDialog>>();
        SwingHost.install(new DesktopUiContext(false, 8080, ".", config, "gui-swing", host, List.of(), List::of,
                DesktopUiText::fallback, () -> "system"));
        var panel = new AtomicReference<ToolsPanel>();
        try {
            SwingUtilities.invokeAndWait(() -> panel.set(new ToolsPanel(config)));
            var button = (JButton) ReflectionTestUtils.getField(panel.get(), migration ? "migrationRunButton" : "folderCheckerButton");
            var status = (JLabel) ReflectionTestUtils.getField(panel.get(), migration ? "migrationStatusLabel" : "backfillStatusLabel");
            SwingUtilities.invokeAndWait(() -> {
                button.doClick();
                assertThat(button.isEnabled()).isTrue();
                button.doClick();
                assertThat(button.isEnabled()).isFalse();
                state.set(new DesktopUiHost.BackendSnapshot(DesktopUiHost.BackendState.STOPPING, null));
                notify.get().accept(state.get());
                var failure = new IllegalStateException("writer still active");
                state.set(new DesktopUiHost.BackendSnapshot(DesktopUiHost.BackendState.FAILED, failure));
                notify.get().accept(state.get());
                requests.get(1).failed().accept(failure);
            });
            verify(host, never()).countMigrationCandidates(any());
            SwingUtilities.invokeAndWait(() -> {
                assertThat(status.getText()).contains("writer still active");
                verify(host, never()).startBackend(any());
                state.set(initiallyRunning ? running : new DesktopUiHost.BackendSnapshot(DesktopUiHost.BackendState.STOPPED, null));
                notify.get().accept(state.get());
                errorDialog.set(mockStatic(GuiErrorDialog.class));
                button.doClick();
                notify.get().accept(new DesktopUiHost.BackendSnapshot(DesktopUiHost.BackendState.FAILED, null));
                assertThat(button.isEnabled()).isFalse();
                var onClose = new AtomicReference<Runnable>();
                try (var window = mockConstruction(FolderChecker.class, (checker, construction) ->
                        onClose.set((Runnable) construction.arguments().get(1)))) {
                    requests.get(2).stopped().run();
                    if (!migration) {
                        assertThat(window.constructed()).hasSize(1);
                        verify(window.constructed().getFirst()).showWindow();
                        onClose.get().run();
                    }
                }
            });
            if (migration) {
                org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).untilAsserted(() ->
                        SwingUtilities.invokeAndWait(() -> assertThat(ReflectionTestUtils.getField(panel.get(), "migrationRunning")).isEqualTo(false)));
                verify(host, times(preparationFails ? 0 : 1)).runMigration(any(), any());
            }
            verify(host, times(initiallyRunning ? 1 : 0)).startBackend(any());
        } finally {
            if (errorDialog.get() != null) SwingUtilities.invokeAndWait(errorDialog.get()::close);
            if (panel.get() != null) SwingUtilities.invokeAndWait(panel.get()::dispose);
            ReflectionTestUtils.setField(SwingHost.class, "context", previous);
        }
    }
}

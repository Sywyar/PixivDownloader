package top.sywyar.pixivdownload.gui.panel;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import top.sywyar.pixivdownload.i18n.MessageBundles;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopEnvironmentTransfer;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;

import javax.swing.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RestoreDialogMessageTest {
    @ParameterizedTest
    @ValueSource(strings = {"transfer.database-path-unrepresentable", "transfer.database-path-collision",
            "transfer.database-page-outside-root", "transfer.target-changed", "transfer.restore-failed"})
    void showsLocalizedFailureAndSearchableJobId(String code) throws Exception {
        String job = UUID.randomUUID().toString();
        var captured = new AtomicReference<List<Object>>();
        var host = mock(DesktopUiHost.class, invocation -> switch (invocation.getMethod().getName()) {
            case "environmentTransfer" -> Optional.of(mock(DesktopEnvironmentTransfer.class));
            case "message" -> MessageBundles.get(Locale.US, (String) invocation.getRawArguments()[0],
                    (Object[]) invocation.getRawArguments()[1]);
            default -> RETURNS_DEFAULTS.answer(invocation);
        });
        var overview = new EnvironmentTransferDialog.Overview(
                new DesktopEnvironmentTransfer.RestoreState(DesktopEnvironmentTransfer.Status.FAILED, code, job),
                new DesktopEnvironmentTransfer.Installation(DesktopEnvironmentTransfer.InstallationKind.UNSUPPORTED, "", ""));
        SwingUtilities.invokeAndWait(() -> {
            try (var dialog = mockStatic(JOptionPane.class, invocation -> {
                if (invocation.getMethod().getName().equals("showOptionDialog")) {
                    captured.set(Arrays.asList((Object[]) invocation.getArgument(1)));
                    return JOptionPane.CLOSED_OPTION;
                }
                return RETURNS_DEFAULTS.answer(invocation);
            })) {
                new EnvironmentTransferDialog(null, host, () -> false).chooseOperation(overview);
            }
        });
        assertThat(captured.get()).contains(MessageBundles.get(Locale.US, "transfer.status.failed"),
                MessageBundles.get(Locale.US, "transfer.restore-log-hint", job));
        if (code.equals("transfer.restore-failed")) {
            assertThat(captured.get()).doesNotContain(MessageBundles.get(Locale.US, "transfer.database-path-unrepresentable"));
        } else {
            assertThat(MessageBundles.get(Locale.US, code)).isNotEqualTo(code);
            assertThat(captured.get()).contains(MessageBundles.get(Locale.US, code));
        }
    }
}

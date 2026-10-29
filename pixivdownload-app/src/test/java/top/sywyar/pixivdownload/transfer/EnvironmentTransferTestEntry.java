package top.sywyar.pixivdownload.transfer;

import top.sywyar.pixivdownload.plugin.api.gui.*;
import java.nio.file.Path;
import java.util.*;

/** 真正退出准备进程，再由持有实例锁的新进程执行导入。 */
public final class EnvironmentTransferTestEntry {
    public static void main(String[] args) throws Exception {
        top.sywyar.pixivdownload.common.Utf8ConsoleStreams.install();
        try (var instance = top.sywyar.pixivdownload.gui.SingleInstanceManager.acquire()) {
            if (instance == null) throw new IllegalStateException("INSTANCE_BUSY");
            if (args[0].equals("run")) { EnvironmentTransferService.runPending(); return; }
            var host = org.mockito.Mockito.mock(DesktopUiHost.class);
            org.mockito.Mockito.when(host.applicationVersion()).thenReturn("fixture");
            org.mockito.Mockito.when(host.coreConfigFields()).thenReturn(List.of(new GuiConfigFieldContribution(
                    "server.port", "server", "fixture", GuiConfigFieldType.PORT, "", 1)));
            var transfer = new EnvironmentTransferService(host, List::of, () -> !args[0].equals("restart-fails"));
            var preview = transfer.previewConfiguration(Path.of(args[1]), null);
            if (!args[0].equals("preview")) transfer.importConfiguration(preview.token(),
                    args[0].equals("invalid-selection") ? Set.of(new DesktopEnvironmentTransfer.FieldKey("host", "unknown"))
                            : preview.changes().stream().map(DesktopEnvironmentTransfer.Change::field).collect(java.util.stream.Collectors.toSet()),
                    Map.of(), null, false, Set.of(), Map.of());
        }
    }
}

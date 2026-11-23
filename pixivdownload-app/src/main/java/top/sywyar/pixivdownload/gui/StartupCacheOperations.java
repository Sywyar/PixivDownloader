package top.sywyar.pixivdownload.gui;

import top.sywyar.pixivdownload.startup.DesktopStartupCacheService;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiToolHost.ToolId;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiToolHost.ToolOutcome;
import java.util.Optional;

/** 桌面与 HTTP 共享同一进程训练器；后端重启不创建第二个执行器。 */
final class StartupCacheOperations {
    private StartupCacheOperations() {}
    private static final class ProcessInstance {
        private static final Optional<DesktopStartupCacheService> SERVICE = DesktopStartupCacheService.create((result, started) -> {
            var outcome = switch (result.state()) {
                case SUCCEEDED -> ToolOutcome.SUCCEEDED;
                case CANCELLED -> ToolOutcome.CANCELLED;
                default -> ToolOutcome.FAILED;
            };
            var history = DesktopToolHistory.shared();
            history.record(ToolId.STARTUP_CACHE, outcome, started, null, null, null, null, result.executionId(), null);
        });
    }
    static Optional<DesktopStartupCacheService> service() { return ProcessInstance.SERVICE; }
}

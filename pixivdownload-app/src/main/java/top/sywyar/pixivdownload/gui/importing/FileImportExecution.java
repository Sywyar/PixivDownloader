package top.sywyar.pixivdownload.gui.importing;

import top.sywyar.pixivdownload.plugin.api.web.HttpApiNullable;

import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiText;
import top.sywyar.pixivdownload.plugin.api.gui.importing.DesktopFileImportTool.*;

import java.util.Optional;
import java.util.UUID;

/** 桌面和 HTTP 共用当前导入身份，检查期间不接纳另一轮启动。 */
final class FileImportExecution implements Source {
    private final Source delegate;
    private UUID executionId;
    private boolean inspecting;
    private Thread inspectThread;
    private boolean inspectInterrupted;

    FileImportExecution(Source delegate) { this.delegate = delegate; }
    record Snapshot(@HttpApiNullable UUID executionId, boolean inspecting, Status status, @HttpApiNullable Completion completion) {}
    synchronized Snapshot snapshot() { return new Snapshot(executionId, inspecting, delegate.status(), delegate.pendingCompletion().orElse(null)); }
    synchronized boolean cancel(UUID expected) {
        if (expected == null || !expected.equals(executionId)) return false;
        cancelCurrent();
        return true;
    }
    @Override public Description description() { return delegate.description(); }
    @Override public Status status() { return delegate.status(); }
    @Override public synchronized void cancel() { cancelCurrent(); }
    private void cancelCurrent() {
        if (inspectThread != null) {
            inspectInterrupted = true;
            inspectThread.interrupt();
        }
        delegate.cancel();
    }
    @Override public Optional<Completion> pendingCompletion() { return delegate.pendingCompletion(); }
    @Override public void acknowledgeCompletion(String id) { delegate.acknowledgeCompletion(id); }

    @Override public Result<Preview> inspect(Request request) {
        synchronized (this) {
            if (inspecting || delegate.status().active()) return busy();
            inspecting = true;
            inspectThread = Thread.currentThread();
            inspectInterrupted = false;
            executionId = UUID.randomUUID();
        }
        try { return delegate.inspect(request); }
        finally { synchronized (this) {
            inspecting = false;
            inspectThread = null;
            if (inspectInterrupted) Thread.interrupted();
        } }
    }

    @Override public synchronized Result<Status> start(String token, String credential) {
        if (inspecting) return busy();
        var result = delegate.start(token, credential);
        if (result.error() == null) executionId = UUID.randomUUID();
        return result;
    }

    private static <T> Result<T> busy() {
        return Result.failure(DesktopUiText.key("gui.message.backend-busy"));
    }
}

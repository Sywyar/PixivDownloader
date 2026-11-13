package top.sywyar.pixivdownload.gui.media;

import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiText;
import top.sywyar.pixivdownload.plugin.api.gui.media.DesktopMediaTool.*;

import java.util.UUID;
import java.util.function.Consumer;

/** 同一 publication 的桌面与 HTTP 调用共用执行身份，旧取消不得命中新任务。 */
public class MediaToolExecution implements Source {
    protected final Source delegate;
    private UUID executionId;
    private boolean previewing;
    private Thread previewThread;
    private boolean previewInterrupted;

    static MediaToolExecution wrap(Source source) {
        return source instanceof CompletionSource tracked ? new Tracked(tracked) : new MediaToolExecution(source);
    }

    private MediaToolExecution(Source delegate) { this.delegate = delegate; }

    record Snapshot(UUID executionId, boolean previewing, Status status) {}

    synchronized Snapshot snapshot() { return new Snapshot(executionId, previewing, delegate.status()); }

    synchronized boolean cancel(UUID expected) {
        if (expected == null || !expected.equals(executionId)) return false;
        cancelCurrent();
        return true;
    }

    @Override public Description description() { return delegate.description(); }
    @Override public Status status() { return delegate.status(); }
    @Override public Result<Report> capabilities() { return delegate.capabilities(); }
    @Override public synchronized void cancel() { cancelCurrent(); }

    private void cancelCurrent() {
        if (previewThread != null) {
            previewInterrupted = true;
            previewThread.interrupt();
        }
        delegate.cancel();
    }

    @Override public Result<Preview> preview(Request request) {
        synchronized (this) {
            String state = delegate.status().state();
            if (previewing || "running".equals(state) || "scanning".equals(state) || "cancelling".equals(state)) return busy();
            previewing = true;
            previewThread = Thread.currentThread();
            previewInterrupted = false;
            // 预览也会改变提供者的当前工作，先失效旧执行的取消权限。
            executionId = UUID.randomUUID();
        }
        try { return delegate.preview(request); }
        finally { synchronized (this) {
            previewing = false;
            previewThread = null;
            if (previewInterrupted) Thread.interrupted();
        } }
    }

    @Override public synchronized Result<Status> start(String token) {
        return startExecution(() -> delegate.start(token));
    }

    protected synchronized Result<Status> startExecution(java.util.function.Supplier<Result<Status>> start) {
        return startExecution(UUID.randomUUID(), start);
    }

    /** 历史记录和当前任务共用同一次执行的身份。 */
    public synchronized Result<Status> start(String token, UUID id, Consumer<Status> completion) {
        return startExecution(id, () -> ((CompletionSource) delegate).start(token, completion));
    }

    private synchronized Result<Status> startExecution(UUID id, java.util.function.Supplier<Result<Status>> start) {
        if (previewing) return busy();
        UUID previous = executionId;
        executionId = id;
        try {
            var result = start.get();
            if (result.error() != null) executionId = previous;
            return result;
        } catch (RuntimeException | Error failure) {
            executionId = previous;
            throw failure;
        }
    }

    private static <T> Result<T> busy() {
        return new Result<>(null, DesktopUiText.key("gui.message.backend-busy"));
    }

    private static final class Tracked extends MediaToolExecution implements CompletionSource {
        private Tracked(CompletionSource source) { super(source); }

        @Override public Result<Status> start(String token, Consumer<Status> completion) {
            return startExecution(() -> ((CompletionSource) delegate).start(token, completion));
        }
    }
}

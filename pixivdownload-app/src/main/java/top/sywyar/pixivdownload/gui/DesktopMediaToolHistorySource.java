package top.sywyar.pixivdownload.gui;

import ch.qos.logback.classic.LoggerContext;
import org.slf4j.LoggerFactory;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.i18n.MessageBundles;
import top.sywyar.pixivdownload.logback.LogSession;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiToolHost;
import top.sywyar.pixivdownload.plugin.api.gui.media.DesktopMediaTool.*;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/** 执行终态直接落入已有历史，观察者只持有宿主值，与页面轮询和 provider 生命周期无关。 */
final class DesktopMediaToolHistorySource implements Source {
    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(DesktopMediaToolHistorySource.class);
    private final Identity identity;
    private final Source source;
    private final DesktopToolHistory history;

    DesktopMediaToolHistorySource(Identity identity, Source source, DesktopToolHistory history) {
        this.identity = identity;
        this.source = source;
        this.history = history;
    }

    @Override public Description description() { return source.description(); }
    @Override public Result<Preview> preview(Request request) { return source.preview(request); }
    @Override public Status status() { return source.status(); }
    @Override public void cancel() { source.cancel(); }
    @Override public Result<Report> capabilities() { return source.capabilities(); }

    @Override public Result<Status> start(String token) {
        if (!(source instanceof CompletionSource tracked)) return source.start(token);
        long started = System.currentTimeMillis();
        var recorded = new AtomicBoolean();
        // 回调不得捕获 source；插件撤回后，已接纳的任务仍须交付取消或失败终态。
        DesktopToolHistory store = history;
        Identity owner = identity;
        return tracked.start(token, status -> {
            if (!recorded.compareAndSet(false, true)) return;
            var outcome = status.state().equals("cancelled") ? DesktopUiToolHost.ToolOutcome.CANCELLED
                    : status.state().equals("completed") && status.failed() == 0
                    ? DesktopUiToolHost.ToolOutcome.SUCCEEDED : DesktopUiToolHost.ToolOutcome.FAILED;
            LOG.info("{} owner={} started={} state={} processed={}/{} failed={}",
                    MessageBundles.getForLog("gui.tools.history.tool.media-maintenance"), owner, started,
                    status.state(), status.completed(), status.total(), status.failed());
            store.record(DesktopUiToolHost.ToolId.MEDIA_MAINTENANCE, outcome, started,
                    status.completed(), status.completed() - status.failed(), status.failed(), sessionLog());
        });
    }

    private static Path sessionLog() {
        var context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Object value = context.getObject(LogSession.class.getName());
        return value instanceof LogSession session
                ? RuntimeFiles.logDirectory().resolve("html/pixiv-download_" + session.getPropertyValue() + ".html")
                : null;
    }
}

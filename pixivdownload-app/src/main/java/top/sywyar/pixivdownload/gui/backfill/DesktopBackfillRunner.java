package top.sywyar.pixivdownload.gui.backfill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.i18n.MessageBundles;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiToolHost.*;
import top.sywyar.pixivdownload.tools.ArtworksBackFill;

import javax.sql.DataSource;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

/** 后端拥有执行线程与连接生命周期，桌面只观察机器事实。 */
@Component
public final class DesktopBackfillRunner implements SmartLifecycle, DisposableBean {
    // 沿用工具日志层级，让任务失败原因同时进入精确会话日志与主程序日志。
    private static final Logger LOG = LoggerFactory.getLogger(ArtworksBackFill.class.getName() + ".desktop");
    private final Operation operation;
    private final java.util.concurrent.Callable<ToolLogSession> logFactory;
    private boolean running;
    private Execution execution;

    @org.springframework.beans.factory.annotation.Autowired
    public DesktopBackfillRunner(DataSource dataSource) {
        this((options, progress) -> ArtworksBackFill.runObserved(options, dataSource, progress),
                () -> top.sywyar.pixivdownload.gui.ToolHtmlLogSession.openStatic("artworks-backfill", ArtworksBackFill.class));
    }

    DesktopBackfillRunner(Operation operation, java.util.concurrent.Callable<ToolLogSession> logFactory) {
        this.operation = operation;
        this.logFactory = logFactory;
    }

    @FunctionalInterface
    interface Operation {
        ArtworksBackFill.Summary run(ArtworksBackFill.Options options, Consumer<ArtworksBackFill.Progress> progress) throws Exception;
    }

    @Override public synchronized void start() { running = true; }
    @Override public synchronized boolean isRunning() { return running; }
    @Override public int getPhase() { return Integer.MAX_VALUE; }

    public BackfillTask start(ArtworksBackFill.Options options, Consumer<BackfillTask> observer) {
        Execution next;
        synchronized (this) {
            if (!running || execution != null) throw new IllegalStateException("BACKFILL_BUSY");
            next = new Execution(options, observer);
            execution = next;
            next.thread.start();
        }
        return next.snapshot;
    }

    public boolean cancel(UUID id) {
        Execution current;
        synchronized (this) { current = execution; }
        return current != null && current.id.equals(id) && current.cancel();
    }

    @Override public void stop() {
        Execution current;
        synchronized (this) {
            running = false;
            current = execution;
        }
        if (current == null) return;
        current.cancel();
        boolean interrupted = false;
        // 物理退出后才允许销毁依赖的连接池；进程关闭的外层预算仍由后端协调器拥有。
        while (current.thread.isAlive()) {
            try { current.thread.join(); }
            catch (InterruptedException ignored) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    @Override public void stop(Runnable callback) { stop(); callback.run(); }
    @Override public void destroy() { stop(); }

    private final class Execution {
        final UUID id = UUID.randomUUID();
        final ArtworksBackFill.Options options;
        final Consumer<BackfillTask> observer;
        final Thread thread;
        volatile BackfillTask snapshot;
        volatile boolean stopping;

        Execution(ArtworksBackFill.Options options, Consumer<BackfillTask> observer) {
            this.options = options;
            this.observer = observer;
            snapshot = new BackfillTask(id, 0, BackfillState.RUNNING, BackfillPhase.IMPORTING,
                    options.automatic(), options.dryRun(), System.currentTimeMillis(), 0,
                    0, null, 0, 0, 0, null, null, null, null);
            thread = new Thread(this::run, "metadata-backfill-" + id);
            thread.setDaemon(true);
        }

        boolean cancel() {
            BackfillTask value;
            synchronized (this) {
                if (stopping || !snapshot.active()) return false;
                stopping = true;
                value = replace(BackfillState.STOPPING, snapshot.phase(), snapshot.summary(), false);
                thread.interrupt();
            }
            publish(value);
            return true;
        }

        void progress(ArtworksBackFill.Progress progress) {
            BackfillTask value;
            synchronized (this) {
                var previous = snapshot;
                value = new BackfillTask(id, previous.revision() + 1,
                        stopping ? BackfillState.STOPPING : BackfillState.RUNNING,
                        BackfillPhase.valueOf(progress.phase().name()), options.automatic(), options.dryRun(),
                        previous.startedAtEpochMs(), 0, progress.processed(), progress.total(), progress.changed(),
                        progress.remaining(), progress.unavailable(), progress.workType(), progress.workId(),
                        summary(progress.summary()), previous.logFileName());
                snapshot = value;
            }
            publish(value);
        }

        void run() {
            BackfillState outcome = BackfillState.FAILED;
            BackfillSummary summary = null;
            ToolLogSession logSession = null;
            try {
                publish(snapshot);
                try {
                    logSession = logFactory.call();
                    synchronized (this) {
                        var value = snapshot;
                        snapshot = new BackfillTask(id, value.revision() + 1, value.state(), value.phase(), value.automatic(),
                                value.dryRun(), value.startedAtEpochMs(), 0, 0, null, 0, 0, 0, null, null, null,
                                logSession.sessionPath().getFileName().toString());
                    }
                } catch (Exception failure) {
                    LOG.warn(MessageBundles.getForLog("desktop.ui.tools.log-open-failed"), failure);
                }
                LOG.info(MessageBundles.getForLog("gui.tools.backfill.task.started", id, options.automatic()));
                var result = operation.run(options, this::progress);
                summary = summary(result);
                outcome = result.rateLimited() ? BackfillState.RATE_LIMITED
                        : snapshot.remaining() > 0 || result.processed() < result.totalCandidates() ? BackfillState.INCOMPLETE : BackfillState.COMPLETED;
            } catch (InterruptedException | CancellationException cancelled) {
                outcome = BackfillState.CANCELLED;
            } catch (Exception failure) {
                outcome = stopping ? BackfillState.CANCELLED : BackfillState.FAILED;
                LOG.error(MessageBundles.getForLog("gui.tools.backfill.task.failed", id), failure);
            } finally {
                Thread.interrupted();
                if (logSession != null) {
                    try { logSession.close(); }
                    catch (RuntimeException failure) { LOG.warn(MessageBundles.getForLog("desktop.ui.tools.log-open-failed"), failure); }
                }
                BackfillTask terminal;
                synchronized (this) {
                    terminal = replace(stopping ? BackfillState.CANCELLED : outcome, BackfillPhase.FINISHED,
                            summary == null ? snapshot.summary() : summary, true);
                }
                try {
                    publish(terminal);
                    LOG.info(MessageBundles.getForLog("gui.tools.backfill.task.finished", id, terminal.state(),
                            terminal.processed(), terminal.changed(), terminal.remaining()));
                } finally {
                    synchronized (DesktopBackfillRunner.this) { execution = null; }
                }
            }
        }

        private BackfillTask replace(BackfillState state, BackfillPhase phase, BackfillSummary summary, boolean finished) {
            var value = snapshot;
            snapshot = new BackfillTask(id, value.revision() + 1, state, phase, value.automatic(), value.dryRun(),
                    value.startedAtEpochMs(), finished ? System.currentTimeMillis() : 0,
                    value.processed(), value.total(), value.changed(), value.remaining(), value.unavailable(),
                    finished ? null : value.workType(), finished ? null : value.workId(), summary, value.logFileName());
            return snapshot;
        }

        void publish(BackfillTask value) { observer.accept(value); }
    }

    public static BackfillSummary summary(ArtworksBackFill.Summary value) {
        return new BackfillSummary(value.totalCandidates(), value.processed(), value.filledAuthor(), value.filledR18(),
                value.filledAi(), value.filledDescription(), value.filledTags(), value.filledSeries(), value.deletedCount(),
                value.skipped(), value.previouslyUnreachable(), value.newlyUnreachable(), value.dryRun(),
                value.rateLimited(), value.filledUploadTime());
    }
}

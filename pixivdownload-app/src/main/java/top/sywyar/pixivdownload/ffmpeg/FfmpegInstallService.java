package top.sywyar.pixivdownload.ffmpeg;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.UUID;

/** HTTP 安装只保留最近任务；桌面仍通过安装器的同一个进程锁准入。 */
@Service
public final class FfmpegInstallService {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(FfmpegInstallService.class);
    @FunctionalInterface interface Installer {
        FfmpegInstallation install(FfmpegInstaller.ProxySettings proxy, FfmpegInstaller.ProgressListener progress) throws Exception;
    }
    public record Snapshot(UUID executionId, String state, FfmpegInstaller.ProgressStage stage,
                           long current, long total, String failureCode,
                           top.sywyar.pixivdownload.plugin.api.gui.DesktopUiText failure) {}
    private final Installer installer;
    private Thread worker;
    private boolean closed;
    private volatile Snapshot snapshot;

    public FfmpegInstallService() { this(FfmpegInstaller::installManaged); }
    FfmpegInstallService(Installer installer) { this.installer = installer; }

    public Snapshot snapshot() { return snapshot; }

    public synchronized Snapshot start(FfmpegInstaller.ProxySettings proxy) throws IOException {
        if (closed || worker != null || FfmpegInstaller.isInstalling()) throw new FfmpegInstaller.BusyException();
        var accepted = new Snapshot(UUID.randomUUID(), "RUNNING", FfmpegInstaller.ProgressStage.CONNECTING, 0, -1, "", null);
        snapshot = accepted;
        worker = Thread.ofPlatform().name("ffmpeg-http-install").unstarted(() -> run(accepted.executionId(), proxy));
        try { worker.start(); }
        catch (RuntimeException | Error failure) {
            worker = null;
            snapshot = new Snapshot(accepted.executionId(), "FAILED", accepted.stage(), 0, -1, "FFMPEG_INSTALL_START_FAILED",
                    top.sywyar.pixivdownload.plugin.api.gui.DesktopUiText.key("ffmpeg.api.failed"));
            throw failure;
        }
        return accepted;
    }

    public synchronized Snapshot cancel(UUID id) {
        if (snapshot == null || !snapshot.executionId().equals(id)) return null;
        if (worker == null || !snapshot.state().equals("RUNNING")) return snapshot;
        // Once the verified binaries are being published, let the complete installation finish.
        if (snapshot.stage() == FfmpegInstaller.ProgressStage.INSTALLING
                || snapshot.stage() == FfmpegInstaller.ProgressStage.COMPLETED) return snapshot;
        snapshot = new Snapshot(id, "CANCEL_REQUESTED", snapshot.stage(), snapshot.current(), snapshot.total(), "", null);
        worker.interrupt();
        LOG.info("FFmpeg installation cancellation requested executionId={}", id);
        return snapshot;
    }

    private synchronized void progress(FfmpegInstaller.ProgressStage stage, long current, long total) {
        if (snapshot.state().equals("CANCEL_REQUESTED")) throw new java.util.concurrent.CancellationException();
        snapshot = new Snapshot(snapshot.executionId(), "RUNNING", stage, current, total, "", null);
    }

    private void run(UUID id, FfmpegInstaller.ProxySettings proxy) {
        String state = "FAILED";
        String code = "FFMPEG_INSTALL_FAILED";
        var explanation = top.sywyar.pixivdownload.plugin.api.gui.DesktopUiText.key("ffmpeg.api.failed");
        LOG.info("FFmpeg installation started executionId={}", id);
        try {
            installer.install(proxy, this::progress);
            state = "COMPLETED";
            code = "";
            explanation = null;
        } catch (FfmpegInstaller.InstallationException failure) {
            code = failure.code();
            explanation = failure.text();
        } catch (FfmpegInstaller.BusyException busy) {
            code = "FFMPEG_INSTALL_BUSY";
            explanation = top.sywyar.pixivdownload.plugin.api.gui.DesktopUiText.key("gui.message.backend-busy");
        } catch (Exception failure) {
            if (interrupted(failure)) {
                if (snapshot.state().equals("CANCEL_REQUESTED")) {
                    state = "CANCELLED";
                    code = "";
                    explanation = null;
                } else {
                    code = "FFMPEG_INSTALL_INTERRUPTED";
                    explanation = top.sywyar.pixivdownload.plugin.api.gui.DesktopUiText.key("ffmpeg.api.interrupted");
                }
            } else {
                LOG.warn("FFmpeg installation failed executionId={} failureType={}", id, failure.getClass().getName());
            }
        } finally {
            synchronized (this) {
                var last = snapshot;
                snapshot = new Snapshot(id, state, last.stage(), last.current(), last.total(), code, explanation);
                worker = null;
            }
            LOG.info("FFmpeg installation finished executionId={} state={} code={}", id, state, code);
        }
    }

    private static boolean interrupted(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof InterruptedException || cause instanceof java.util.concurrent.CancellationException
                    || cause instanceof java.nio.channels.ClosedByInterruptException) return true;
        }
        return false;
    }

    @PreDestroy
    public void close() {
        Thread running;
        synchronized (this) {
            closed = true;
            running = worker;
            if (running == null) return;
            if (snapshot.stage() != FfmpegInstaller.ProgressStage.INSTALLING
                    && snapshot.stage() != FfmpegInstaller.ProgressStage.COMPLETED) running.interrupt();
        }
        boolean interrupted = false;
        while (running.isAlive()) {
            try { running.join(); }
            catch (InterruptedException ignored) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}

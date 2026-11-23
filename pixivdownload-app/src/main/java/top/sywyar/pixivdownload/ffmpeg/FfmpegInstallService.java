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

    private void run(UUID id, FfmpegInstaller.ProxySettings proxy) {
        String state = "FAILED";
        String code = "FFMPEG_INSTALL_FAILED";
        var explanation = top.sywyar.pixivdownload.plugin.api.gui.DesktopUiText.key("ffmpeg.api.failed");
        LOG.info("FFmpeg installation started executionId={}", id);
        try {
            installer.install(proxy, (stage, current, total) -> snapshot = new Snapshot(id, "RUNNING", stage, current, total, "", null));
            state = "COMPLETED";
            code = "";
            explanation = null;
        } catch (FfmpegInstaller.InstallationException failure) {
            code = failure.code();
            explanation = failure.text();
        } catch (FfmpegInstaller.BusyException busy) {
            code = "FFMPEG_INSTALL_BUSY";
            explanation = top.sywyar.pixivdownload.plugin.api.gui.DesktopUiText.key("gui.message.backend-busy");
        } catch (InterruptedException interrupted) {
            code = "FFMPEG_INSTALL_INTERRUPTED";
            explanation = top.sywyar.pixivdownload.plugin.api.gui.DesktopUiText.key("ffmpeg.api.interrupted");
            Thread.currentThread().interrupt();
        } catch (Exception failure) {
            LOG.warn("FFmpeg installation failed executionId={} failureType={}", id, failure.getClass().getName());
        } finally {
            synchronized (this) {
                var last = snapshot;
                snapshot = new Snapshot(id, state, last.stage(), last.current(), last.total(), code, explanation);
                worker = null;
            }
            LOG.info("FFmpeg installation finished executionId={} state={} code={}", id, state, code);
        }
    }

    @PreDestroy
    public void close() {
        Thread running;
        synchronized (this) { closed = true; running = worker; }
        if (running == null) return;
        running.interrupt();
        boolean interrupted = false;
        while (running.isAlive()) {
            try { running.join(); }
            catch (InterruptedException ignored) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}

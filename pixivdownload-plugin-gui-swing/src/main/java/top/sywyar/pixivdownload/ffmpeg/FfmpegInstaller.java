package top.sywyar.pixivdownload.ffmpeg;

import top.sywyar.pixivdownload.guiswing.SwingHost;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;

import java.io.IOException;

public final class FfmpegInstaller {
    private FfmpegInstaller() {}
    public static boolean supportsManagedDownload() { return SwingHost.host().supportsManagedFfmpegInstall(); }
    public static FfmpegInstallation installManaged(ProxySettings proxy, ProgressListener listener)
            throws IOException, InterruptedException {
        ProxySettings value = proxy == null ? ProxySettings.disabled() : proxy;
        DesktopUiHost.FfmpegInstallation installed = SwingHost.host().installManagedFfmpeg(
                new DesktopUiHost.FfmpegProxy(value.enabled(), value.host(), value.port()),
                listener == null ? (stage, current, total) -> {} : listener::onProgress);
        return new FfmpegInstallation(installed.ffmpegPath(), installed.ffprobePath(), installed.homeDir(),
                installed.sourceMessageCode());
    }
    @FunctionalInterface public interface ProgressListener { void onProgress(String stage, long current, long total); }
    public record ProxySettings(boolean enabled, String host, int port) {
        public ProxySettings { host = host == null ? "" : host.trim(); }
        public static ProxySettings disabled() { return new ProxySettings(false, "", 0); }
    }
}

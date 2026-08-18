package top.sywyar.pixivdownload.ffmpeg;

import top.sywyar.pixivdownload.guiswing.SwingHost;

import java.nio.file.Path;
import java.util.Optional;

public final class FfmpegLocator {
    private FfmpegLocator() {}
    public static Optional<FfmpegInstallation> locate() {
        return SwingHost.host().locateFfmpeg().map(value -> new FfmpegInstallation(
                value.ffmpegPath(), value.ffprobePath(), value.homeDir(), value.sourceMessageCode()));
    }
    public static Path managedToolsDir() { return SwingHost.host().managedFfmpegDirectory(); }
}

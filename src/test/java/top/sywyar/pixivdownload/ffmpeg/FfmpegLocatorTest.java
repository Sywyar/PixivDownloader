package top.sywyar.pixivdownload.ffmpeg;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FfmpegLocatorTest {

    @TempDir
    Path tempDir;

    @Test
    void defaultManagedRootUsesLocalAppDataOnWindows() {
        Path root = FfmpegLocator.defaultManagedRoot(
                "Windows 11",
                "C:\\Users\\tester\\AppData\\Local",
                Path.of("C:\\Users\\tester")
        );

        assertEquals(Path.of("C:\\Users\\tester\\AppData\\Local").resolve("PixivDownload"), root);
    }

    @Test
    void defaultManagedRootFallsBackToHiddenDirectoryOnNonWindows() {
        Path userHome = Path.of("/home/tester");
        Path root = FfmpegLocator.defaultManagedRoot("Linux", "", userHome);

        assertEquals(userHome.resolve(".pixivdownload"), root);
    }

    @Test
    void installationAtReturnsEmptyWhenFfmpegMissing() {
        assertTrue(FfmpegLocator.installationAt(tempDir, FfmpegInstallation.Source.MANAGED).isEmpty());
    }

    @Test
    void installationAtReturnsInstallationWhenFfmpegExists() throws IOException {
        Path ffmpeg = tempDir.resolve(FfmpegLocator.executableName());
        Path ffprobe = tempDir.resolve(FfmpegLocator.probeExecutableName());
        Files.writeString(ffmpeg, "ffmpeg");
        Files.writeString(ffprobe, "ffprobe");

        FfmpegInstallation installation = FfmpegLocator.installationAt(
                tempDir,
                FfmpegInstallation.Source.MANAGED
        ).orElseThrow();

        assertEquals(ffmpeg, installation.ffmpegPath());
        assertEquals(ffprobe, installation.ffprobePath());
        assertEquals(tempDir, installation.homeDir());
        assertEquals(FfmpegInstallation.Source.MANAGED, installation.source());
        assertTrue(installation.hasFfmpeg());
        assertTrue(installation.hasFfprobe());
    }
}

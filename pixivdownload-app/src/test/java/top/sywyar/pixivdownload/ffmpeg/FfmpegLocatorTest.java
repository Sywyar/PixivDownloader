package top.sywyar.pixivdownload.ffmpeg;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.common.AppInfo;
import top.sywyar.pixivdownload.gui.config.ConfigFileEditor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

        assertEquals(Path.of("C:\\Users\\tester\\AppData\\Local").resolve(AppInfo.LEGACY_ARTIFACT_NAME), root);
    }

    @Test
    void defaultManagedRootFallsBackToHiddenDirectoryOnNonWindows() {
        Path userHome = Path.of("/home/tester");
        Path root = FfmpegLocator.defaultManagedRoot("Linux", "", userHome);

        assertEquals(userHome.resolve(AppInfo.HIDDEN_DIRECTORY_NAME), root);
    }

    @Test
    void installationAtReturnsEmptyWhenFfmpegMissing() {
        assertTrue(FfmpegLocator.installationAt(tempDir, FfmpegInstallation.Source.MANAGED).isEmpty());
    }

    @Test
    void installationAtReturnsInstallationWhenFfmpegExists() throws IOException {
        Path ffmpeg = tempDir.resolve(FfmpegLocator.executableName());
        Path ffprobe = tempDir.resolve(FfmpegLocator.probeExecutableName());
        Files.writeString(ffmpeg, "ffmpeg", StandardCharsets.UTF_8);
        Files.writeString(ffprobe, "ffprobe", StandardCharsets.UTF_8);

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

    @Test
    void configuredDirectoryAndExecutableTakePriority() throws IOException {
        Path directory = Files.createDirectories(tempDir.resolve("FFmpeg (自定义)"));
        Path ffmpeg = Files.writeString(
                directory.resolve(FfmpegLocator.executableName()), "ffmpeg", StandardCharsets.UTF_8);
        Path ffprobe = Files.writeString(
                directory.resolve(FfmpegLocator.probeExecutableName()), "ffprobe", StandardCharsets.UTF_8);
        Path config = tempDir.resolve("config.yaml");
        ConfigFileEditor editor = new ConfigFileEditor(config);

        for (Path configured : new Path[]{directory, ffmpeg}) {
            editor.write(FfmpegLocator.CONFIG_KEY, configured.toString());
            FfmpegInstallation installation = FfmpegLocator.locate(config).orElseThrow();
            assertEquals(FfmpegInstallation.Source.CUSTOM, installation.source());
            assertEquals(ffmpeg.toRealPath(), installation.ffmpegPath());
            assertEquals(ffprobe, installation.ffprobePath());
        }
    }

    @Test
    void configuredPathMustNameAnExistingAbsoluteFfmpeg() throws IOException {
        assertDoesNotThrow(() -> FfmpegLocator.validateConfiguredPath(""));
        assertThrows(IOException.class, () -> FfmpegLocator.validateConfiguredPath("relative/ffmpeg"));
        assertThrows(IOException.class, () -> FfmpegLocator.validateConfiguredPath(tempDir.resolve("missing").toString()));

        Path wrongName = Files.writeString(tempDir.resolve("not-ffmpeg"), "binary", StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> FfmpegLocator.validateConfiguredPath(wrongName.toString()));
    }
}

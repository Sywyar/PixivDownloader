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
    @Test
    @org.junit.jupiter.api.DisplayName("未变化配置只读取一次，内容变化后重新解析且不缓存安装状态")
    void configurationCacheRefreshes() throws Exception {
        Path first = Files.createDirectories(tempDir.resolve("first"));
        Path second = Files.createDirectories(tempDir.resolve("second"));
        Files.writeString(first.resolve(FfmpegLocator.executableName()), "");
        Files.writeString(second.resolve(FfmpegLocator.executableName()), "");
        Path config = Files.writeString(tempDir.resolve("cache.yaml"), "a");
        try (var editors = org.mockito.Mockito.mockConstruction(ConfigFileEditor.class, (editor, context) -> {
            org.mockito.Mockito.when(editor.read(FfmpegLocator.CONFIG_KEY))
                    .thenAnswer(call -> Files.readString(config).equals("a") ? first.toString() : second.toString());
        })) {
            assertEquals(first.toRealPath(), FfmpegLocator.locate(config).orElseThrow().homeDir());
            FfmpegLocator.locate(config);
            assertEquals(1, editors.constructed().size());
            Files.writeString(config, "changed");
            assertEquals(second.toRealPath(), FfmpegLocator.locate(config).orElseThrow().homeDir());
            assertEquals(2, editors.constructed().size());
            Files.delete(second.resolve(FfmpegLocator.executableName()));
            assertTrue(FfmpegLocator.locate(config).filter(value -> value.source() == FfmpegInstallation.Source.CUSTOM).isEmpty());
        }
    }

    @Test
    @org.junit.jupiter.api.DisplayName("PATH 顺序与工作目录直接解析文件，安装删除后不使用旧路径")
    void findsFilesWithoutLocatorProcess() throws Exception {
        Path first = Files.createDirectories(tempDir.resolve("first"));
        Path second = Files.createDirectories(tempDir.resolve("with spaces"));
        String name = FfmpegLocator.executableName();
        Path binary = Files.writeString(second.resolve(name), "");
        String path = first + java.io.File.pathSeparator + "\"" + second + "\"";
        assertEquals(binary.toAbsolutePath(), FfmpegLocator.findOnPath(name, path, tempDir, true).orElseThrow());
        Path local = Files.writeString(tempDir.resolve(name), "");
        assertEquals(local, FfmpegLocator.findOnPath(name, path, tempDir, true).orElseThrow());
        Files.delete(local);
        Files.delete(binary);
        assertTrue(FfmpegLocator.findOnPath(name, path, tempDir, true).isEmpty());
    }

}

package top.sywyar.pixivdownload.gui;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.ffmpeg.FfmpegLocator;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@DisplayName("桌面宿主 FFmpeg 目录")
public class AppDesktopUiHostFfmpegTest {

    /** 在发布夹具放行前检查真实桌面宿主与排在查询之后的 Swing 事件。 */
    public static void checkSwingStatus(Path tools) throws Exception {
        var host = new AppDesktopUiHost(0, mock(DesktopUiHost.ConfigFile.class));
        var status = new java.util.concurrent.FutureTask<>(host::locateFfmpeg);
        var nextEvent = new java.util.concurrent.CountDownLatch(1);
        javax.swing.SwingUtilities.invokeLater(status);
        javax.swing.SwingUtilities.invokeLater(nextEvent::countDown);
        var installation = status.get(5, java.util.concurrent.TimeUnit.SECONDS).orElseThrow();
        assertThat(installation.source()).isEqualTo(DesktopUiHost.FfmpegSource.MANAGED);
        assertThat(installation.homeDir()).isEqualTo(tools);
        assertThat(nextEvent.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @DisplayName("未安装 FFmpeg 时打开目录不沿 Junction 祖先创建文件夹")
    void openingMissingFfmpegDirectoryRejectsJunctionAncestor(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeTrue(FfmpegLocator.isWindows());
        Path installationRoot = Files.createDirectories(tempDir.resolve("Program Files/PixivDownload"));
        Path realTools = Files.createDirectory(tempDir.resolve("FormatFactory (x86)"));
        Path linkedTools = installationRoot.resolve("tools");
        Process mklink = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J",
                linkedTools.toString(), realTools.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectErrorStream(true)
                .start();
        Assumptions.assumeTrue(mklink.waitFor() == 0 && Files.isDirectory(linkedTools));
        String oldDirectory = System.getProperty("user.dir");
        try {
            System.setProperty("user.dir", installationRoot.toString());
            AppDesktopUiHost host = new AppDesktopUiHost(0, mock(DesktopUiHost.ConfigFile.class));
            assertThat(host.managedFfmpegDirectory()).isEqualTo(linkedTools.resolve("ffmpeg"));
            assertThatThrownBy(host::prepareManagedFfmpegDirectory)
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining(linkedTools.toString());
            assertThat(realTools.resolve("ffmpeg")).doesNotExist();
        } finally {
            System.setProperty("user.dir", oldDirectory);
            Files.deleteIfExists(linkedTools);
        }
    }
}

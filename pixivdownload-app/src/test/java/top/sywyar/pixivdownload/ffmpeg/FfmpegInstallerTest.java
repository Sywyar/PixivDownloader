package top.sywyar.pixivdownload.ffmpeg;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("FFmpeg 稳定版资产选择")
class FfmpegInstallerTest {

    @Test
    @DisplayName("按操作系统与架构选择对应的稳定版资产")
    void selectsStableAssetForSupportedPlatform() {
        assertAsset("Windows 11", "amd64", "ffmpeg-windows-x64.zip");
        assertAsset("Linux", "x86_64", "ffmpeg-linux-x64.zip");
        assertAsset("Linux", "aarch64", "ffmpeg-linux-arm64.zip");
        assertAsset("Mac OS X", "x86_64", "ffmpeg-macos-x64.zip");
        assertAsset("Mac OS X", "arm64", "ffmpeg-macos-arm64.zip");
        assertAsset("Darwin", "arm64", "ffmpeg-macos-arm64.zip");
    }

    @Test
    @DisplayName("没有对应构建的系统保持手动安装降级")
    void rejectsUnsupportedPlatform() {
        assertThat(FfmpegInstaller.archiveUri("Windows 11", "aarch64")).isEmpty();
        assertThat(FfmpegInstaller.archiveUri("Linux", "x86")).isEmpty();
        assertThat(FfmpegInstaller.archiveUri("FreeBSD", "amd64")).isEmpty();
    }

    @Test
    @DisplayName("只提取运行文件与随包许可证")
    void extractsRequiredFiles(@TempDir Path tempDir) throws IOException {
        Path archive = tempDir.resolve("ffmpeg.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            addEntry(zip, "ffmpeg-test/bin/" + FfmpegLocator.executableName());
            addEntry(zip, "ffmpeg-test/bin/" + FfmpegLocator.probeExecutableName());
            addEntry(zip, "ffmpeg-test/licenses/ffmpeg-LGPLv2.1.txt");
            addEntry(zip, "ffmpeg-test/licenses/libwebp-COPYING.txt");
            addEntry(zip, "ffmpeg-test/licenses/libwebp-PATENTS.txt");
            addEntry(zip, "ffmpeg-test/ignored.txt");
        }

        FfmpegInstaller.ExtractedFiles files = FfmpegInstaller.extractRequiredFiles(
                archive, tempDir.resolve("extracted"));
        assertThat(List.of(files.ffmpeg(), files.ffprobe(), files.ffmpegLicense(),
                files.libwebpLicense(), files.libwebpPatents())).allMatch(Files::isRegularFile);
        assertThat(tempDir.resolve("extracted/ignored.txt")).doesNotExist();
    }

    private static void assertAsset(String osName, String osArch, String asset) {
        assertThat(FfmpegInstaller.archiveUri(osName, osArch))
                .contains(URI.create(FfmpegInstaller.RELEASE_BASE_URL + asset));
    }

    private static void addEntry(ZipOutputStream zip, String name) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(name.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}

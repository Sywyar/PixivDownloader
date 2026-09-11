package top.sywyar.pixivdownload.sdk.community.project;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("插件工程标识读取")
class PluginProjectMarkerTest {
    @TempDir Path directory;

    @Test
    @DisplayName("同一版本允许 BOM 和常见单行行尾，拒绝额外内容与其它编码")
    void acceptsOnlyTheVersionLine() throws IOException {
        Path marker = directory.resolve(PluginProjectMarker.FILE_NAME);
        String version = "pixivdownloader-plugin-project-v1";
        assertThatThrownBy(() -> PluginProjectMarker.validate(directory)).isInstanceOf(IOException.class);
        for (String bom : new String[]{"", "\ufeff"}) {
            for (String ending : new String[]{"", "\n", "\r\n"}) {
                Files.writeString(marker, bom + version + ending, StandardCharsets.UTF_8);
                assertThatCode(() -> PluginProjectMarker.validate(directory)).doesNotThrowAnyException();
            }
        }
        for (String invalid : new String[]{"", version + "\r", version + "\n\n", version + " ",
                " " + version, version + "\nextra", version.replace("v1", "v2"), "\ufeff\ufeff" + version,
                version + "x".repeat(10000)}) {
            Files.writeString(marker, invalid, StandardCharsets.UTF_8);
            assertThatThrownBy(() -> PluginProjectMarker.validate(directory)).isInstanceOf(IOException.class);
        }
        Files.write(marker, version.getBytes(StandardCharsets.UTF_16));
        assertThatThrownBy(() -> PluginProjectMarker.validate(directory)).isInstanceOf(IOException.class);
        Files.delete(marker);
        Files.createDirectory(marker);
        assertThatThrownBy(() -> PluginProjectMarker.validate(directory)).isInstanceOf(IOException.class);
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    @DisplayName("符号链接标识即使内容合法也拒绝")
    void rejectsLinkedMarker() throws IOException {
        Path source = directory.resolve("source");
        Files.writeString(source, "pixivdownloader-plugin-project-v1\n", StandardCharsets.UTF_8);
        Files.createSymbolicLink(directory.resolve(PluginProjectMarker.FILE_NAME), source);
        assertThatThrownBy(() -> PluginProjectMarker.validate(directory)).isInstanceOf(IOException.class);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("Windows 的目录联接不能充当工程标识")
    void rejectsJunctionMarker() throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        Path marker = directory.resolve(PluginProjectMarker.FILE_NAME);
        Process process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", marker.toString(), source.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        org.assertj.core.api.Assertions.assertThat(process.waitFor()).isZero();
        try {
            assertThatThrownBy(() -> PluginProjectMarker.validate(directory)).isInstanceOf(IOException.class);
        } finally {
            Files.delete(marker);
        }
    }
}

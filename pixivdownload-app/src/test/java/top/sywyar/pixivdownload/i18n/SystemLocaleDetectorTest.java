package top.sywyar.pixivdownload.i18n;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.common.Utf8ConsoleStreams;
import top.sywyar.pixivdownload.config.RuntimeFiles;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("启动语言配置与系统快照")
class SystemLocaleDetectorTest {
    @TempDir Path directory;

    @Test
    @DisplayName("真实子进程中配置优先，未知或损坏配置回退，跟随系统不受界面切换污染")
    void configurationAndStartupSnapshotRemainIndependent() throws Exception {
        Path output = directory.resolve("process.log");
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-Dfile.encoding=UTF-8", "-Duser.language=ja", "-Duser.country=JP",
                "-D" + RuntimeFiles.CONFIG_DIR_PROPERTY + "=" + directory,
                "-cp", System.getProperty("java.class.path"), Probe.class.getName())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
            String diagnostics = Files.readString(output, StandardCharsets.UTF_8);
            assertThat(process.exitValue()).as(diagnostics).isZero();
            assertThat(diagnostics).contains("LOCALE_PROBE_OK", "Failed reading");
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    public static final class Probe {
        public static void main(String[] args) throws Exception {
            Utf8ConsoleStreams.install();
            Path config = RuntimeFiles.peekConfigYamlPath();
            List<String> diagnostics = new ArrayList<>();
            Files.writeString(config, "app.language: zh_Hans\n", StandardCharsets.UTF_8);
            assertThat(SystemLocaleDetector.detectAndApply(diagnostics::add)).isEqualTo(Locale.SIMPLIFIED_CHINESE);
            assertThat(Locale.getDefault()).isEqualTo(Locale.SIMPLIFIED_CHINESE);
            assertThat(diagnostics.get(0)).contains("app.language=zh-Hans");

            Files.writeString(config, "app.language: en_US\n", StandardCharsets.UTF_8);
            assertThat(SystemLocaleDetector.detectAndApply(diagnostics::add)).isEqualTo(Locale.US);
            for (String content : List.of("app.language: zz-ZZ\n", "app.language: zh-TW\n", "app.language: \n", "unrelated: value\n")) {
                Files.writeString(config, content, StandardCharsets.UTF_8);
                Locale.setDefault(Locale.KOREA);
                assertThat(SystemLocaleDetector.detectAndApply(diagnostics::add)).isEqualTo(Locale.JAPAN);
            }
            Files.write(config, new byte[]{(byte) 0xff});
            assertThat(SystemLocaleDetector.detectAndApply(diagnostics::add)).isEqualTo(Locale.JAPAN);
            Files.delete(config);
            Locale.setDefault(Locale.US);
            assertThat(SystemLocaleDetector.detectAndApply(diagnostics::add)).isEqualTo(Locale.JAPAN);
            assertThat(diagnostics.get(diagnostics.size() - 1)).contains("JVM default ja-JP");
            System.out.println("LOCALE_PROBE_OK");
        }
    }
}

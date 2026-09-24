package top.sywyar.pixivdownload.ffmpeg;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.core.ffmpeg.ResolvedFfmpegCommand;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("宿主 FFmpeg 命令解析适配器")
class FfmpegCommandResolverAdapterTest {

    @Test
    @DisplayName("已探测安装应映射命令路径与受控来源")
    void detectedInstallationMapsCommandAndSource() {
        for (FfmpegInstallation.Source source : FfmpegInstallation.Source.values()) {
            Path executable = Path.of("tools", source.name().toLowerCase(), "ffmpeg");
            FfmpegInstallation installation = new FfmpegInstallation(
                    executable,
                    null,
                    executable.getParent(),
                    source
            );
            FfmpegCommandResolverAdapter adapter = new FfmpegCommandResolverAdapter(
                    () -> Optional.of(installation),
                    () -> "unused-fallback"
            );

            ResolvedFfmpegCommand resolved = adapter.resolve();

            assertThat(resolved.command()).isEqualTo(executable.toString());
            assertThat(resolved.source()).isEqualTo(expectedSource(source));
        }
    }

    @Test
    @DisplayName("未探测到安装时应保留平台回退命令")
    void missingInstallationUsesFallbackCommand() {
        FfmpegCommandResolverAdapter adapter = new FfmpegCommandResolverAdapter(
                Optional::empty,
                () -> "ffmpeg-fallback"
        );

        assertThat(adapter.resolve())
                .isEqualTo(new ResolvedFfmpegCommand(
                        "ffmpeg-fallback",
                        ResolvedFfmpegCommand.Source.FALLBACK
                ));
    }

    @Test
    @DisplayName("自定义路径可被按既有来源枚举编译的插件消费")
    void customInstallationWorksWithPreviouslyCompiledConsumer(@TempDir Path tempDir) throws Exception {
        Path api = tempDir.resolve("ResolvedFfmpegCommand.java");
        Files.writeString(api, """
                package top.sywyar.pixivdownload.core.ffmpeg;
                public record ResolvedFfmpegCommand(String command, Source source) {
                    public enum Source { MANAGED, BUNDLED, SYSTEM, FALLBACK }
                }
                """, StandardCharsets.UTF_8);
        Path consumer = tempDir.resolve("LegacyFfmpegConsumer.java");
        Files.writeString(consumer, """
                package compatibility;
                import top.sywyar.pixivdownload.core.ffmpeg.ResolvedFfmpegCommand;
                public class LegacyFfmpegConsumer {
                    public static String command(ResolvedFfmpegCommand resolved) {
                        String source = switch (resolved.source()) {
                            case MANAGED -> "managed";
                            case BUNDLED -> "bundled";
                            case SYSTEM -> "system";
                            case FALLBACK -> "fallback";
                        };
                        return source + ":" + resolved.command();
                    }
                }
                """, StandardCharsets.UTF_8);
        ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
        int result = ToolProvider.getSystemJavaCompiler().run(null, diagnostics, diagnostics,
                "-proc:none", "-encoding", "UTF-8", "-classpath", tempDir.toString(),
                "-d", tempDir.toString(), api.toString(), consumer.toString());
        assertThat(result).as(diagnostics.toString(StandardCharsets.UTF_8)).isZero();

        Path executable = tempDir.resolve("custom tools").resolve("ffmpeg");
        var installation = new FfmpegInstallation(executable, null, executable.getParent(),
                FfmpegInstallation.Source.CUSTOM);
        var adapter = new FfmpegCommandResolverAdapter(() -> Optional.of(installation), () -> "unused");
        // 父加载器提供当前 API；子加载器只加载按旧枚举编译的消费方及其 switch 映射。
        try (var loader = new URLClassLoader(new java.net.URL[]{tempDir.toUri().toURL()},
                ResolvedFfmpegCommand.class.getClassLoader())) {
            assertThat(loader.loadClass(ResolvedFfmpegCommand.class.getName()))
                    .isSameAs(ResolvedFfmpegCommand.class);
            var command = loader.loadClass("compatibility.LegacyFfmpegConsumer")
                    .getMethod("command", ResolvedFfmpegCommand.class);
            assertThat(command.invoke(null, adapter.resolve())).isEqualTo("system:" + executable);
        }
    }

    private static ResolvedFfmpegCommand.Source expectedSource(FfmpegInstallation.Source source) {
        return switch (source) {
            case CUSTOM -> ResolvedFfmpegCommand.Source.SYSTEM;
            case MANAGED -> ResolvedFfmpegCommand.Source.MANAGED;
            case BUNDLED -> ResolvedFfmpegCommand.Source.BUNDLED;
            case SYSTEM -> ResolvedFfmpegCommand.Source.SYSTEM;
        };
    }
}

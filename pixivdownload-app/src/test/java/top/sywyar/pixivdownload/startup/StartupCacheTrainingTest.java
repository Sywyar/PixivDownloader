package top.sywyar.pixivdownload.startup;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.common.Utf8ConsoleStreams;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopStartupCache.Phase;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("桌面训练子进程隔离与退出")
class StartupCacheTrainingTest {
    @TempDir Path directory;

    @Test @DisplayName("真实训练子进程保留显示会话，排除凭据与 JVM 环境注入")
    void graphicalEnvironmentWithoutInjection() throws Exception {
        Path result = directory.resolve("environment.properties");
        var builder = new ProcessBuilder(command(Driver.class.getName(), result.toString()));
        builder.environment().putAll(Map.of("DISPLAY", ":987", "XAUTHORITY", "/test/xauthority",
                "WAYLAND_DISPLAY", "test-wayland", "XDG_RUNTIME_DIR", "/test/runtime",
                "TEST_API_TOKEN", "private-fixture", "JAVA_TOOL_OPTIONS", "-Dtraining.injection=tool",
                "JDK_JAVA_OPTIONS", "-Dtraining.injection=jdk", "_JAVA_OPTIONS", "-Dtraining.injection=legacy"));
        assertThat(run(builder, "environment.log")).isZero();
        var properties = new Properties();
        try (var reader = Files.newBufferedReader(result, StandardCharsets.UTF_8)) { properties.load(reader); }
        assertThat(properties).containsEntry("DISPLAY", ":987").containsEntry("XAUTHORITY", "/test/xauthority")
                .containsEntry("WAYLAND_DISPLAY", "test-wayland").containsEntry("XDG_RUNTIME_DIR", "/test/runtime");
        for (String excluded : List.of("TEST_API_TOKEN", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "training.injection"))
            assertThat(properties).containsEntry(excluded, "absent");
    }

    @Test @DisplayName("桌面训练误入无界面时真实启动器失败退出且不发布就绪标记")
    @org.junit.jupiter.api.Timeout(60)
    void headlessDesktopTrainingExits() throws Exception {
        var args = new ArrayList<>(command("top.sywyar.pixivdownload.gui.GuiLauncher", "--server.port=0"));
        args.add(1, "-Djava.awt.headless=true");
        args.add(1, "-D" + StartupCacheTraining.TRAINING_PROPERTY + "=true");
        Path training = Files.createDirectory(directory.resolve("headless"));
        assertThatThrownBy(() -> StartupCacheTraining.execute(args, training, Phase.RECORDING))
                .isInstanceOfSatisfying(StartupCacheTraining.TrainingFailure.class, failure -> {
                    assertThat(failure.phase).isEqualTo(Phase.RECORDING);
                    assertThat(failure.exitCode).isEqualTo(1);
                });
        assertThat(training.resolve(StartupCacheTraining.READY_FILE)).doesNotExist();
        assertThat(Files.readString(directory.resolve("desktop-training.log"), StandardCharsets.UTF_8))
                .contains("Desktop startup cache training requires a graphical session")
                .doesNotContain("Started PixivDownloadApplication");
    }

    private int run(ProcessBuilder builder, String log) throws Exception {
        builder.directory(directory.toFile()).redirectErrorStream(true).redirectOutput(directory.resolve(log).toFile());
        Process child = builder.start();
        try {
            assertThat(child.waitFor(60, TimeUnit.SECONDS)).as("子进程必须完成，不得启动常驻后端").isTrue();
            return child.exitValue();
        } finally {
            child.descendants().forEach(ProcessHandle::destroyForcibly);
            if (child.isAlive()) child.destroyForcibly();
            child.waitFor();
        }
    }

    private static List<String> command(String main, String... args) {
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        classpath = java.util.Arrays.stream(classpath.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)))
                .map(value -> Path.of(value).toAbsolutePath().toString()).collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
        var command = new ArrayList<>(List.of(DistributionLauncher.java(), "-cp", classpath, main));
        command.addAll(List.of(args));
        return command;
    }

    public static final class Driver {
        public static void main(String[] args) throws Exception {
            Utf8ConsoleStreams.install();
            StartupCacheTraining.execute(command(EnvironmentProbe.class.getName(), args), Path.of("").toAbsolutePath(), Phase.RECORDING);
        }
    }

    public static final class EnvironmentProbe {
        public static void main(String[] args) throws Exception {
            Utf8ConsoleStreams.install();
            var properties = new Properties();
            for (String key : List.of("DISPLAY", "XAUTHORITY", "WAYLAND_DISPLAY", "XDG_RUNTIME_DIR",
                    "TEST_API_TOKEN", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"))
                properties.setProperty(key, System.getenv().getOrDefault(key, "absent"));
            properties.setProperty("training.injection", System.getProperty("training.injection", "absent"));
            try (var writer = Files.newBufferedWriter(Path.of(args[0]), StandardCharsets.UTF_8)) { properties.store(writer, null); }
        }
    }
}

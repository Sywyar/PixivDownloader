package top.sywyar.pixivdownload.plugin.runtime.isolation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("隔离插件 worker 会话")
class IsolatedPluginSessionTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void clearWorkerProperties() {
        List.of(
                IsolatedPluginSession.INITIALIZE_TIMEOUT_PROPERTY,
                IsolatedPluginSession.COMMAND_TIMEOUT_PROPERTY,
                IsolatedPluginSession.SHUTDOWN_TIMEOUT_PROPERTY,
                IsolatedPluginSession.RESTART_ATTEMPTS_PROPERTY,
                IsolatedPluginSession.RESTART_INITIAL_DELAY_PROPERTY,
                IsolatedPluginSession.RESTART_MAX_DELAY_PROPERTY,
                IsolatedPluginSession.STDERR_MAX_BYTES_PROPERTY
        ).forEach(System::clearProperty);
    }

    @Test
    @DisplayName("worker 保留宿主环境并仅覆盖私有临时目录")
    void preservesInheritedEnvironment() {
        ProcessBuilder builder = new ProcessBuilder();
        Map<String, String> environment = builder.environment();
        environment.clear();
        environment.put("PATH", "tools-path");
        environment.put("HTTP_PROXY", "http://proxy.example:8080");
        environment.put("USERPROFILE", "user-profile");
        environment.put("LANG", "zh_CN.UTF-8");

        IsolatedPluginSession.configureEnvironment(builder, tempDir);

        assertThat(environment).containsEntry("PATH", "tools-path")
                .containsEntry("HTTP_PROXY", "http://proxy.example:8080")
                .containsEntry("USERPROFILE", "user-profile")
                .containsEntry("LANG", "zh_CN.UTF-8")
                .containsEntry("TEMP", tempDir.toString())
                .containsEntry("TMP", tempDir.toString())
                .containsEntry("TMPDIR", tempDir.toString());
    }

    @Test
    @DisplayName("worker 超时与指数退避配置有界且非法值失败关闭")
    void validatesWorkerSettingsAndCapsBackoff() {
        System.setProperty(IsolatedPluginSession.RESTART_ATTEMPTS_PROPERTY, "4");
        System.setProperty(IsolatedPluginSession.RESTART_INITIAL_DELAY_PROPERTY, "10");
        System.setProperty(IsolatedPluginSession.RESTART_MAX_DELAY_PROPERTY, "25");

        IsolatedPluginSession.Settings settings = IsolatedPluginSession.Settings.fromSystemProperties();

        assertThat(settings.restartAttempts()).isEqualTo(4);
        assertThat(settings.restartDelay(1)).isEqualTo(Duration.ofMillis(10));
        assertThat(settings.restartDelay(2)).isEqualTo(Duration.ofMillis(20));
        assertThat(settings.restartDelay(3)).isEqualTo(Duration.ofMillis(25));

        System.setProperty(IsolatedPluginSession.RESTART_MAX_DELAY_PROPERTY, "5");
        assertThatThrownBy(IsolatedPluginSession.Settings::fromSystemProperties)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(IsolatedPluginSession.RESTART_MAX_DELAY_PROPERTY);
    }

    @Test
    @DisplayName("worker 命令失败通过有界协议保留异常类型与调用栈")
    void preservesWorkerFailureStackTrace() throws Exception {
        byte[] failure = IsolatedPluginProtocol.failure(new IllegalStateException("probe failure"));

        assertThatThrownBy(() -> IsolatedPluginProtocol.requireSuccess(failure))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("IllegalStateException: probe failure")
                .hasMessageContaining("preservesWorkerFailureStackTrace");
    }

    @Test
    @DisplayName("强制清退 worker 时同时终止其后代进程")
    void terminatesWholeProcessTree() throws Exception {
        Process root = new ProcessBuilder(
                javaExecutable(),
                "-cp",
                System.getProperty("java.class.path"),
                ProcessTreeRoot.class.getName())
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        long childPid;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                root.getInputStream(), StandardCharsets.UTF_8))) {
            childPid = Long.parseLong(reader.readLine());
        }

        assertThat(ProcessHandle.of(childPid)).hasValueSatisfying(handle -> assertThat(handle.isAlive()).isTrue());
        assertThat(IsolatedPluginSession.terminateProcessTree(root, Duration.ofSeconds(2))).isTrue();
        assertThat(root.isAlive()).isFalse();
        assertThat(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin",
                        System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java")
                .toString();
    }

    public static final class ProcessTreeRoot {

        private ProcessTreeRoot() {
        }

        public static void main(String[] args) throws Exception {
            Process child = new ProcessBuilder(
                    javaExecutable(),
                    "-cp",
                    System.getProperty("java.class.path"),
                    ProcessTreeLeaf.class.getName())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            System.out.println(child.pid());
            System.out.flush();
            Thread.sleep(TimeUnit.MINUTES.toMillis(5));
        }
    }

    public static final class ProcessTreeLeaf {

        private ProcessTreeLeaf() {
        }

        public static void main(String[] args) throws Exception {
            Thread.sleep(TimeUnit.MINUTES.toMillis(5));
        }
    }
}

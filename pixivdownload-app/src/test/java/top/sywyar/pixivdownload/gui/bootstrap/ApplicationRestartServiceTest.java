package top.sywyar.pixivdownload.gui.bootstrap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import top.sywyar.pixivdownload.common.Utf8ConsoleStreams;
import top.sywyar.pixivdownload.gui.GuiLauncher;
import top.sywyar.pixivdownload.gui.controller.GuiStatusController;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@DisplayName("应用完整重启交接")
class ApplicationRestartServiceTest {
    @TempDir Path directory;

    @Test
    @DisplayName("启动失败保留旧界面并允许重试，重复请求只拉起一个接替进程")
    void launchFailureDoesNotExitAndSuccessfulRequestsAreCoalesced() throws Exception {
        ProcessBuilder builder = mock(ProcessBuilder.class);
        var environment = new HashMap<String, String>();
        when(builder.environment()).thenReturn(environment);
        when(builder.start()).thenThrow(new IOException("launch denied")).thenReturn(mock(Process.class));
        List<Runnable> scheduled = new ArrayList<>();
        Runnable exit = mock(Runnable.class);
        var service = new ApplicationRestartService(TestI18nBeans.appMessages(), () -> builder, scheduled::add, exit);

        assertThat(service.requestRestart()).isFalse();
        assertThat(scheduled).isEmpty();
        verifyNoInteractions(exit);
        assertThat(service.requestRestart()).isTrue();
        assertThat(service.requestRestart()).isTrue();
        verify(builder, times(2)).start();
        assertThat(environment).containsEntry(ApplicationRestartService.PREVIOUS_PID_ENV,
                Long.toString(ProcessHandle.current().pid()));
        assertThat(scheduled).hasSize(1);
        verifyNoInteractions(exit);
        scheduled.get(0).run();
        verify(exit).run();
    }

    @Test
    @DisplayName("无法安排退出时结束等待中的接替进程，旧进程不退出")
    void schedulingFailureCancelsReplacement() throws Exception {
        ProcessBuilder builder = mock(ProcessBuilder.class);
        when(builder.environment()).thenReturn(new HashMap<>());
        Process replacement = mock(Process.class);
        when(builder.start()).thenReturn(replacement);
        Runnable exit = mock(Runnable.class);
        var service = new ApplicationRestartService(TestI18nBeans.appMessages(), () -> builder,
                task -> { throw new IllegalStateException("scheduler unavailable"); }, exit);
        assertThat(service.requestRestart()).isFalse();
        verify(replacement).destroy();
        verifyNoInteractions(exit);
    }

    @Test
    @DisplayName("原生启动器只接收应用参数，Java 入口保留 JVM、JAR 和带空格参数")
    void reconstructsNativeAndJavaCommandsWithoutOsArgumentQuery() throws Exception {
        List<String> arguments = List.of("--server.port=7001", "--download.root-folder=artwork folder");
        List<String> vmArguments = List.of("-Dfile.encoding=UTF-8", "-Dtest.value=with spaces");
        Path jar = Files.createFile(directory.resolve("application with spaces.jar"));
        assertThat(ApplicationRestartService.restartCommand("desktop.exe", arguments, vmArguments, jar.toFile(), "ignored"))
                .containsExactly("desktop.exe", "--server.port=7001", "--download.root-folder=artwork folder");
        assertThat(ApplicationRestartService.restartCommand("javaw.exe", arguments, vmArguments, jar.toFile(), "ignored"))
                .containsExactly("javaw.exe", "-Dfile.encoding=UTF-8", "-Dtest.value=with spaces", "-jar", jar.toString(),
                        "--server.port=7001", "--download.root-folder=artwork folder");
        assertThat(ApplicationRestartService.restartCommand("java", arguments, vmArguments, directory.toFile(), "classes path"))
                .containsExactly("java", "-Dfile.encoding=UTF-8", "-Dtest.value=with spaces", "-cp", "classes path",
                        GuiLauncher.class.getName(), "--server.port=7001", "--download.root-folder=artwork folder");
    }

    @Test
    @DisplayName("重启端点拒绝非本地来源，创建进程失败不能返回成功")
    void endpointReportsRestartAdmission() {
        var service = mock(ApplicationRestartService.class);
        var controller = new GuiStatusController(null, null, null, TestI18nBeans.appMessages(), null, null, service);
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.7");
        assertThat(controller.restart(request).getStatusCode().value()).isEqualTo(403);
        verifyNoInteractions(service);
        request.setRemoteAddr("127.0.0.1");
        when(service.requestRestart()).thenReturn(false, true);
        assertThat(controller.restart(request).getStatusCode().value()).isEqualTo(503);
        assertThat(controller.restart(request).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("真实进程退出及关闭钩子完成后才接管文件锁，等待超时不强杀旧进程")
    void replacementWaitsForActualProcessExit() throws Exception {
        Process parent = probe("parent", directory).start();
        try {
            awaitFile(directory.resolve("waiting"));
            assertThatThrownBy(() -> ApplicationRestartService.awaitPreviousProcess(
                    Long.toString(parent.pid()), Duration.ofMillis(30))).isInstanceOf(TimeoutException.class);
            assertThat(parent.isAlive()).isTrue();
            assertThat(directory.resolve("ready")).doesNotExist();
            parent.getOutputStream().write(1);
            parent.getOutputStream().flush();
            awaitFile(directory.resolve("closing"));
            assertThat(directory.resolve("ready")).doesNotExist();
            Files.writeString(directory.resolve("allow-close"), "", StandardCharsets.UTF_8);
            assertThat(parent.waitFor(15, TimeUnit.SECONDS)).isTrue();
            assertThat(parent.exitValue()).isZero();
            awaitFile(directory.resolve("ready"));
            ApplicationRestartService.awaitPreviousProcess(Long.toString(parent.pid()), Duration.ofSeconds(1));
        } finally {
            Files.writeString(directory.resolve("allow-close"), "", StandardCharsets.UTF_8);
            if (parent.isAlive()) parent.destroyForcibly();
            Path childPid = directory.resolve("child-pid");
            if (Files.exists(childPid)) {
                ProcessHandle.of(Long.parseLong(Files.readString(childPid))).ifPresent(ProcessHandle::destroyForcibly);
            }
        }
    }

    private static ProcessBuilder probe(String mode, Path directory) {
        String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        return new ProcessBuilder(java, "-Dfile.encoding=UTF-8", "-cp", System.getProperty("java.class.path"),
                Probe.class.getName(), mode, directory.toString())
                .redirectErrorStream(true).redirectOutput(directory.resolve(mode + ".log").toFile());
    }

    private static void awaitFile(Path path) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!Files.exists(path) && System.nanoTime() < deadline) Thread.sleep(20);
        assertThat(path).exists();
    }

    public static final class Probe {
        public static void main(String[] args) throws Exception {
            Utf8ConsoleStreams.install();
            Path directory = Path.of(args[1]);
            if (args[0].equals("parent")) {
                FileChannel channel = FileChannel.open(directory.resolve("runtime.lock"),
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                var lock = channel.lock();
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try {
                        Files.writeString(directory.resolve("closing"), "", StandardCharsets.UTF_8);
                        awaitFile(directory.resolve("allow-close"));
                        lock.release();
                        channel.close();
                    } catch (Exception failure) {
                        throw new AssertionError(failure);
                    }
                }));
                ProcessBuilder builder = probe("child", directory);
                builder.environment().put(ApplicationRestartService.PREVIOUS_PID_ENV,
                        Long.toString(ProcessHandle.current().pid()));
                Process child = builder.start();
                Files.writeString(directory.resolve("child-pid"), Long.toString(child.pid()), StandardCharsets.UTF_8);
                System.in.read();
                System.exit(0);
            } else {
                Files.writeString(directory.resolve("waiting"), "", StandardCharsets.UTF_8);
                ApplicationRestartService.awaitPreviousProcess();
                long predecessor = Long.parseLong(System.getenv(ApplicationRestartService.PREVIOUS_PID_ENV));
                if (ProcessHandle.of(predecessor).map(ProcessHandle::isAlive).orElse(false)) {
                    throw new AssertionError("Previous process is still alive");
                }
                try (FileChannel channel = FileChannel.open(directory.resolve("runtime.lock"), StandardOpenOption.WRITE);
                     var lock = channel.tryLock()) {
                    if (lock == null) throw new AssertionError("Previous process still owns runtime lock");
                    Files.writeString(directory.resolve("ready"), "", StandardCharsets.UTF_8);
                }
            }
        }
    }
}

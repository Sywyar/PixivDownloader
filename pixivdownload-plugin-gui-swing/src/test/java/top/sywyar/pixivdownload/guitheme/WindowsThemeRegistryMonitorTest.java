package top.sywyar.pixivdownload.guitheme;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import top.sywyar.pixivdownload.gui.theme.GuiThemeManager;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiPluginSnapshot;

import javax.swing.SwingUtilities;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledOnOs(OS.WINDOWS)
@DisplayName("Windows 主题监听的原生资源释放")
class WindowsThemeRegistryMonitorTest {
    @TempDir
    Path temp;

    @ParameterizedTest
    @ValueSource(ints = {0, 3})
    @DisplayName("快速停止并重启后回收全部旧监听线程")
    void rapidRestartReleasesOldWorkers(int pauseMillis) throws Exception {
        runProbe(Integer.toString(pauseMillis));
    }

    @Test
    @DisplayName("使用系统主题创建首窗失败后回收原生监听")
    void failedDesktopLaunchReleasesThemeWorker() throws Exception {
        runProbe("failed-launch");
    }

    @Test
    @DisplayName("从系统主题回退到不可用主题时停止原生监听")
    void unavailableThemeReleasesSystemWatcher() throws Exception {
        runProbe("fallback");
    }

    private void runProbe(String scenario) throws Exception {
        Path output = temp.resolve("monitor.log");
        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "-Dfile.encoding=UTF-8",
                "-cp", System.getProperty("java.class.path"),
                WindowsThemeRegistryMonitorTest.class.getName(),
                scenario
        ).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertThat(process.waitFor(20, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue())
                    .withFailMessage(Files.readString(output, StandardCharsets.UTF_8))
                    .isZero();
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    public static void main(String[] args) throws Exception {
        if (args[0].equals("failed-launch")) {
            GuiSwingPluginTest.failBeforeFirstWindow(new GuiSwingPlugin().guiThemes());
        } else if (args[0].equals("fallback")) {
            GuiSwingPlugin plugin = new GuiSwingPlugin();
            var snapshot = new DesktopUiPluginSnapshot(
                    plugin.id(), false, plugin.id(), 1, true, null, "",
                    plugin.guiThemes(), List.of(), List.of(), List.of(), List.of()
            );
            GuiThemeManager.applyBeforeFirstWindow("system", List.of(snapshot));
            GuiThemeManager.applyThemeId("unavailable");
            SwingUtilities.invokeAndWait(() -> { });
        } else {
            restartMonitor(Integer.parseInt(args[0]));
        }
        awaitWorkersStopped();
    }

    private static void restartMonitor(int pauseMillis) throws Exception {
        WindowsThemeRegistryMonitor monitor = new WindowsThemeRegistryMonitor(() -> { });
        try {
            for (int cycle = 0; cycle < 100; cycle++) {
                monitor.start();
                Thread.sleep(pauseMillis);
                monitor.stop();
                monitor.start();
                Thread.sleep(pauseMillis);
                monitor.stop();
            }
        } finally {
            monitor.stop();
        }
    }

    private static void awaitWorkersStopped() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        long remaining;
        do {
            remaining = Thread.getAllStackTraces().keySet().stream()
                    .filter(thread -> thread.isAlive()
                            && thread.getName().equals("gui-swing-registry-watcher"))
                    .count();
            if (remaining == 0) return;
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Theme listeners still running after stop: " + remaining);
    }
}

package top.sywyar.pixivdownload.gui.bootstrap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("启动窗口系统外观")
class StartupAppearanceTest {
    @Test
    @DisplayName("Windows 读取应用深浅色、文字放大和减少动态效果，显式主题优先")
    void windowsPreferences() {
        var appearance = StartupAppearance.detect("Windows 11", command -> switch (command.get(command.size() - 1)) {
            case "AppsUseLightTheme" -> "AppsUseLightTheme    REG_DWORD    0x0";
            case "TextScaleFactor" -> "TextScaleFactor    REG_DWORD    0x96";
            case "UserPreferencesMask" -> "UserPreferencesMask    REG_BINARY    9012038010000000";
            default -> "";
        }, key -> key.equals("win.highContrast.on"));
        assertThat(appearance).isEqualTo(new StartupAppearance(true, true, true, 1.5));
        assertThat(appearance.withTheme("light").dark()).isFalse();
        assertThat(appearance.withTheme("system").dark()).isTrue();
        assertThat(appearance.withTheme("unknown").dark()).isTrue();
    }

    @Test
    @DisplayName("macOS 读取系统深色及辅助功能偏好")
    void macPreferences() {
        var appearance = StartupAppearance.detect("Mac OS X", command -> command.contains("AppleInterfaceStyle")
                ? "Dark\n" : "{\n    reduceMotion = 1;\n    increaseContrast = 1;\n}\n", key -> null);
        assertThat(appearance).isEqualTo(new StartupAppearance(true, true, true, 1));
    }

    @Test
    @DisplayName("Linux 优先使用 Portal，保留深浅色、对比度、减少动态效果与文字比例")
    void portalPreferences() {
        var appearance = StartupAppearance.detect("Linux", command -> {
            assertThat(command.get(0)).isEqualTo("gdbus");
            return "({'org.freedesktop.appearance': {'color-scheme': <uint32 2>, 'contrast': <uint32 1>, 'reduced-motion': <uint32 1>},"
                    + " 'org.gnome.desktop.interface': {'text-scaling-factor': <1.5>}},)";
        }, key -> null);
        assertThat(appearance).isEqualTo(new StartupAppearance(false, true, true, 1.5));
    }

    @Test
    @DisplayName("缺少 Portal 时使用桌面偏好，查询失败保留可用默认值")
    void linuxFallback() {
        var appearance = StartupAppearance.detect("Linux", command -> switch (command.get(command.size() - 1)) {
            case "color-scheme" -> "'prefer-dark'";
            case "enable-animations" -> "false";
            case "text-scaling-factor" -> "1.25";
            default -> "";
        }, key -> null);
        assertThat(appearance).isEqualTo(new StartupAppearance(true, false, true, 1.25));
        assertThat(StartupAppearance.detect("Linux", command -> "", key -> null))
                .isEqualTo(new StartupAppearance(false, false, false, 1));
    }

    @ParameterizedTest
    @ValueSource(doubles = {0, -1, 100, Double.NaN, Double.POSITIVE_INFINITY})
    @DisplayName("无效文字比例不会制造无限或不可读的布局")
    void invalidTextScale(double value) {
        assertThat(StartupAppearance.validTextScale(value)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"normal", "flood", "failure", "timeout"})
    @DisplayName("真实子进程验证读取、非零退出、输出限制及截止时间")
    void boundedSystemCommand(String mode) {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        var command = List.of(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-Dfile.encoding=UTF-8", "-cp", System.getProperty("java.class.path"), CommandProbe.class.getName(), mode);
        long start = System.nanoTime();
        Duration budget = mode.equals("timeout") ? StartupAppearance.QUERY_BUDGET : Duration.ofSeconds(10);
        String value = StartupAppearance.query(command, start + budget.toNanos());
        assertThat(value).isEqualTo(mode.equals("normal") ? "dark" : "");
        if (mode.equals("timeout")) assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
    }

    @Test
    @DisplayName("已耗尽预算和线程中断不继续启动系统命令")
    void expiredBudgetAndInterrupt() {
        assertThat(StartupAppearance.query(List.of("missing-command"), System.nanoTime() - 1)).isEmpty();
        Thread.currentThread().interrupt();
        try {
            assertThat(StartupAppearance.query(List.of("missing-command"), System.nanoTime() + TimeUnit.SECONDS.toNanos(10))).isEmpty();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }

    public static final class CommandProbe {
        public static void main(String[] args) throws Exception {
            switch (args[0]) {
                case "normal" -> System.out.print("dark");
                case "failure" -> { System.out.print("dark"); System.exit(1); }
                case "flood" -> System.out.print("x".repeat(StartupAppearance.MAX_OUTPUT_BYTES + 1));
                case "timeout" -> Thread.sleep(30_000);
                default -> throw new IllegalArgumentException(args[0]);
            }
        }
    }
}

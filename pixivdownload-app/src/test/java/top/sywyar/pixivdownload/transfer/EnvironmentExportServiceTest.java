package top.sywyar.pixivdownload.transfer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("环境导出的真实停写交接与受控产物")
class EnvironmentExportServiceTest {
    @TempDir Path root;

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"context", "web", "restart-false", "restart-throws"})
    @DisplayName("无头 context 真正关闭后导出加密归档，重启在完成之后，篡改产物拒绝下载")
    void exportsInIsolatedProcess(String mode) throws Exception {
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dfile.encoding=UTF-8", "-Dpixivdownload.plugin-dev.enabled=false"));
        for (String directory : List.of("config", "state", "data", "instance", "plugins"))
            command.add("-Dpixivdownload." + directory + "-dir=" + root.resolve(directory));
        command.addAll(List.of("-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                EnvironmentExportTestEntry.class.getName(), root.toString(), mode));
        Path log = root.resolve("export.log");
        var process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            boolean exited = process.waitFor(60, TimeUnit.SECONDS);
            assertThat(exited).as(Files.readString(log)).isTrue();
            assertThat(process.exitValue()).as(Files.readString(log)).isZero();
        } finally { if (process.isAlive()) process.destroyForcibly().waitFor(); }
    }

    @Test
    @DisplayName("未确认重启和不安全目标在关闭后端前拒绝，失败也清理密码")
    void rejectsBeforeStopping() {
        var context = mock(ConfigurableApplicationContext.class);
        var service = new EnvironmentExportService(context, new String[0], session -> { throw new AssertionError(); }, () -> false);
        for (var request : List.of(
                new EnvironmentExportService.Request(root.resolve("archive").toString(), new char[]{'x'}, false, false, false, false, false),
                new EnvironmentExportService.Request("relative", new char[]{'x'}, false, false, false, false, true))) {
            assertThatThrownBy(() -> service.start(request)).isInstanceOf(IllegalArgumentException.class);
            assertThat(request.password()).containsOnly('\0');
        }
        verifyNoInteractions(context);
    }

    @Test
    @DisplayName("无头后端关闭未确认时不能进入离线导出")
    void refusesUnconfirmedShutdown() {
        var context = mock(ConfigurableApplicationContext.class);
        when(context.isActive()).thenReturn(true);
        when(context.getBeanProvider(top.sywyar.pixivdownload.plugin.lifecycle.ExternalPluginLifecycleCoordinator.class))
                .thenReturn(mock(org.springframework.beans.factory.ObjectProvider.class));
        assertThatThrownBy(() -> EnvironmentExportService.stopBackend(context)).isInstanceOf(java.io.IOException.class)
                .hasMessage("transfer.backend-not-stopped");
        verify(context).close();
    }
}

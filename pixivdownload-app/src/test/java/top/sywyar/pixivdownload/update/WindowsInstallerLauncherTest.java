package top.sywyar.pixivdownload.update;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("Windows 安装器系统壳启动")
class WindowsInstallerLauncherTest {
    @TempDir
    Path temp;

    @Test
    @DisplayName("保留系统取消和失败退出码，超时回收辅助进程")
    void preservesResultsAndBoundsTheWait() throws Exception {
        for (int code : new int[]{0, 1, WindowsInstallerLauncher.CANCELLED}) {
            Process helper = mock(Process.class);
            when(helper.waitFor(anyLong(), eq(TimeUnit.SECONDS))).thenReturn(true);
            when(helper.exitValue()).thenReturn(code);
            assertThat(WindowsInstallerLauncher.awaitLaunch(helper)).isEqualTo(code);
            verify(helper, never()).destroyForcibly();
        }
        Process timeout = mock(Process.class);
        when(timeout.isAlive()).thenReturn(true);
        assertThat(WindowsInstallerLauncher.awaitLaunch(timeout)).isEqualTo(WindowsInstallerLauncher.TIMED_OUT);
        verify(timeout).destroyForcibly();
    }

    @Test
    @DisplayName("中断等待保留中断标志并回收辅助进程")
    void interruptionIsNotLaunchSuccess() throws Exception {
        Process helper = mock(Process.class);
        when(helper.waitFor(anyLong(), eq(TimeUnit.SECONDS))).thenThrow(new InterruptedException());
        when(helper.isAlive()).thenReturn(true);
        try {
            assertThatThrownBy(() -> WindowsInstallerLauncher.awaitLaunch(helper)).isInstanceOf(IOException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(helper).destroyForcibly();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("真实系统壳启动含中文与特殊字符路径的无提权夹具，缺失文件失败")
    void launchesNativeFixtureWithoutInterpretingItsPath() throws Exception {
        Path source = temp.resolve("Fixture.cs");
        Files.writeString(source, """
                using System.IO;
                using System.Reflection;
                class Fixture {
                    static void Main() {
                        File.WriteAllText(Assembly.GetExecutingAssembly().Location + ".started", "ok");
                    }
                }
                """, StandardCharsets.UTF_8);
        Path manifest = temp.resolve("fixture.manifest");
        Files.writeString(manifest, """
                <assembly xmlns="urn:schemas-microsoft-com:asm.v1" manifestVersion="1.0">
                  <trustInfo xmlns="urn:schemas-microsoft-com:asm.v3"><security><requestedPrivileges>
                    <requestedExecutionLevel level="asInvoker" uiAccess="false"/>
                  </requestedPrivileges></security></trustInfo>
                </assembly>
                """, StandardCharsets.UTF_8);
        Path executable = temp.resolve("普通 ' $(fixture) & executable.exe");
        Path compiler = Path.of(System.getenv("SystemRoot"), "Microsoft.NET", "Framework64",
                "v4.0.30319", "csc.exe");
        Process compile = new ProcessBuilder(compiler.toString(), "/nologo", "/target:winexe",
                "/win32manifest:" + manifest, "/out:" + executable, source.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            assertThat(compile.waitFor(30, TimeUnit.SECONDS)).isTrue();
            assertThat(compile.exitValue()).isZero();
        } finally {
            if (compile.isAlive()) compile.destroyForcibly();
        }
        assertThat(WindowsInstallerLauncher.launch(executable)).isZero();
        Path marker = Path.of(executable + ".started");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!Files.exists(marker) && System.nanoTime() < deadline) Thread.sleep(20);
        assertThat(Files.readString(marker, StandardCharsets.UTF_8)).isEqualTo("ok");
        assertThat(WindowsInstallerLauncher.launch(temp.resolve("absent.exe"))).isNotZero();
    }
}

package top.sywyar.pixivdownload.update;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** 经 Windows 系统壳启动已验证的安装器，让其 manifest 触发所需的 UAC 流程。 */
final class WindowsInstallerLauncher {
    static final int CANCELLED = 1223;
    static final int TIMED_OUT = 1460;
    private static final long LAUNCH_TIMEOUT_SECONDS = 120;
    private static final String SCRIPT = """
            $ErrorActionPreference = 'Stop'
            try {
                $info = New-Object System.Diagnostics.ProcessStartInfo
                $info.FileName = $env:PIXIV_INSTALLER_PATH
                $info.WorkingDirectory = [System.IO.Path]::GetDirectoryName($info.FileName)
                $info.UseShellExecute = $true
                $info.Verb = 'open'
                $installerProcess = [System.Diagnostics.Process]::Start($info)
                if ($null -eq $installerProcess) { exit 1 }
                $installerProcess.Dispose()
                exit 0
            } catch {
                $cause = $_.Exception.GetBaseException()
                if ($cause -is [System.ComponentModel.Win32Exception] -and $cause.NativeErrorCode -eq 1223) {
                    exit 1223
                }
                exit 1
            }
            """;

    private WindowsInstallerLauncher() {
    }

    static int launch(Path installer) throws IOException {
        String systemRoot = System.getenv("SystemRoot");
        if (systemRoot == null || systemRoot.isBlank()) {
            throw new IOException("WINDOWS_SYSTEM_ROOT_MISSING");
        }
        Path powershell = Path.of(systemRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
        var builder = new ProcessBuilder(powershell.toString(), "-NoLogo", "-NoProfile", "-NonInteractive",
                "-WindowStyle", "Hidden", "-Command", SCRIPT)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        // 路径只作为环境值传入，不成为 PowerShell 代码或命令参数片段。
        builder.environment().put("PIXIV_INSTALLER_PATH", installer.toAbsolutePath().normalize().toString());
        return awaitLaunch(builder.start());
    }

    static int awaitLaunch(Process helper) throws IOException {
        try {
            if (!helper.waitFor(LAUNCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                return TIMED_OUT;
            }
            return helper.exitValue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("INSTALLER_LAUNCH_INTERRUPTED", e);
        } finally {
            // 只回收等待壳调用的辅助进程；成功拉起的安装器独立继续运行。
            if (helper.isAlive()) helper.destroyForcibly();
        }
    }
}

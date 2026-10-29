package top.sywyar.pixivdownload.update;

import top.sywyar.pixivdownload.common.PlainFilePathGuard;
import top.sywyar.pixivdownload.gui.AutoStartManager;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopEnvironmentTransfer.Installation;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopEnvironmentTransfer.InstallationKind;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 只读探测当前原生程序与安装登记；读取失败不推断为便携版，也不自动修改系统登记。 */
public final class WindowsInstallation {
    static final String MARKER = ".pixivdownload-installation";
    private static final int MAX_BYTES = 128 * 1024;
    private static final String QUERY = """
            $ErrorActionPreference = 'Stop'
            $base = [Microsoft.Win32.RegistryKey]::OpenBaseKey('LocalMachine', 'Registry64')
            try {
                foreach ($item in @(
                    @('SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\{4D4F3566-C6C0-4D24-9242-86059B2A84A5}_is1', 'InstallLocation'),
                    @('SOFTWARE\\sywyar\\PixivDownload', 'InstallLocation'),
                    @('SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\{4D4F3566-C6C0-4D24-9242-86059B2A84A5}_is1', 'UninstallString'))) {
                    $key = $base.OpenSubKey($item[0], $false)
                    try {
                        $value = if ($null -eq $key) { '' } else { [string]$key.GetValue($item[1], '') }
                        if ($value.Length -gt 32768) { throw 'REGISTRY_VALUE_TOO_LARGE' }
                        [Console]::WriteLine([Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($value)))
                    } finally { if ($null -ne $key) { $key.Dispose() } }
                }
            } finally { $base.Dispose() }
            """;

    private WindowsInstallation() {}

    public static Installation detect() {
        var executable = AutoStartManager.currentApplicationExecutable();
        if (executable.isEmpty()) return new Installation(InstallationKind.UNSUPPORTED, "", "");
        Path directory = executable.get().getParent();
        try { return inspect(directory, queryRegistry()); }
        catch (IOException | RuntimeException failure) {
            return new Installation(InstallationKind.UNKNOWN, directory.toString(), "");
        }
    }

    static Installation inspect(Path directory, List<String> registration) throws IOException {
        directory = directory.toAbsolutePath().normalize();
        if (registration.size() != 3) throw new IOException("INSTALLATION_REGISTRY_INVALID");
        if (!PlainFilePathGuard.isPlainDirectory(directory)) throw new IOException("INSTALLATION_DIRECTORY_INVALID");
        String registered = registration.get(0).isBlank() ? registration.get(1) : registration.get(0);
        String recorded = "";
        boolean marker = Files.exists(directory.resolve(MARKER), LinkOption.NOFOLLOW_LINKS);
        boolean pending = false;
        if (marker) {
            Path path = directory.resolve(MARKER);
            PlainFilePathGuard.requirePlainRegularFile(path);
            byte[] bytes;
            try (var input = Files.newInputStream(path)) { bytes = input.readNBytes(MAX_BYTES + 1); }
            if (bytes.length > MAX_BYTES) throw new IOException("INSTALLATION_MARKER_INVALID");
            String text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            if (text.startsWith("\uFEFF")) text = text.substring(1);
            var lines = text.lines().toList();
            if (lines.size() != 2) throw new IOException("INSTALLATION_MARKER_INVALID");
            pending = lines.get(0).equals("PixivDownload relocation 1");
            if (!pending && !lines.get(0).equals("PixivDownload installation 1")) throw new IOException("INSTALLATION_MARKER_INVALID");
            recorded = lines.get(1);
            if ((!pending || !recorded.isEmpty()) && !Path.of(recorded).isAbsolute()) throw new IOException("INSTALLATION_MARKER_INVALID");
        }
        boolean uninstaller = false;
        boolean hasUninstallFiles = false;
        int visited = 0;
        try (var entries = Files.newDirectoryStream(directory, "unins*")) {
            for (Path entry : entries) {
                if (++visited > 4000) throw new IOException("INSTALLATION_ENTRY_LIMIT");
                if (!entry.getFileName().toString().matches("(?i)unins[0-9]{3}\\.(dat|exe|msg)")) continue;
                hasUninstallFiles = true;
                PlainFilePathGuard.requirePlainRegularFile(entry);
                if (!entry.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".dat")) continue;
                Path exe = entry.resolveSibling(entry.getFileName().toString().replaceFirst("(?i)\\.dat$", ".exe"));
                if (!Files.exists(exe, LinkOption.NOFOLLOW_LINKS)) continue;
                PlainFilePathGuard.requirePlainRegularFile(exe);
                uninstaller = true;
            }
        }
        InstallationKind kind;
        if (pending) kind = InstallationKind.REPAIR_REQUIRED;
        else if (!marker && !hasUninstallFiles && !same(directory, registered)) kind = InstallationKind.PORTABLE;
        else if (!recorded.isEmpty() && !same(directory, recorded) || !registered.isEmpty() && !same(directory, registered))
            kind = InstallationKind.MOVED;
        else if (!uninstaller || !same(directory, registration.get(0)) || !same(directory, registration.get(1))
                || !uninstallerAt(directory, registration.get(2))) kind = InstallationKind.REPAIR_REQUIRED;
        else kind = InstallationKind.INSTALLED;
        return new Installation(kind, directory.toString(), registered.isEmpty() ? recorded : registered);
    }

    private static boolean same(Path directory, String value) {
        if (value.isBlank()) return false;
        Path path = Path.of(value);
        return path.isAbsolute() && directory.equals(path.normalize());
    }

    private static boolean uninstallerAt(Path directory, String command) {
        String file;
        if (command.startsWith("\"")) {
            int end = command.indexOf('"', 1);
            if (end < 0) return false;
            file = command.substring(1, end);
        } else file = command.split("\\s", 2)[0];
        if (file.isEmpty()) return false;
        Path path = Path.of(file).normalize();
        return path.isAbsolute() && directory.equals(path.getParent())
                && path.getFileName().toString().matches("(?i)unins[0-9]{3}\\.exe")
                && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
    }

    static List<String> queryRegistry() throws IOException {
        String root = System.getenv("SystemRoot");
        if (root == null || root.isBlank()) throw new IOException("WINDOWS_SYSTEM_ROOT_MISSING");
        Process process = new ProcessBuilder(Path.of(root, "System32", "WindowsPowerShell", "v1.0", "powershell.exe").toString(),
                "-NoLogo", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", QUERY)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        try (var input = process.getInputStream(); var output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            while (true) {
                if (System.nanoTime() >= deadline) throw new IOException("INSTALLATION_REGISTRY_TIMEOUT");
                int available = input.available();
                if (available > 0) {
                    int count = input.read(buffer, 0, Math.min(buffer.length, available));
                    if (count < 0) break;
                    if (output.size() + count > MAX_BYTES) throw new IOException("INSTALLATION_REGISTRY_LIMIT");
                    output.write(buffer, 0, count);
                } else if (!process.isAlive()) break;
                else Thread.sleep(10);
            }
            if (process.exitValue() != 0) throw new IOException("INSTALLATION_REGISTRY_FAILED");
            return output.toString(StandardCharsets.US_ASCII).lines()
                    .map(line -> new String(Base64.getDecoder().decode(line), StandardCharsets.UTF_8)).toList();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("INSTALLATION_REGISTRY_INTERRUPTED", interrupted);
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }
}

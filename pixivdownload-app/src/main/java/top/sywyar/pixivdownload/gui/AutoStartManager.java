package top.sywyar.pixivdownload.gui;

import lombok.extern.slf4j.Slf4j;
import top.sywyar.pixivdownload.common.AppInfo;
import top.sywyar.pixivdownload.i18n.MessageBundles;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Slf4j
public final class AutoStartManager {

    public static final String STARTUP_ARG = "--pixivdownload-startup";

    private static final String STARTUP_ARG_ALIAS = "--startup";
    private static final String APP_EXE_NAME = AppInfo.EXECUTABLE_NAME;
    private static final String SHORTCUT_NAME = AppInfo.SHORTCUT_NAME;
    private static final String STARTUP_FOLDER = "Microsoft\\Windows\\Start Menu\\Programs\\Startup";
    // 参数通过环境变量传入，而不是命令行位置参数：powershell.exe -Command 会把后续位置
    // 参数按空格重新拼回命令文本，导致含空格的路径（如 "...\Start Menu\..."）被截断。
    // 环境变量值不受命令行解析/分词影响，可安全携带空格。
    private static final String CREATE_SHORTCUT_SCRIPT = """
            $shell = New-Object -ComObject WScript.Shell
            $shortcut = $shell.CreateShortcut($env:PD_SHORTCUT_PATH)
            $shortcut.TargetPath = $env:PD_TARGET_PATH
            $shortcut.Arguments = $env:PD_ARGUMENTS
            $shortcut.WorkingDirectory = $env:PD_WORKING_DIR
            $shortcut.IconLocation = $env:PD_TARGET_PATH
            $shortcut.Save()
            """;

    private AutoStartManager() {
    }

    public static boolean isStartupLaunch(String[] args) {
        if (args == null) {
            return false;
        }
        for (String arg : args) {
            if (isStartupArg(arg)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isStartupArg(String arg) {
        return STARTUP_ARG.equals(arg) || STARTUP_ARG_ALIAS.equals(arg);
    }

    public static boolean isSupported() {
        return isWindows()
                && startupShortcutPath().isPresent()
                && currentApplicationExecutable().isPresent();
    }

    public static boolean isEnabled() {
        return startupShortcutPath()
                .map(Files::isRegularFile)
                .orElse(false);
    }

    public static void setEnabled(boolean enabled) throws IOException, InterruptedException {
        Optional<Path> shortcut = startupShortcutPath();
        if (shortcut.isEmpty()) {
            throw new IOException("Windows Startup folder is not available");
        }

        try {
            if (enabled) {
                Path executable = currentApplicationExecutable()
                        .orElseThrow(() -> new IOException("Current process is not " + AppInfo.EXECUTABLE_NAME));
                createShortcut(shortcut.get(), executable);
                log.info(MessageBundles.get("gui.autostart.log.enabled", shortcut.get()));
            } else {
                boolean removed = Files.deleteIfExists(shortcut.get());
                log.info(MessageBundles.get(removed
                        ? "gui.autostart.log.disabled.removed"
                        : "gui.autostart.log.disabled.absent", shortcut.get()));
            }
        } catch (IOException | InterruptedException | RuntimeException e) {
            log.error(MessageBundles.get("gui.autostart.log.set-failed",
                    enabled, shortcut.get(), e.getMessage()), e);
            throw e;
        }
    }

    public static Optional<Path> currentApplicationExecutable() {
        if (!isWindows()) {
            return Optional.empty();
        }

        return ProcessHandle.current().info().command()
                .flatMap(AutoStartManager::normalizeApplicationExecutable);
    }

    static Optional<Path> normalizeApplicationExecutable(String command) {
        if (command == null || command.isBlank()) {
            return Optional.empty();
        }

        Path executable;
        try {
            executable = Path.of(command).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return Optional.empty();
        }

        if (!Files.isRegularFile(executable)) {
            return Optional.empty();
        }

        String fileName = executable.getFileName().toString();
        String lowerName = fileName.toLowerCase(Locale.ROOT);
        if (!lowerName.endsWith(".exe")) {
            return Optional.empty();
        }
        if (lowerName.equals("java.exe") || lowerName.equals("javaw.exe")) {
            return Optional.empty();
        }
        if (!fileName.equalsIgnoreCase(APP_EXE_NAME) && System.getProperty("jpackage.app-version") == null) {
            return Optional.empty();
        }

        return Optional.of(executable);
    }

    static Optional<Path> startupShortcutPath() {
        if (!isWindows()) {
            return Optional.empty();
        }

        String appData = System.getenv("APPDATA");
        if (appData == null || appData.isBlank()) {
            return Optional.empty();
        }

        return Optional.of(Path.of(appData, STARTUP_FOLDER, SHORTCUT_NAME).toAbsolutePath().normalize());
    }

    private static void createShortcut(Path shortcut, Path executable) throws IOException, InterruptedException {
        Files.createDirectories(shortcut.getParent());

        String workingDirectory = executable.getParent() == null
                ? executable.toAbsolutePath().getParent().toString()
                : executable.getParent().toString();

        ProcessBuilder builder = new ProcessBuilder(List.of(
                powershellExecutable(),
                "-NoProfile",
                "-NonInteractive",
                "-ExecutionPolicy",
                "Bypass",
                "-Command",
                CREATE_SHORTCUT_SCRIPT
        )).redirectErrorStream(true);
        builder.environment().put("PD_SHORTCUT_PATH", shortcut.toString());
        builder.environment().put("PD_TARGET_PATH", executable.toString());
        builder.environment().put("PD_ARGUMENTS", STARTUP_ARG);
        builder.environment().put("PD_WORKING_DIR", workingDirectory);
        Process process = builder.start();

        byte[] outputBytes = process.getInputStream().readAllBytes();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            // Windows PowerShell 在 stdout 被重定向时使用控制台 OEM 代码页（中文系统为 GBK），
            // 不是 UTF-8；用 UTF-8 解码会把中文异常信息变成 U+FFFD（弹窗里显示为空心方框）。
            String output = new String(outputBytes, consoleOutputCharset()).trim();
            throw new IOException(output.isBlank()
                    ? "PowerShell shortcut creation failed with exit code " + exitCode
                    : output);
        }
        if (!Files.isRegularFile(shortcut)) {
            throw new IOException("Startup shortcut was not created");
        }
    }

    /**
     * 解码外部控制台进程（如 powershell.exe）输出所用的字符集。
     * JDK 17+ 通过 {@code native.encoding} 暴露宿主机原生字符集（中文 Windows 为 GBK/MS936），
     * 解析失败时回退到 JVM 默认字符集。
     */
    private static Charset consoleOutputCharset() {
        String nativeEncoding = System.getProperty("native.encoding");
        if (nativeEncoding != null && !nativeEncoding.isBlank()) {
            try {
                return Charset.forName(nativeEncoding.trim());
            } catch (RuntimeException ignored) {
                // 回退到默认字符集
            }
        }
        return Charset.defaultCharset();
    }

    private static String powershellExecutable() {
        String systemRoot = System.getenv("SystemRoot");
        if (systemRoot != null && !systemRoot.isBlank()) {
            Path powershell = Path.of(systemRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
            if (Files.isRegularFile(powershell)) {
                return powershell.toString();
            }
        }
        return "powershell.exe";
    }

    private static boolean isWindows() {
        String osName = System.getProperty("os.name", "");
        return osName.toLowerCase(Locale.ROOT).contains("win");
    }
}

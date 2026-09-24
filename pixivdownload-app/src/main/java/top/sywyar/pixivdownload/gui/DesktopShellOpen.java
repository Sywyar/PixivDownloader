package top.sywyar.pixivdownload.gui;

import top.sywyar.pixivdownload.common.PlainFilePathGuard;
import top.sywyar.pixivdownload.i18n.MessageBundles;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 桌面壳打开本地路径 / 外部 URI 的统一入口。
 *
 * <p>两条不变量：
 * <ul>
 *   <li><b>不阻塞调用线程</b>：Windows 交给独立的 {@code explorer.exe} 子进程，其它平台把
 *       {@link Desktop#open(java.io.File)} / {@link Desktop#browse(URI)} 提交到守护线程池，两处都只等待
 *       {@link #WAIT_TIMEOUT} 后放弃。Windows 上把目录重解析点（目录符号链接、Junction）直接交给
 *       {@code Desktop.open} 会在原生 {@code ShellExecute} 中无限阻塞，因此这类调用不能留在调用线程上。</li>
 *   <li><b>先解析重解析点</b>：目标是链接 / Junction 时先得到真实目标再交给系统壳；无法解析时失败关闭，
 *       不回退到原路径，避免把已知会卡住的位置再次交给 Shell。</li>
 * </ul>
 */
final class DesktopShellOpen {

    /** 单次 Shell 打开的最长等待时间；超时只放弃等待，不撤回已经交给系统壳的请求。 */
    static final Duration WAIT_TIMEOUT = Duration.ofSeconds(5);

    private static final boolean WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT)
            .contains("win");

    private static final ExecutorService SHELL_CALLS = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "desktop-shell-open");
        thread.setDaemon(true);
        return thread;
    });

    private DesktopShellOpen() {
    }

    /** 把待打开的位置解析为普通目录 / 普通文件；链接与 Junction 归还其真实目标。 */
    static Path resolveTarget(Path path) throws IOException {
        Path normalized = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        if (PlainFilePathGuard.isPlainDirectory(normalized)
                || PlainFilePathGuard.isPlainRegularFile(normalized)) {
            return normalized;
        }
        try {
            return normalized.toRealPath();
        } catch (IOException unresolvable) {
            throw new IOException(
                    MessageBundles.get("gui.desktop.open-unresolvable", normalized), unresolvable);
        }
    }

    /** 用系统默认方式打开本地目录 / 文件。 */
    static void openLocalPath(Path path) throws IOException {
        Path target = resolveTarget(path);
        if (WINDOWS) {
            openWithWindowsShell(target);
            return;
        }
        awaitShellCall(() -> {
            Desktop.getDesktop().open(target.toFile());
            return null;
        }, target);
    }

    /** 用系统默认程序打开外部 URI。 */
    static void openExternalUri(URI uri) throws IOException {
        URI target = Objects.requireNonNull(uri, "uri");
        awaitShellCall(() -> {
            Desktop.getDesktop().browse(target);
            return null;
        }, target);
    }

    private static void openWithWindowsShell(Path target) throws IOException {
        Process process = new ProcessBuilder(windowsShellCommand(), target.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectErrorStream(true)
                .start();
        try {
            // explorer.exe 把请求交给常驻 shell 后立即退出；退出码不表达打开结果，因此只看它是否返回。
            if (!awaitExit(process, WAIT_TIMEOUT)) {
                throw new IOException(MessageBundles.get("gui.desktop.open-timeout", target));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException(MessageBundles.get("gui.desktop.open-timeout", target), interrupted);
        } finally {
            if (process.isAlive()) {
                process.destroy();
            }
        }
    }

    /** 在给定上限内等待子进程退出；到达上限返回 {@code false}，由调用方决定如何处理。 */
    static boolean awaitExit(Process process, Duration timeout) throws InterruptedException {
        return process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static void awaitShellCall(Callable<Void> call, Object target) throws IOException {
        Future<?> pending = SHELL_CALLS.submit(call);
        try {
            pending.get(WAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            pending.cancel(true);
            throw new IOException(MessageBundles.get("gui.desktop.open-timeout", target), timeout);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            if (cause instanceof IOException ioFailure) {
                throw ioFailure;
            }
            throw new IOException(cause.getMessage(), cause);
        } catch (InterruptedException interrupted) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new IOException(MessageBundles.get("gui.desktop.open-timeout", target), interrupted);
        }
    }

    private static String windowsShellCommand() {
        String systemRoot = System.getenv("SystemRoot");
        if (systemRoot == null || systemRoot.isBlank()) {
            return "explorer.exe";
        }
        Path candidate = Path.of(systemRoot, "explorer.exe");
        return Files.isRegularFile(candidate) ? candidate.toString() : "explorer.exe";
    }
}

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
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 桌面壳打开本地路径 / 外部 URI 的统一入口。
 *
 * <p>三条不变量：
 * <ul>
 *   <li><b>整个打开动作共用一个截止时间账户</b>：在入口创建一次 {@link #WAIT_TIMEOUT} 预算，路径准备
 *       （属性读取、{@code toRealPath()}）消耗的时间会从同一预算里扣掉，Shell 执行与子进程退出等待
 *       只取剩余额度，不再各自从零计时。注意这只保证「不会在路径准备之后再白等一个完整周期」，
 *       并<b>不</b>构成调用方等待时长的硬上限——见下文剩余限制。</li>
 *   <li><b>不阻塞调用线程</b>：Windows 交给独立的 {@code explorer.exe} 子进程，其它平台把
 *       {@link Desktop#open(java.io.File)} / {@link Desktop#browse(URI)} 提交到有界线程池。Windows 上把目录
 *       重解析点（目录符号链接、Junction）直接交给 {@code Desktop.open} 会在原生 {@code ShellExecute} 中
 *       无限阻塞，因此这类调用不能留在调用线程上。</li>
 *   <li><b>有界资源</b>：打开任务使用固定上限的工作线程池，超过上限即拒绝新请求而不是无限创建线程；
 *       同一目标在同一时刻只保留一个未完成请求，重复点击合并到既有任务。超时只放弃等待，
 *       已提交给系统壳的请求不撤回；忽略中断的挂起任务最多占用 {@link #MAX_IN_FLIGHT_SHELL_CALLS} 个工作线程，
 *       不会无界增长。合并到同一目标的等待方若被先放弃的调用方取消，会按超时错误返回而不是抛出未检查异常。</li>
 * </ul>
 *
 * <p>剩余限制：路径准备走的是平台文件系统调用，既不能被中断、也不能被抢占，因此
 * {@link #WAIT_TIMEOUT} 只约束**会检查该预算的阶段**（Shell 执行与子进程退出等待）；调用方等待的总时长
 * 可以因为路径准备自身耗时而超过它，超时并不意味着这次打开动作已经停止。Windows 上被放弃的
 * {@code explorer.exe} 子进程会被主动销毁，但它派生的 Shell 动作无法撤回。
 */
final class DesktopShellOpen {

    /**
     * 单次打开的共享时间预算，由路径准备、Shell 执行与子进程退出等待共同消耗。
     *
     * <p>它是这三者的共同额度，但不是调用方等待时长的硬上限：路径准备阶段不可中断，
     * 其自身耗时可以超过该值。详见类注释的剩余限制。
     */
    static final Duration WAIT_TIMEOUT = Duration.ofSeconds(5);

    /**
     * 同时未完成的 Shell 调用上限。
     *
     * <p>{@code Desktop.open} / {@code Desktop.browse} 在原生实现里可能忽略中断，超时后线程不会返回；
     * 有界池保证这类任务最多占用这么多线程，之后的新请求直接被拒绝，而不是继续创建线程。
     */
    static final int MAX_IN_FLIGHT_SHELL_CALLS = 4;

    private static final boolean WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT)
            .contains("win");

    private static final AtomicInteger SHELL_THREAD_SEQUENCE = new AtomicInteger();

    private static final ThreadFactory SHELL_THREADS = runnable -> {
        Thread thread = new Thread(runnable, "desktop-shell-open-" + SHELL_THREAD_SEQUENCE.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    };

    private static final ExecutorService SHELL_CALLS = createShellExecutor();

    private static ExecutorService createShellExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                0, MAX_IN_FLIGHT_SHELL_CALLS,
                30L, TimeUnit.SECONDS,
                // 不排队：四个工作线程都占用时立即拒绝，避免队列再接纳额外任务。
                new SynchronousQueue<>(),
                SHELL_THREADS);
        return executor;
    }

    /** 正在进行的打开请求，按目标合并重复请求；键为目标的规范化文本。 */
    private static final Map<String, FutureTask<Void>> IN_FLIGHT = new ConcurrentHashMap<>();

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
        Deadline deadline = Deadline.starting(WAIT_TIMEOUT);
        Path target = resolveTarget(path);
        if (WINDOWS) {
            openWithWindowsShell(target, deadline);
            return;
        }
        awaitShellCall(() -> {
            Desktop.getDesktop().open(target.toFile());
            return null;
        }, target, deadline);
    }

    /** 用系统默认程序打开外部 URI。 */
    static void openExternalUri(URI uri) throws IOException {
        URI target = Objects.requireNonNull(uri, "uri");
        awaitShellCall(() -> {
            Desktop.getDesktop().browse(target);
            return null;
        }, target, Deadline.starting(WAIT_TIMEOUT));
    }

    private static void openWithWindowsShell(Path target, Deadline deadline) throws IOException {
        String command = windowsShellCommand();
        try {
            awaitShellCall(() -> {
                Process process = new ProcessBuilder(command, target.toString())
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectErrorStream(true)
                        .start();
                try {
                    // explorer.exe 把请求交给常驻 shell 后立即退出；退出码不表达打开结果，只看它是否返回。
                    if (!awaitExit(process, deadline.remainingOrMinimum())) {
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
                return null;
            }, target, deadline);
        } catch (RejectedExecutionException saturated) {
            throw new IOException(MessageBundles.get("gui.desktop.open-busy", target), saturated);
        }
    }

    /** 在给定上限内等待子进程退出；到达上限返回 {@code false}，由调用方决定如何处理。 */
    static boolean awaitExit(Process process, Duration timeout) throws InterruptedException {
        return process.waitFor(Math.max(timeout.toMillis(), 1L), TimeUnit.MILLISECONDS);
    }

    /**
     * 在有界池里执行一次 Shell 调用，并只等待共享截止时间内的剩余额度。
     *
     * <p>同一目标已有未完成请求时合并到该请求，避免连续点击无界增加任务。
     *
     * <p>包内可见以便回归测试直接覆盖「合并 + 取消」这条路径；本类本身不对外暴露。
     */
    static void awaitShellCall(Callable<Void> call, Object target, Deadline deadline) throws IOException {
        if (deadline.expired()) {
            throw new IOException(MessageBundles.get("gui.desktop.open-timeout", target));
        }
        String key = deduplicationKey(target);
        FutureTask<Void> task = new FutureTask<>(() -> {
            if (deadline.expired()) {
                throw new IOException(MessageBundles.get("gui.desktop.open-timeout", target));
            }
            return call.call();
        });
        FutureTask<Void> existing = IN_FLIGHT.putIfAbsent(key, task);
        if (existing != null) {
            // 同一目标已有未完成请求：合并等待，不重复提交。
            task = existing;
        } else {
            try {
                SHELL_CALLS.execute(task);
            } catch (RejectedExecutionException saturated) {
                IN_FLIGHT.remove(key, task);
                throw new IOException(MessageBundles.get("gui.desktop.open-busy", target), saturated);
            }
        }

        try {
            task.get(deadline.remainingOrMinimum().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            task.cancel(true);
            throw new IOException(MessageBundles.get("gui.desktop.open-timeout", target), timeout);
        } catch (CancellationException abandoned) {
            // 合并到同一目标的请求可能被先放弃的调用方取消（例如超时或线程被中断）。
            // 对本调用方而言等价于这次打开没有完成，必须转成 IOException，
            // 否则会被当成未检查异常穿透到界面层。
            throw new IOException(MessageBundles.get("gui.desktop.open-timeout", target), abandoned);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            if (cause instanceof IOException ioFailure) {
                throw ioFailure;
            }
            if (cause instanceof RejectedExecutionException saturated) {
                throw new IOException(MessageBundles.get("gui.desktop.open-busy", target), saturated);
            }
            throw new IOException(cause.getMessage(), cause);
        } catch (InterruptedException interrupted) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            throw new IOException(MessageBundles.get("gui.desktop.open-timeout", target), interrupted);
        } finally {
            if (task.isDone()) {
                IN_FLIGHT.remove(key, task);
            }
        }
    }

    /** 合并重复请求使用的键：同一目标、同一类动作视为同一请求。 */
    private static String deduplicationKey(Object target) {
        if (target instanceof Path path) {
            return "path:" + path;
        }
        if (target instanceof URI uri) {
            return "uri:" + uri.normalize();
        }
        return "other:" + target;
    }

    private static String windowsShellCommand() {
        String systemRoot = System.getenv("SystemRoot");
        if (systemRoot == null || systemRoot.isBlank()) {
            return "explorer.exe";
        }
        Path candidate = Path.of(systemRoot, "explorer.exe");
        return Files.isRegularFile(candidate) ? candidate.toString() : "explorer.exe";
    }

    /** 共享截止时间：路径准备、解析与 Shell 等待都从这里取剩余额度。 */
    static final class Deadline {

        private final long budgetNanos;
        private final long startedAtNanos;

        private Deadline(long budgetNanos, long startedAtNanos) {
            this.budgetNanos = budgetNanos;
            this.startedAtNanos = startedAtNanos;
        }

        static Deadline starting(Duration budget) {
            return new Deadline(budget.toNanos(), System.nanoTime());
        }

        /** 自创建起已经过去的时间；用于诊断与测试。 */
        Duration elapsed() {
            return Duration.ofNanos(Math.max(0L, System.nanoTime() - startedAtNanos));
        }

        /** 返回剩余额度；已经耗尽时返回 0，调用方据此立即超时而不是继续等待。 */
        Duration remaining() {
            long remaining = budgetNanos - (System.nanoTime() - startedAtNanos);
            return remaining <= 0L ? Duration.ZERO : Duration.ofNanos(remaining);
        }

        /** 返回剩余额度，至少 1 毫秒，避免把 0 传给不接受 0 的等待 API。 */
        Duration remainingOrMinimum() {
            Duration remaining = remaining();
            return remaining.isZero() ? Duration.ofMillis(1L) : remaining;
        }

        boolean expired() {
            return remaining().isZero();
        }
    }
}

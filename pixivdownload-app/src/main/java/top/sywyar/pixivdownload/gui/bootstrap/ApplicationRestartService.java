package top.sywyar.pixivdownload.gui.bootstrap;

import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.system.ApplicationHome;
import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.gui.GuiLauncher;
import top.sywyar.pixivdownload.i18n.AppMessages;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** 完整进程重启的交接；新进程不与旧进程的窗口、插件和实例锁并行初始化。 */
@Service
public final class ApplicationRestartService {
    static final String PREVIOUS_PID_ENV = "PIXIVDOWNLOAD_RESTART_PREVIOUS_PID";
    private static final Duration PREVIOUS_EXIT_TIMEOUT = Duration.ofMinutes(2);
    private static volatile List<String> applicationArguments;
    private static final AtomicReference<String> PROCESS_REQUEST = new AtomicReference<>();

    private final AtomicReference<String> requested;
    private final AppMessages messages;
    private final Supplier<ProcessBuilder> processFactory;
    private final Consumer<Runnable> exitScheduler;
    private final Runnable exitRequest;

    @Autowired
    public ApplicationRestartService(AppMessages messages) {
        this(messages, ApplicationRestartService::restartProcess,
                ApplicationRestartService::scheduleExit, GuiLauncher::requestApplicationExit, PROCESS_REQUEST);
    }

    /** Spring 尚未启动时复用相同的进程交接和请求合并状态。 */
    public static ApplicationRestartService forBootstrap() {
        return new ApplicationRestartService(null);
    }

    ApplicationRestartService(
            AppMessages messages,
            Supplier<ProcessBuilder> processFactory,
            Consumer<Runnable> exitScheduler,
            Runnable exitRequest
    ) {
        this(messages, processFactory, exitScheduler, exitRequest, new AtomicReference<>());
    }

    private ApplicationRestartService(
            AppMessages messages,
            Supplier<ProcessBuilder> processFactory,
            Consumer<Runnable> exitScheduler,
            Runnable exitRequest,
            AtomicReference<String> requested
    ) {
        this.requested = requested;
        this.messages = messages;
        this.processFactory = processFactory;
        this.exitScheduler = exitScheduler;
        this.exitRequest = exitRequest;
    }

    public static void captureArguments(String[] args) {
        applicationArguments = List.copyOf(Arrays.asList(args));
    }

    /** 必须早于日志会话、实例锁和插件运行时初始化。 */
    public static void awaitPreviousProcess() throws Exception {
        awaitPreviousProcess(System.getenv(PREVIOUS_PID_ENV), PREVIOUS_EXIT_TIMEOUT);
    }

    static void awaitPreviousProcess(String previousPid, Duration timeout) throws Exception {
        if (previousPid == null || previousPid.isBlank()) return;
        long pid = Long.parseLong(previousPid);
        if (pid <= 0 || pid == ProcessHandle.current().pid()) {
            throw new IllegalArgumentException("Invalid restart predecessor");
        }
        var previous = ProcessHandle.of(pid);
        if (previous.isPresent()) {
            previous.get().onExit().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    public boolean requestRestart() {
        if (!requested.compareAndSet(null, "restart")) return "restart".equals(requested.get());
        Process replacement = null;
        try {
            ProcessBuilder builder = processFactory.get();
            builder.environment().put(PREVIOUS_PID_ENV, Long.toString(ProcessHandle.current().pid()));
            replacement = builder.start();
            exitScheduler.accept(exitRequest);
            return true;
        } catch (IOException | RuntimeException failure) {
            if (replacement != null) replacement.destroy();
            requested.set(null);
            LoggerFactory.getLogger(ApplicationRestartService.class).warn(
                    messages != null ? messages.getForLog("gui.controller.log.restart.command-failed", failure.getMessage())
                            : top.sywyar.pixivdownload.i18n.MessageBundles.getForLog(
                                    "gui.controller.log.restart.command-failed", failure.getMessage()), failure);
            return false;
        }
    }

    public boolean requestExit() {
        if (!requested.compareAndSet(null, "exit")) return "exit".equals(requested.get());
        try {
            exitScheduler.accept(exitRequest);
            return true;
        } catch (RuntimeException failure) {
            requested.set(null);
            LoggerFactory.getLogger(ApplicationRestartService.class).warn(
                    top.sywyar.pixivdownload.i18n.MessageBundles.getForLog(
                            "recovery.action.failed"), failure);
            return false;
        }
    }

    private static ProcessBuilder restartProcess() {
        List<String> arguments = applicationArguments;
        if (arguments == null) throw new IllegalStateException("Application entry arguments are unavailable");
        String executable = ProcessHandle.current().info().command()
                .orElseThrow(() -> new IllegalStateException("Application executable is unavailable"));
        return new ProcessBuilder(restartCommand(
                executable,
                arguments,
                ManagementFactory.getRuntimeMXBean().getInputArguments(),
                new ApplicationHome(GuiLauncher.class).getSource(),
                System.getProperty("java.class.path")
        )).directory(Path.of("").toAbsolutePath().toFile()).inheritIO();
    }

    static List<String> restartCommand(
            String executable,
            List<String> arguments,
            List<String> vmArguments,
            File source,
            String classPath
    ) {
        var command = new ArrayList<String>();
        command.add(executable);
        String name = Path.of(executable).getFileName().toString().toLowerCase(Locale.ROOT);
        if (List.of("java", "javaw", "java.exe", "javaw.exe").contains(name)) {
            // Windows 的 ProcessHandle.Info 不提供 arguments；使用 JVM 参数和入口捕获的应用参数。
            command.addAll(vmArguments);
            if (source != null && source.isFile() && source.getName().endsWith(".jar")) {
                command.add("-jar");
                command.add(source.getAbsolutePath());
            } else {
                command.addAll(List.of("-cp", classPath, GuiLauncher.class.getName()));
            }
        }
        command.addAll(arguments);
        return List.copyOf(command);
    }

    private static void scheduleExit(Runnable action) {
        Thread thread = new Thread(() -> {
            try {
                // 只为 HTTP 响应留出返回时间；新进程通过 OS 进程退出信号等待资源释放。
                Thread.sleep(500);
                action.run();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }, "gui-restart");
        thread.setDaemon(false);
        thread.start();
    }
}

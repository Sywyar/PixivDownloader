package top.sywyar.pixivdownload.gui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.sywyar.pixivdownload.PixivDownloadApplication;
import top.sywyar.pixivdownload.cli.CliSetupCommand;
import top.sywyar.pixivdownload.common.AppVersion;
import top.sywyar.pixivdownload.common.Utf8ConsoleStreams;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.download.db.DatabaseSchemaInspector;
import top.sywyar.pixivdownload.gui.config.ConfigFileEditor;
import top.sywyar.pixivdownload.gui.i18n.GuiMessages;
import top.sywyar.pixivdownload.gui.theme.FlatLafSetup;
import top.sywyar.pixivdownload.i18n.MessageBundles;
import top.sywyar.pixivdownload.i18n.SystemLocaleDetector;
import top.sywyar.pixivdownload.tools.ArtworksBackFill;

import javax.swing.*;
import java.awt.*;
import java.io.FileNotFoundException;
import java.net.BindException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * GUI 模式入口点。
 * <ul>
 *   <li>默认启动 GUI + 后台 Spring Boot</li>
 *   <li>{@code --no-gui}：纯命令行模式（服务器/Docker 场景）</li>
 *   <li>无显示设备（{@code GraphicsEnvironment.isHeadless()}）：自动降级为命令行</li>
 * </ul>
 * Spring Boot fat-jar 的 {@code Start-Class} 指向此类（pom.xml 已配置）。
 *
 * <h3>日志文件策略</h3>
 * <p>每次启动在 {@code log/} 目录生成两份内容相同的日志：
 * <ul>
 *   <li>{@code log/latest.log} — 始终代表本次运行</li>
 *   <li>{@code log/pixiv-download_YYYY-MM-DD_HHmmss.log} — 带时间戳的会话存档</li>
 * </ul>
 * 仅保留最近 {@value #LOG_HISTORY_COUNT} 份时间戳文件，多余的在启动时自动删除。
 *
 * <h3>实现关键</h3>
 * <p>logback 在第一次 {@code getLogger()} 调用时完成初始化并读取 {@code logback.xml}。
 * 为确保 {@code logback.xml} 中的 {@code ${LOG_TIMESTAMP}} 占位符可以正确解析，
 * 本类故意不使用 {@code @Slf4j}，而是在 {@code main()} 方法体内、完成系统属性写入后
 * 再获取 logger，让 logback 的初始化晚于 {@code System.setProperty()} 调用。
 *
 * <p>同时设置 {@code org.springframework.boot.logging.LoggingSystem=none}，禁止
 * Spring Boot 接管日志系统。否则 Spring Boot 启动时会重新初始化 logback，导致
 * {@code append=false} 的 HTML appender 截断文件，丢失 Spring Boot 启动前
 * （包括 {@code PixivDownload 启动中}）的日志行。本项目所有日志配置都在
 * {@code logback.xml} 中，未使用任何 {@code logging.*} 属性，因此禁用 Spring Boot
 * 日志接管是安全的。
 */
public class GuiLauncher {

    // logger 故意不声明为 static final 字段，避免类加载时触发 logback 提前初始化
    private static Logger log;

    private static final String LOG_DIR = "log";
    private static final String LOG_HTML_DIR = LOG_DIR + "/html";
    private static final String LOG_LATEST = LOG_DIR + "/latest.log";
    private static final String LOG_HTML_LATEST = LOG_HTML_DIR + "/latest.html";
    private static final String LOG_SESSION_PREFIX = "pixiv-download_";
    /**
     * 保留最近的会话日志数量（不含 latest.log / latest.html）
     */
    private static final int LOG_HISTORY_COUNT = 5;
    private static final int DEFAULT_PORT = 6999;
    private static final String DEFAULT_ROOT = RuntimeFiles.DEFAULT_DOWNLOAD_ROOT;

    /**
     * 标记本次运行是否为无 GUI（headless / {@code --no-gui}）模式。
     * 由 {@code BrowserLauncher} 读取：仅 nogui 模式才自动打开浏览器到 setup 页，
     * GUI 模式改由「首页」引导内完成配置。{@code --no-gui} 会被 filterArgs 过滤掉，
     * 无法经 ApplicationArguments 传递，故用系统属性桥接。
     */
    public static final String HEADLESS_PROPERTY = "pixivdownload.headless";

    public static void main(String[] args) throws Exception {
        // ── 0. 统一标准输出/错误流为 UTF-8（必须先于 logback 初始化与任何打印）──────
        //    logback 的 ConsoleAppender 在初始化时会捕获当时的 System.out，故须最先执行。
        Utf8ConsoleStreams.install();

        // ── 0a. 全局 locale 检测（必须先于 logback 初始化）────────────────────────
        //    检测器内部不允许使用 SLF4J / @Slf4j 类；通过 Locale.setDefault 写回，
        //    使后续 HtmlLogLayout / GuiMessages / getForLog 拿到统一信号。
        SystemLocaleDetector.detectAndApply();

        // ── 0b. 在 logback 初始化前完成日志目录/属性准备 ─────────────────────────
        //    顺序不可颠倒：必须先于任何 getLogger() / log.xxx() 调用
        prepareLogging();

        // ── 触发 logback 初始化（此时 LOG_TIMESTAMP 已就绪）─────────────────────
        log = LoggerFactory.getLogger(GuiLauncher.class);
        log.info(logMessage("gui.launcher.log.version",
                AppVersion.getDisplayVersionOrDefault(logMessage("app.version.unknown"))));
        log.info(logMessage("gui.launcher.log.starting", Arrays.toString(args)));

        // ── 安装全局未捕获异常处理器 ────────────────────────────────────────────
        //    jpackage 窗口化 exe 没有控制台，stderr 不可见。若不在此兜底，
        //    EDT / 后台线程上的异常会被静默吞掉：进程几秒后无痕退出，
        //    日志只停在上面两行。安装后任何线程（含 AWT-EventQueue）的
        //    未捕获异常都会落进文件日志，必要时弹窗，便于排查。
        installGlobalExceptionHandler();

        // ── 启动参数校验 ───────────────────────────────────────────────────────
        //    在单实例锁取得之前完成：避免拼写错误的参数也会去占锁。
        //    命中未识别参数 / 漏值时打印帮助并以 64 退出；--help / -h 则打印帮助并以 0 退出。
        CliSetupCommand.validateArgsOrExit(args);

        boolean startupLaunch = AutoStartManager.isStartupLaunch(args);

        SingleInstanceManager singleInstanceManager;
        try {
            singleInstanceManager = SingleInstanceManager.acquire();
        } catch (Exception e) {
            log.error(logMessage("gui.launcher.log.single-instance.init-failed"), e);
            showSingleInstanceInitError(e);
            throw e;
        }

        if (singleInstanceManager == null) {
            // CLI 管理命令需要排他写入 setup_config.json，无法与运行中的实例共存：
            // 直接退出并提示用户先停止服务，而不是去激活另一个 GUI 窗口或静默退出。
            if (CliSetupCommand.containsCliCommand(args)) {
                log.info(logMessage("gui.launcher.log.single-instance.existing-detected", false));
                CliSetupCommand.abortBecauseAnotherInstanceRunning();
            }
            if (startupLaunch) {
                log.info(logMessage("gui.launcher.log.single-instance.startup-existing"));
                return;
            }
            boolean activated = SingleInstanceManager.signalExistingInstance();
            log.info(logMessage("gui.launcher.log.single-instance.existing-detected", activated));
            if (!activated && !GraphicsEnvironment.isHeadless()) {
                JOptionPane.showMessageDialog(null,
                        message("gui.launcher.dialog.already-running.message"),
                        message("gui.launcher.dialog.already-running.title"),
                        JOptionPane.INFORMATION_MESSAGE);
            }
            return;
        }
        registerSingleInstanceShutdown(singleInstanceManager);

        // ── 0c. CLI 管理命令（--setup / --change-password / --reset-password）─────
        //    必须在单实例锁取得之后执行：避免与正在运行的实例并发写 setup_config.json。
        //    命中后会 System.exit，永不返回；未命中时正常继续。
        CliSetupCommand.handleIfPresent(args);

        // ── 1. 判断是否需要 GUI ─────────────────────────────────────────────────
        boolean noGui = Arrays.asList(args).contains("--no-gui")
                || GraphicsEnvironment.isHeadless();
        System.setProperty(HEADLESS_PROPERTY, Boolean.toString(noGui));

        if (noGui) {
            // 无头模式下若未完成首次初始化，没有任何 UI 入口可用（setup.html 仅本地可访问）。
            // 直接打印 CLI 提示并退出，避免起一个无法被任何人配置的服务。
            CliSetupCommand.enforceSetupCompleteForHeadlessOrExit();
            log.info(logMessage("gui.launcher.log.headless"));
            try {
                PixivDownloadApplication.start(filterArgs(args));
            } catch (Throwable t) {
                logStartupFailure(t);
                throw t;
            }
            return;
        }

        // ── 2. 启动前读取配置（Spring 尚未就绪，直接读文件）────────────────────────
        int serverPort = DEFAULT_PORT;
        String rootFolder = DEFAULT_ROOT;
        Path configPath = RuntimeFiles.resolveConfigYamlPath();

        if (configPath.toFile().exists()) {
            try {
                ConfigFileEditor editor = new ConfigFileEditor(configPath);
                String portStr = editor.read("server.port");
                String rootStr = editor.read("download.root-folder");
                if (portStr != null && !portStr.isBlank()) {
                    serverPort = Integer.parseInt(portStr.trim());
                }
                if (rootStr != null && !rootStr.isBlank()) {
                    rootFolder = rootStr.trim();
                }
            } catch (Exception e) {
                log.warn(logMessage("gui.launcher.log.config.read-failed", e.getMessage()));
            }
        }

        RuntimeFiles.prepareRuntimeFiles(rootFolder);

        final int port = serverPort;
        final String root = rootFolder;
        String[] backendArgs = filterArgs(args);

        BackendLifecycleManager.configure(backendArgs, GuiLauncher::showBackendStartupFailure);

        // ── 3. 初始化 Swing + FlatLaf，展示主窗口 ────────────────────────────────
        SwingUtilities.invokeLater(() -> {
            try {
                FlatLafSetup.apply();
                MainFrame frame = new MainFrame(port, root, configPath);
                singleInstanceManager.setActivationHandler(() -> SwingUtilities.invokeLater(frame::showWindow));
                boolean trayInstalled = SystemTrayManager.install(frame, root);
                if (!startupLaunch || !trayInstalled) {
                    frame.showWindow();
                }
                maybeScheduleStartupBackfillFlow(frame, configPath, root);
            } catch (Throwable t) {
                // 没有这层兜底，异常只会打到不可见的 stderr，EDT 随即收摊、
                // JVM 静默退出，日志永远停在启动那两行。务必先落盘再退出。
                handleFatalGuiBootstrapFailure(t);
            }
        });
    }

    // ────────────────────────────────────────────────────────────────────────
    // 全局异常兜底（防止进程无痕消失）
    // ────────────────────────────────────────────────────────────────────────

    /**
     * 安装进程级未捕获异常处理器。EDT（{@code AWT-EventQueue-*}）与任何后台线程
     * 抛出的未捕获异常都会被写入文件日志；致命的引导期失败还会弹窗并以非零码退出，
     * 避免「进程出现几秒就消失、日志只有两行」这种无法排查的情况。
     */
    private static void installGlobalExceptionHandler() {
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                log.error(logMessage("gui.launcher.log.uncaught-exception",
                        thread.getName(), safeMessage(error)), error);
            } catch (Throwable loggingFailure) {
                System.err.println("[GuiLauncher] uncaught exception on "
                        + thread.getName() + ": " + error);
                error.printStackTrace();
            }
        });
    }

    /**
     * GUI 引导期（FlatLaf / 主窗口 / 托盘）致命失败时的统一处理：
     * 先保证异常落盘，再尽力弹窗告知用户，最后以非零码显式退出，
     * 使行为可预测、可诊断，而不是静默消失。
     */
    private static void handleFatalGuiBootstrapFailure(Throwable t) {
        logStartupFailure(t);
        try {
            GuiErrorDialog.show(
                    null,
                    message("gui.launcher.dialog.startup-error.title"),
                    message("gui.launcher.dialog.startup-error.with-log.message",
                            safeMessage(t), Path.of(LOG_LATEST).toAbsolutePath()));
        } catch (Throwable dialogFailure) {
            log.error(logMessage("gui.launcher.log.startup.failed.generic",
                    safeMessage(dialogFailure)), dialogFailure);
        }
        // System.exit（而非 halt）：让单实例 shutdown hook 释放锁，
        // 否则下次启动会误判为「已在运行」。
        System.exit(1);
    }

    // ────────────────────────────────────────────────────────────────────────
    // 日志目录准备（必须在 logback 初始化前执行）
    // ────────────────────────────────────────────────────────────────────────

    /**
     * 在 logback 读取 {@code logback.xml} 之前完成以下操作：
     * <ol>
     *   <li>创建 {@code log/} 目录</li>
     *   <li>删除上次遗留的 {@code latest.log}，使本次运行重新创建</li>
     *   <li>清理多余的历史时间戳文件，只保留最近 {@value #LOG_HISTORY_COUNT} - 1 份，
     *       为本次新文件留出位置</li>
     *   <li>将 {@code LOG_TIMESTAMP} 写入系统属性，供 {@code logback.xml} 中的
     *       {@code ${LOG_TIMESTAMP}} 占位符使用</li>
     * </ol>
     */
    private static void prepareLogging() {
        // 禁止 Spring Boot 接管日志系统，避免其重新初始化 logback 导致
        // append=false 的 HTML appender 截断文件、丢失 Spring Boot 启动前的日志。
        System.setProperty("org.springframework.boot.logging.LoggingSystem", "none");

        try {
            // 创建目录
            Files.createDirectories(Path.of(LOG_DIR));
            Files.createDirectories(Path.of(LOG_HTML_DIR));

            // 删除旧 latest 文件，使 logback 以 append=true 创建新文件（等效覆盖）
            Files.deleteIfExists(Path.of(LOG_LATEST));
            // HTML latest 使用 append=false，logback 会自行覆盖；
            // 此处同步删除以保证目录整洁（防止空文件遗留等边界情况）
            Files.deleteIfExists(Path.of(LOG_HTML_LATEST));

            // 清理超量的历史会话文件
            cleanOldSessionLogs(Path.of(LOG_DIR), ".log");
            cleanOldSessionLogs(Path.of(LOG_HTML_DIR), ".html");

            String timestamp = LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss"));
            System.setProperty("LOG_TIMESTAMP", timestamp);
        } catch (Exception e) {
            System.err.println(logMessage("gui.launcher.log.prepare-logging.failed", e.getMessage()));
        }
    }

    /**
     * 删除指定目录下最旧的时间戳会话日志，使现有文件数不超过
     * {@value #LOG_HISTORY_COUNT} - 1，从而在新会话文件创建后恰好保持 {@value #LOG_HISTORY_COUNT} 份。
     *
     * @param logDir    目标目录（{@code log/} 或 {@code log/html/}）
     * @param extension 文件扩展名（{@code ".log"} 或 {@code ".html"}）
     */
    private static void cleanOldSessionLogs(Path logDir, String extension) {
        try {
            List<Path> sessions = Files.list(logDir)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith(LOG_SESSION_PREFIX) && name.endsWith(extension);
                    })
                    .sorted(Comparator.naturalOrder()) // 文件名含时间戳，自然序即时间序
                    .collect(Collectors.toList());

            int toDelete = sessions.size() - (LOG_HISTORY_COUNT - 1);
            for (int i = 0; i < toDelete; i++) {
                Files.deleteIfExists(sessions.get(i));
            }
        } catch (Exception e) {
            System.err.println(logMessage("gui.launcher.log.cleanup-old-logs.failed",
                    logDir, extension, e.getMessage()));
        }
    }

    private static void maybeScheduleStartupBackfillFlow(MainFrame frame, Path configPath, String rootFolder) {
        ArtworksBackFill.Options options = buildStartupBackfillOptions(configPath, rootFolder);
        Path databasePath = Path.of(options.dbPath());

        Thread worker = new Thread(() -> {
            try {
                runStartupSchemaBackfill(frame, databasePath, options);
            } catch (Throwable fatal) {
                log.error(logMessage("gui.launcher.log.startup.schema-backfill-flow.unexpected"), fatal);
                BackendLifecycleManager.startAsync();
            }
        }, "startup-schema-backfill");
        worker.setDaemon(true);
        worker.start();
    }

    private static void runStartupSchemaBackfill(MainFrame frame, Path databasePath,
                                                 ArtworksBackFill.Options options) {
        if (!hasInspectableDatabase(databasePath)) {
            log.info(logMessage("gui.launcher.log.startup.schema-check.skipped", databasePath.toAbsolutePath()));
            BackendLifecycleManager.startAsync();
            return;
        }

        DatabaseSchemaInspector.SchemaComparison comparison;
        try {
            comparison = DatabaseSchemaInspector.compare(databasePath);
        } catch (Throwable error) {
            log.warn(logMessage("gui.launcher.log.startup.schema-check.compare-failed"), error);
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                    frame,
                    message("gui.launcher.dialog.schema-check-failed.message", safeMessage(error)),
                    message("gui.launcher.dialog.schema-check-failed.title"),
                    JOptionPane.WARNING_MESSAGE));
            BackendLifecycleManager.startAsync();
            return;
        }

        if (comparison.matches()) {
            log.info(logMessage("gui.launcher.log.startup.schema-check.passed", databasePath.toAbsolutePath()));
            BackendLifecycleManager.startAsync();
            return;
        }

        log.info(logMessage("gui.launcher.log.startup.schema-check.mismatch", comparison.summary(8)));
        if (!supportsStartupAutoBackfill(comparison)) {
            log.info(logMessage("gui.launcher.log.startup.schema-check.auto-backfill.unsupported", comparison.summary(8)));
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                    frame,
                    message("gui.launcher.dialog.schema-mismatch.no-auto-backfill.message", comparison.summary(6)),
                    message("gui.launcher.dialog.schema-mismatch.title"),
                    JOptionPane.INFORMATION_MESSAGE));
            BackendLifecycleManager.startAsync();
            return;
        }

        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                frame,
                message("gui.launcher.dialog.schema-mismatch.message", comparison.summary(6)),
                message("gui.launcher.dialog.schema-mismatch.title"),
                JOptionPane.INFORMATION_MESSAGE));

        int pendingCount;
        try {
            pendingCount = ArtworksBackFill.countCandidates(options);
            log.info(logMessage("gui.launcher.log.startup.backfill.pending", pendingCount));
        } catch (Throwable error) {
            log.warn(logMessage("gui.launcher.log.startup.backfill.check-failed"), error);
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                    frame,
                    message("gui.launcher.dialog.backfill-check-failed.message", safeMessage(error)),
                    message("gui.launcher.dialog.backfill-check-failed.title"),
                    JOptionPane.WARNING_MESSAGE));
            BackendLifecycleManager.startAsync();
            return;
        }

        if (pendingCount <= 0) {
            BackendLifecycleManager.startAsync();
            return;
        }

        final int confirmedPending = pendingCount;
        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                frame,
                message("gui.launcher.dialog.backfill-pending.message", confirmedPending),
                message("gui.launcher.dialog.auto-backfill.title"),
                JOptionPane.INFORMATION_MESSAGE));

        ToolHtmlLogSession logSession = null;
        ArtworksBackFill.Summary summary = null;
        Throwable failure = null;
        try {
            try {
                logSession = ToolHtmlLogSession.open("artworks-backfill", ArtworksBackFill.class);
                try {
                    logSession.openLatestInBrowser();
                } catch (Exception browserError) {
                    log.warn(logMessage("gui.launcher.log.startup.backfill.open-log-page-failed"), browserError);
                }
            } catch (Exception logError) {
                log.warn(logMessage("gui.launcher.log.startup.backfill.create-log-session-failed"), logError);
            }
            summary = ArtworksBackFill.run(options);
        } catch (Throwable error) {
            failure = error;
            log.error(logMessage("gui.launcher.log.startup.backfill.failed"), error);
        } finally {
            if (logSession != null) {
                try {
                    logSession.close();
                } catch (Exception ignored) {
                }
            }
        }

        ArtworksBackFill.Summary finalSummary = summary;
        Throwable finalFailure = failure;
        BackendLifecycleManager.startAsync(() -> showStartupBackfillResult(frame, finalSummary, finalFailure, confirmedPending));
    }

    private static boolean supportsStartupAutoBackfill(DatabaseSchemaInspector.SchemaComparison comparison) {
        return !comparison.details().isEmpty()
                && comparison.details().stream().allMatch(GuiLauncher::isSupportedStartupAutoBackfillDifference);
    }

    private static boolean isSupportedStartupAutoBackfillDifference(
            DatabaseSchemaInspector.SchemaDifference difference) {
        if (difference.hasColumn()) {
            return ArtworksBackFill.supportsDatabaseColumn(difference.tableName(), difference.columnName());
        }
        return difference.kind() == DatabaseSchemaInspector.SchemaDifferenceKind.MISSING_TABLE
                && ArtworksBackFill.supportsDatabaseTable(difference.tableName());
    }

    private static void showStartupBackfillResult(Component owner,
                                                  ArtworksBackFill.Summary summary,
                                                  Throwable failure,
                                                  int checkedCount) {
        if (failure != null) {
            GuiErrorDialog.show(
                    owner,
                    message("gui.launcher.dialog.auto-backfill.title"),
                    message("gui.launcher.dialog.auto-backfill.failed.message", safeMessage(failure)));
            return;
        }

        if (summary == null) {
            return;
        }

        String resultText = summary.rateLimited()
                ? message("gui.launcher.dialog.auto-backfill.rate-limited")
                : message("gui.launcher.dialog.auto-backfill.completed");
        JOptionPane.showMessageDialog(
                owner,
                message("gui.launcher.dialog.auto-backfill.summary.message",
                        resultText, checkedCount, summary.processed(), summary.totalCandidates()),
                message("gui.launcher.dialog.auto-backfill.title"),
                summary.rateLimited() ? JOptionPane.WARNING_MESSAGE : JOptionPane.INFORMATION_MESSAGE
        );
    }

    private static ArtworksBackFill.Options buildStartupBackfillOptions(Path configPath, String rootFolder) {
        ArtworksBackFill.Options defaults = ArtworksBackFill.Options.defaults();
        boolean proxyEnabled = defaults.useProxy();
        String proxyHost = defaults.proxyHost();
        int proxyPort = defaults.proxyPort();

        if (Files.isRegularFile(configPath)) {
            try {
                ConfigFileEditor editor = new ConfigFileEditor(configPath);
                proxyEnabled = Boolean.parseBoolean(defaultIfBlank(editor.read("proxy.enabled"), String.valueOf(defaults.useProxy())));
                proxyHost = defaultIfBlank(editor.read("proxy.host"), defaults.proxyHost());
                proxyPort = Integer.parseInt(defaultIfBlank(editor.read("proxy.port"), String.valueOf(defaults.proxyPort())));
            } catch (Exception e) {
                log.warn(logMessage("gui.launcher.log.startup.backfill.proxy-defaults.failed", e.getMessage()));
            }
        }//"

        return new ArtworksBackFill.Options(
                RuntimeFiles.resolveDatabasePath(rootFolder).toString(),
                proxyHost,
                proxyPort,
                proxyEnabled,
                defaults.delayMs(),
                defaults.limit(),
                false
        );
    }

    private static boolean hasInspectableDatabase(Path databasePath) {
        try {
            return Files.isRegularFile(databasePath) && Files.size(databasePath) > 0;
        } catch (Exception e) {
            log.warn(logMessage("gui.launcher.log.database.inspect-failed",
                    databasePath.toAbsolutePath(), e.getMessage()));
            return false;
        }
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static void showBackendStartupFailure(Throwable error) {
        String diag = diagnoseStartupError(error);
        String userMessage = diag != null ? diag : message("gui.launcher.dialog.startup-error.message", safeMessage(error));
        logStartupFailure(error);
        SwingUtilities.invokeLater(() -> GuiErrorDialog.show(
                null,
                message("gui.launcher.dialog.startup-error.title"),
                message("gui.launcher.dialog.startup-error.with-log.message",
                        userMessage, Path.of(LOG_LATEST).toAbsolutePath())));
    }

    private static void logStartupFailure(Throwable t) {
        String diag = diagnoseStartupError(t);
        if (diag != null) {
            log.error(logMessage("gui.launcher.log.startup.failed.diagnostic", diag), t);
        } else {
            log.error(logMessage("gui.launcher.log.startup.failed.generic", safeMessage(t)), t);
        }
    }

    /**
     * 沿异常链识别常见启动失败原因，返回面向用户的提示文本。
     * 未识别的异常返回 null，由调用方按通用错误处理。
     */
    private static String diagnoseStartupError(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            String name = cause.getClass().getName();
            String msg = cause.getMessage() == null ? "" : cause.getMessage();
            String low = msg.toLowerCase();

            if (cause instanceof BindException
                    || name.endsWith("PortInUseException")
                    || low.contains("address already in use")
                    || low.contains("failed to bind")) {
                String port = extractPort(msg);
                String portHint = port == null ? message("gui.launcher.diagnostic.port.placeholder") : port;
                if (port == null) {
                    return message("gui.launcher.diagnostic.port-in-use.without-port", portHint, msg);
                }
                return message("gui.launcher.diagnostic.port-in-use.with-port", port, portHint, msg);
            }

            if ((cause instanceof NoSuchFileException
                    || cause instanceof FileNotFoundException
                    || low.contains("nosuchfileexception"))
                    && hasAny(low, "ssl", "cert", "key", "pem", "jks", "store", "p12", "pkcs12")) {
                return message("gui.launcher.diagnostic.ssl-file-not-found", msg);
            }

            if (name.endsWith("UnrecoverableKeyException")
                    || low.contains("keystore password was incorrect")
                    || low.contains("password was incorrect")
                    || low.contains("wrong password")
                    || low.contains("given final block not properly padded")) {
                return message("gui.launcher.diagnostic.ssl-password-invalid", msg);
            }

            if (name.endsWith("CertificateException")
                    || name.endsWith("SSLException")
                    || low.contains("derinputstream")
                    || low.contains("could not load store")
                    || low.contains("unable to read")
                    || (low.contains("keystore") && low.contains("not"))
                    || low.contains("invalid keystore format")) {
                return message("gui.launcher.diagnostic.ssl-invalid-format", msg);
            }

            if (name.contains("yaml")
                    || name.contains("Yaml")
                    || msg.contains("YAML")
                    || msg.contains("ScannerException")
                    || msg.contains("ParserException")
                    || msg.contains("mapping values")) {
                return message("gui.launcher.diagnostic.config-yaml-invalid", msg);
            }

            if (cause instanceof AccessDeniedException
                    || low.contains("permission denied")
                    || msg.contains("拒绝访问")) {
                return message("gui.launcher.diagnostic.permission-denied", msg);
            }
        }
        return null;
    }

    /**
     * 从 "Port 6999 was already in use" / ":6999" 等消息中提取端口号。
     */
    private static String extractPort(String msg) {
        if (msg == null) {
            return null;
        }
        Matcher portMatcher = Pattern.compile("(?i)port[^0-9]{0,10}(\\d{2,5})").matcher(msg);
        if (portMatcher.find()) {
            return portMatcher.group(1);
        }
        Matcher colonMatcher = Pattern.compile(":(\\d{2,5})\\b").matcher(msg);
        if (colonMatcher.find()) {
            return colonMatcher.group(1);
        }
        return null;
    }

    private static boolean hasAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String safeMessage(Throwable t) {
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    private static void showSingleInstanceInitError(Exception e) {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        GuiErrorDialog.show(null,
                message("gui.launcher.dialog.startup-error.title"),
                message("gui.launcher.dialog.single-instance-init-failed.message",
                        safeMessage(e), Path.of(LOG_LATEST).toAbsolutePath()));
    }

    private static void registerSingleInstanceShutdown(SingleInstanceManager singleInstanceManager) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                singleInstanceManager.close();
            } catch (Exception e) {
                if (log != null) {
                    log.debug(logMessage("gui.launcher.log.single-instance.close-failed", e.getMessage()));
                }
            }
        }, "single-instance-shutdown"));
    }

    private static String message(String code, Object... args) {
        return GuiMessages.get(code, args);
    }

    private static String logMessage(String code, Object... args) {
        return MessageBundles.get(code, args);
    }

    /**
     * 从参数列表中过滤掉 GUI 专用参数，避免传入 Spring。
     */
    private static String[] filterArgs(String[] args) {
        return Arrays.stream(args)
                .filter(arg -> !arg.equals("--no-gui"))
                .filter(arg -> !AutoStartManager.isStartupArg(arg))
                .toArray(String[]::new);
    }
}

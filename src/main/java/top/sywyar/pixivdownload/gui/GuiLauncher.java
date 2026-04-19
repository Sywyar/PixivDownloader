package top.sywyar.pixivdownload.gui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.sywyar.pixivdownload.PixivDownloadApplication;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.gui.config.ConfigFileEditor;
import top.sywyar.pixivdownload.gui.theme.FlatLafSetup;

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
    /** 保留最近的会话日志数量（不含 latest.log / latest.html） */
    private static final int LOG_HISTORY_COUNT = 5;
    private static final int DEFAULT_PORT = 6999;
    private static final String DEFAULT_ROOT = RuntimeFiles.DEFAULT_DOWNLOAD_ROOT;

    public static void main(String[] args) throws Exception {
        // ── 0. 在 logback 初始化前完成日志目录/属性准备 ──────────────────────────
        //    顺序不可颠倒：必须先于任何 getLogger() / log.xxx() 调用
        prepareLogging();

        // ── 触发 logback 初始化（此时 LOG_TIMESTAMP 已就绪）─────────────────────
        log = LoggerFactory.getLogger(GuiLauncher.class);
        log.info("PixivDownload 启动中，args={}", Arrays.toString(args));

        SingleInstanceManager singleInstanceManager;
        try {
            singleInstanceManager = SingleInstanceManager.acquire();
        } catch (Exception e) {
            log.error("无法初始化单实例保护", e);
            showSingleInstanceInitError(e);
            throw e;
        }
        if (singleInstanceManager == null) {
            boolean activated = SingleInstanceManager.signalExistingInstance();
            log.info("检测到已有运行实例，是否已发出激活请求: {}", activated);
            if (!activated && !GraphicsEnvironment.isHeadless()) {
                JOptionPane.showMessageDialog(null,
                        "PixivDownload 已在运行，请先关闭现有窗口后再重新启动。",
                        "程序已运行",
                        JOptionPane.INFORMATION_MESSAGE);
            }
            return;
        }
        registerSingleInstanceShutdown(singleInstanceManager);

        // ── 1. 判断是否需要 GUI ─────────────────────────────────────────────────
        boolean noGui = Arrays.asList(args).contains("--no-gui")
                || GraphicsEnvironment.isHeadless();

        if (noGui) {
            log.info("无头/命令行模式启动（GUI 已禁用）");
            try {
                PixivDownloadApplication.main(args);
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
                log.warn("读取配置文件失败，使用默认值: {}", e.getMessage());
            }
        }

        RuntimeFiles.prepareRuntimeFiles(rootFolder);

        final int port = serverPort;
        final String root = rootFolder;

        // ── 3. 初始化 Swing + FlatLaf，展示主窗口 ────────────────────────────────
        SwingUtilities.invokeLater(() -> {
            FlatLafSetup.apply();
            MainFrame frame = new MainFrame(port, root, configPath);
            singleInstanceManager.setActivationHandler(() -> SwingUtilities.invokeLater(frame::showWindow));
            SystemTrayManager.install(frame, root);
            frame.showWindow();
        });

        // ── 4. 在后台线程启动 Spring Boot ─────────────────────────────────────────
        Thread springThread = new Thread(() -> {
            try {
                PixivDownloadApplication.main(filterArgs(args));
            } catch (Throwable t) {
                String diag = diagnoseStartupError(t);
                String userMsg = (diag != null) ? diag : ("后端服务启动失败：" + safeMessage(t));
                logStartupFailure(t);
                SwingUtilities.invokeLater(() ->
                        JOptionPane.showMessageDialog(null,
                                userMsg + "\n\n详细日志见：" + Path.of(LOG_LATEST).toAbsolutePath(),
                                "启动错误", JOptionPane.ERROR_MESSAGE));
            }
        }, "spring-main");
        springThread.setDaemon(false);
        springThread.start();
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
            System.err.println("日志目录准备失败: " + e.getMessage());
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
            System.err.println("清理旧日志失败（" + logDir + extension + "）: " + e.getMessage());
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // 启动错误诊断
    // ────────────────────────────────────────────────────────────────────────

    private static void logStartupFailure(Throwable t) {
        String diag = diagnoseStartupError(t);
        if (diag != null) {
            log.error("启动失败：\n{}", diag, t);
        } else {
            log.error("启动失败：{}", safeMessage(t), t);
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

            // 端口被占用
            if (cause instanceof BindException
                    || name.endsWith("PortInUseException")
                    || low.contains("address already in use")
                    || low.contains("failed to bind")) {
                String port = extractPort(msg);
                String portHint = (port == null) ? "<端口>" : port;
                return "[端口被占用] " + (port == null ? "" : "端口 " + port + " ")
                        + "已被其他程序占用。\n"
                        + "请检查 config.yaml 中的 server.port 或 ssl.http-redirect-port，"
                        + "或释放该端口后重启。\n"
                        + "Windows 排查命令：netstat -ano | findstr :" + portHint + "\n"
                        + "原始信息：" + msg;
            }

            // SSL 证书 / 密钥库文件不存在
            if ((cause instanceof NoSuchFileException
                    || cause instanceof FileNotFoundException
                    || low.contains("nosuchfileexception"))
                    && hasAny(low, "ssl", "cert", "key", "pem", "jks", "store", "p12", "pkcs12")) {
                return "[SSL 证书文件未找到] " + msg + "\n"
                        + "请检查 config.yaml 中以下路径是否存在且可读：\n"
                        + "  - server.ssl.certificate / server.ssl.certificate-private-key（PEM）\n"
                        + "  - server.ssl.key-store（JKS / PKCS12）\n"
                        + "建议使用绝对路径，避免相对路径在不同工作目录下解析出错。";
            }

            // 密钥库 / 私钥密码错误
            if (name.endsWith("UnrecoverableKeyException")
                    || low.contains("keystore password was incorrect")
                    || low.contains("password was incorrect")
                    || low.contains("wrong password")
                    || low.contains("given final block not properly padded")) {
                return "[SSL 密码错误] " + msg + "\n"
                        + "请检查 config.yaml 中 server.ssl.key-store-password / "
                        + "server.ssl.key-password 是否正确。";
            }

            // SSL 证书 / 私钥格式无效
            if (name.endsWith("CertificateException")
                    || name.endsWith("SSLException")
                    || low.contains("derinputstream")
                    || low.contains("could not load store")
                    || low.contains("unable to read")
                    || (low.contains("keystore") && low.contains("not"))
                    || low.contains("invalid keystore format")) {
                return "[SSL 证书格式无效或无法解析] " + msg + "\n"
                        + "请确认：\n"
                        + "  - PEM 与 JKS 配置不要混用（同时配置时 PEM 优先）；\n"
                        + "  - 证书文件与私钥文件成对、未损坏；\n"
                        + "  - JKS 类型与 server.ssl.key-store-type 匹配（默认 JKS，PKCS12 需显式指定）。";
            }

            // YAML 配置解析错误
            if (name.contains("yaml") || name.contains("Yaml")
                    || msg.contains("YAML") || msg.contains("ScannerException")
                    || msg.contains("ParserException") || msg.contains("mapping values")) {
                return "[config.yaml 格式错误] " + msg + "\n"
                        + "请检查缩进（仅空格、不要 Tab）、冒号后空格、引号是否成对。";
            }

            // 权限不足（低端口需管理员、目录不可写等）
            if (cause instanceof AccessDeniedException
                    || low.contains("permission denied")
                    || msg.contains("拒绝访问")) {
                return "[权限不足] " + msg + "\n"
                        + "可能原因：下载目录无写权限，或监听端口 < 1024 需要管理员权限。";
            }
        }
        return null;
    }

    /** 从 "Port 6999 was already in use" / ":6999" 等消息中提取端口号。 */
    private static String extractPort(String msg) {
        if (msg == null) return null;
        Matcher m = Pattern.compile("(?i)port[^0-9]{0,10}(\\d{2,5})").matcher(msg);
        if (m.find()) return m.group(1);
        m = Pattern.compile(":(\\d{2,5})\\b").matcher(msg);
        if (m.find()) return m.group(1);
        return null;
    }

    private static boolean hasAny(String s, String... needles) {
        for (String n : needles) {
            if (s.contains(n)) return true;
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
        JOptionPane.showMessageDialog(null,
                "初始化单实例保护失败：" + safeMessage(e) + "\n\n详细日志见：" + Path.of(LOG_LATEST).toAbsolutePath(),
                "启动错误",
                JOptionPane.ERROR_MESSAGE);
    }

    private static void registerSingleInstanceShutdown(SingleInstanceManager singleInstanceManager) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                singleInstanceManager.close();
            } catch (Exception e) {
                if (log != null) {
                    log.debug("关闭单实例保护时出现异常: {}", e.getMessage());
                }
            }
        }, "single-instance-shutdown"));
    }

    /** 从参数列表中过滤掉 GUI 专用参数，避免传入 Spring。 */
    private static String[] filterArgs(String[] args) {
        return Arrays.stream(args)
                .filter(a -> !a.equals("--no-gui"))
                .toArray(String[]::new);
    }
}

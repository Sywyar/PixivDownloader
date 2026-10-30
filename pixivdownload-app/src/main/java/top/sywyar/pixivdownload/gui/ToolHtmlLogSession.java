package top.sywyar.pixivdownload.gui;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.FileAppender;
import ch.qos.logback.core.encoder.LayoutWrappingEncoder;
import org.slf4j.LoggerFactory;
import top.sywyar.pixivdownload.logback.HtmlLogLayout;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiToolHost.ToolLogSession;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** 为一次工具执行持有 HTML 日志，关闭时释放本会话的 appender。 */
public final class ToolHtmlLogSession implements ToolLogSession {

    private static final int HISTORY_COUNT = 5;
    private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss_SSS");

    private final Logger logger;
    private final FileAppender<ILoggingEvent> latestAppender;
    private final FileAppender<ILoggingEvent> sessionAppender;
    private final Path latestPath;
    private final Path sessionPath;

    private ToolHtmlLogSession(Logger logger,
                               FileAppender<ILoggingEvent> latestAppender,
                               FileAppender<ILoggingEvent> sessionAppender,
                               Path latestPath,
                               Path sessionPath) {
        this.logger = logger;
        this.latestAppender = latestAppender;
        this.sessionAppender = sessionAppender;
        this.latestPath = latestPath;
        this.sessionPath = sessionPath;
    }

    public static ToolHtmlLogSession open(String stem, Class<?> loggerType) throws Exception {
        return open(top.sywyar.pixivdownload.config.RuntimeFiles.logDirectory().resolve("html"), stem, loggerType);
    }

    public static ToolHtmlLogSession openStatic(String stem, Class<?> loggerType) throws Exception {
        return open(top.sywyar.pixivdownload.config.RuntimeFiles.logDirectory().resolve("html"), stem, loggerType, false);
    }

    static ToolHtmlLogSession open(Path directory, String stem, Class<?> loggerType) throws Exception {
        return open(directory, stem, loggerType, true);
    }

    static synchronized ToolHtmlLogSession open(Path directory, String stem, Class<?> loggerType, boolean autoRefresh) throws Exception {
        String fileStem = normalizeStem(stem);
        Files.createDirectories(directory);

        Path latestPath = directory.resolve(fileStem + "-latest.html");
        Path sessionPath = directory.resolve(fileStem + "_" + LocalDateTime.now().format(TS_FORMAT)
                + "_" + UUID.randomUUID() + ".html");

        Logger logger = (Logger) LoggerFactory.getLogger(loggerType);
        LoggerContext context = logger.getLoggerContext();

        String latestAppenderName = "tool-html-latest-" + fileStem;
        String sessionAppenderName = "tool-html-session-" + fileStem;
        if (logger.getAppender(latestAppenderName) != null) {
            throw new IllegalStateException("Tool log session is already active: " + fileStem);
        }
        Files.deleteIfExists(latestPath);
        cleanOldSessions(directory, fileStem);

        FileAppender<ILoggingEvent> latestAppender = newAppender(context, latestAppenderName, latestPath, autoRefresh);
        FileAppender<ILoggingEvent> sessionAppender = newAppender(context, sessionAppenderName, sessionPath, autoRefresh);
        if (!latestAppender.isStarted() || !sessionAppender.isStarted()) {
            latestAppender.stop();
            sessionAppender.stop();
            throw new java.io.IOException("Unable to open tool log: " + sessionPath);
        }
        logger.addAppender(latestAppender);
        logger.addAppender(sessionAppender);

        return new ToolHtmlLogSession(logger, latestAppender, sessionAppender, latestPath, sessionPath);
    }

    @Override
    public Path latestPath() {
        return latestPath;
    }

    @Override
    public Path sessionPath() {
        return sessionPath;
    }

    @Override
    public void openLatestInBrowser() throws Exception {
        DesktopShellOpen.openExternalUri(latestPath.toAbsolutePath().toUri());
    }

    @Override
    public void close() {
        synchronized (ToolHtmlLogSession.class) {
            detachAndStop(latestAppender);
            detachAndStop(sessionAppender);
        }
    }

    private void detachAndStop(FileAppender<ILoggingEvent> appender) {
        if (appender == null) {
            return;
        }
        logger.detachAppender(appender);
        appender.stop();
    }

    private static FileAppender<ILoggingEvent> newAppender(LoggerContext context, String name, Path file, boolean autoRefresh) {
        HtmlLogLayout layout = autoRefresh ? new AutoRefreshHtmlLogLayout() : new HtmlLogLayout();
        layout.setContext(context);
        layout.start();

        LayoutWrappingEncoder<ILoggingEvent> encoder = new LayoutWrappingEncoder<>();
        encoder.setContext(context);
        encoder.setLayout(layout);
        encoder.setCharset(StandardCharsets.UTF_8);
        encoder.start();

        FileAppender<ILoggingEvent> appender = new FileAppender<>();
        appender.setContext(context);
        appender.setName(name);
        appender.setFile(file.toString());
        appender.setAppend(false);
        appender.setEncoder(encoder);
        appender.start();
        return appender;
    }

    private static void cleanOldSessions(Path directory, String stem) throws Exception {
        try (var stream = Files.list(directory)) {
            List<Path> sessions = stream
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.startsWith(stem + "_") && name.endsWith(".html");
                    })
                    .sorted(Comparator.naturalOrder())
                    .collect(Collectors.toList());

            int toDelete = sessions.size() - (HISTORY_COUNT - 1);
            for (int i = 0; i < toDelete; i++) {
                Files.deleteIfExists(sessions.get(i));
            }
        }
    }

    private static String normalizeStem(String stem) {
        if (stem == null || stem.isBlank()) {
            return "tool";
        }
        return stem.replaceAll("[^a-zA-Z0-9._-]+", "-");
    }

    private static final class AutoRefreshHtmlLogLayout extends HtmlLogLayout {
        @Override
        public String getPresentationHeader() {
            String refresh = """
                    <meta http-equiv="refresh" content="2">
                    <script>
                      window.addEventListener('load', () => {
                        window.scrollTo(0, document.body.scrollHeight);
                      });
                    </script>
                    """;
            return super.getPresentationHeader().replace("</head>", refresh + "\n</head>");
        }
    }
}

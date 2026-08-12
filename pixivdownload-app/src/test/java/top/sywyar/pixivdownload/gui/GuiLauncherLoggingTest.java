package top.sywyar.pixivdownload.gui;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;
import top.sywyar.pixivdownload.common.Utf8ConsoleStreams;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class GuiLauncherLoggingTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("日志清理失败前仍会分配独立会话时间戳")
    void assignsSessionTimestampBeforeLatestCleanup() throws Exception {
        Path logDir = tempDir.resolve("log");
        Path htmlLogDir = logDir.resolve("html");
        Files.createDirectories(logDir.resolve("latest.log"));
        Files.writeString(logDir.resolve("latest.log/occupied"), "probe");
        String previousTimestamp = System.getProperty("LOG_TIMESTAMP");
        String previousLoggingSystem = System.getProperty("org.springframework.boot.logging.LoggingSystem");
        System.setProperty("LOG_TIMESTAMP", "stale");

        try {
            assertThat(GuiLauncher.prepareLogging(logDir, htmlLogDir)).isNotNull();
            assertThat(System.getProperty("LOG_TIMESTAMP"))
                    .matches("\\d{4}-\\d{2}-\\d{2}_\\d{6}")
                    .isNotEqualTo("stale");
        } finally {
            restoreProperty("LOG_TIMESTAMP", previousTimestamp);
            restoreProperty("org.springframework.boot.logging.LoggingSystem", previousLoggingSystem);
        }
    }

    @Test
    @DisplayName("JUL 日志会桥接到 Logback")
    void routesJulRecordsThroughLogback() {
        java.util.logging.Logger julRoot = java.util.logging.Logger.getLogger("");
        Handler[] originalHandlers = julRoot.getHandlers();
        Logger logbackRoot = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> capture = new ListAppender<>();
        capture.start();
        logbackRoot.addAppender(capture);

        try {
            GuiLauncher.installJulBridge();
            java.util.logging.Logger.getLogger("tomcat-probe").info("jul-bridge-probe");

            assertThat(capture.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .contains("jul-bridge-probe");
        } finally {
            SLF4JBridgeHandler.removeHandlersForRootLogger();
            for (Handler handler : originalHandlers) {
                julRoot.addHandler(handler);
            }
            logbackRoot.detachAppender(capture);
            capture.stop();
        }
    }

    @Test
    @DisplayName("生产配置保持控制台文本和 HTML 输出一致")
    void productionConfigurationKeepsConsoleTextAndHtmlOutputsInSync() throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java");
        Path logback = Path.of(GuiLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .resolve("logback.xml");
        String classPath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Process process = new ProcessBuilder(java.toString(),
                "-Dlogback.configurationFile=" + logback,
                "-DLOG_TIMESTAMP=parity-probe",
                "-cp", classPath,
                LoggingProbe.class.getName())
                .directory(tempDir.toFile())
                .redirectErrorStream(true)
                .start();

        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("logging parity probe did not exit");
        }
        String console = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.exitValue()).as(console).isZero();

        String textLatest = Files.readString(tempDir.resolve("log/latest.log"));
        String textSession = Files.readString(tempDir.resolve("log/pixiv-download_parity-probe.log"));
        String htmlLatest = Files.readString(tempDir.resolve("log/html/latest.html"));
        String htmlSession = Files.readString(tempDir.resolve("log/html/pixiv-download_parity-probe.html"));

        assertThat(textLatest).isEqualTo(textSession);
        assertThat(htmlLatest).isEqualTo(htmlSession);
        assertThat(eventCount(console, "^\\d{2}:\\d{2}:\\d{2}\\.\\d{3} ")).isEqualTo(3);
        assertThat(eventCount(textLatest, "^\\d{4}-\\d{2}-\\d{2} ")).isEqualTo(3);
        assertThat(eventCount(htmlLatest, "<div class=\"entry ")).isEqualTo(3);

        for (String output : List.of(console, textLatest, htmlLatest)) {
            assertThat(output)
                    .containsOnlyOnce("PARITY_JUL")
                    .containsOnlyOnce("PARITY_ERROR")
                    .contains("parity outer", "parity cause", "parity suppressed",
                            "Caused by:", "Suppressed:", "1 common frames omitted");
        }
        assertThat(console).containsOnlyOnce("PARITY_INFO <probe>&\" 中文");
        assertThat(textLatest).containsOnlyOnce("PARITY_INFO <probe>&\" 中文");
        assertThat(htmlLatest).containsOnlyOnce("PARITY_INFO &lt;probe&gt;&amp;&quot; 中文");
    }

    private static long eventCount(String output, String regex) {
        return Pattern.compile(regex, Pattern.MULTILINE).matcher(output).results().count();
    }

    public static final class LoggingProbe {

        private LoggingProbe() {
        }

        public static void main(String[] args) {
            Utf8ConsoleStreams.install();
            org.slf4j.Logger logger = LoggerFactory.getLogger(
                    "top.sywyar.pixivdownload.logging.ProductionParityProbe");
            GuiLauncher.installJulBridge();
            logger.info("PARITY_INFO <probe>&\" 中文");
            java.util.logging.Logger.getLogger("parity-jul").info("PARITY_JUL");

            IllegalArgumentException cause = new IllegalArgumentException("parity cause");
            cause.setStackTrace(new StackTraceElement[]{
                    new StackTraceElement("example.Root", "fail", "Root.java", 12),
                    new StackTraceElement("example.Shared", "run", "Shared.java", 34)
            });
            IllegalStateException failure = new IllegalStateException("parity outer", cause);
            failure.setStackTrace(new StackTraceElement[]{
                    new StackTraceElement("example.Outer", "call", "Outer.java", 56),
                    new StackTraceElement("example.Shared", "run", "Shared.java", 34)
            });
            IllegalStateException suppressed = new IllegalStateException("parity suppressed");
            suppressed.setStackTrace(new StackTraceElement[]{
                    new StackTraceElement("example.Suppressed", "close", "Suppressed.java", 78),
                    new StackTraceElement("example.Shared", "run", "Shared.java", 34)
            });
            failure.addSuppressed(suppressed);

            try {
                throw failure;
            } catch (IllegalStateException thrown) {
                logger.error("PARITY_ERROR", thrown);
            }
            ((LoggerContext) LoggerFactory.getILoggerFactory()).stop();
        }
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}

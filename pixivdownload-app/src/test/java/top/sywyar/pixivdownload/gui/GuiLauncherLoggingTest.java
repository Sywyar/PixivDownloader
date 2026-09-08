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
    @DisplayName("真实入口冷启动覆盖 latest 并保留前五次日志")
    void coldStartsRotatePreviousFiveRuns() throws Exception {
        for (int run = 0; run < 7; run++) {
            String console = launchHelp("run-" + run);
            String latest = Files.readString(tempDir.resolve("log/latest.log"));
            String html = Files.readString(tempDir.resolve("log/html/latest.html"));
            List<Path> textSessions = sessionFiles(tempDir.resolve("log"));
            List<Path> htmlSessions = sessionFiles(tempDir.resolve("log/html"));
            assertThat(textSessions).hasSize(Math.min(run + 1, 6));
            assertThat(htmlSessions).hasSize(textSessions.size());
            assertThat(latest).containsOnlyOnce("run-" + run);
            assertThat(html).containsOnlyOnce("<!DOCTYPE html>");
            assertThat(console).contains("System locale resolved:", "--help");
            assertThat(latest).contains("System locale resolved:", "--help");
            assertThat(html).contains("System locale resolved:", "--help");
            assertThat(latest).isEqualTo(Files.readString(textSessions.get(textSessions.size() - 1)));
            assertThat(html).isEqualTo(Files.readString(htmlSessions.get(htmlSessions.size() - 1)));
            if (run > 0) assertThat(latest).doesNotContain("run-" + (run - 1));
        }
        for (Path session : sessionFiles(tempDir.resolve("log"))) {
            assertThat(Files.readString(session)).doesNotContain("run-0");
        }
    }

    @Test
    @DisplayName("另一进程持有日志目录时帮助命令不改写当前日志")
    void concurrentStartPreservesActiveLogFiles() throws Exception {
        Files.createDirectories(tempDir.resolve("log/html"));
        Path latest = tempDir.resolve("log/latest.log");
        Path html = tempDir.resolve("log/html/latest.html");
        Files.writeString(latest, "active-text", StandardCharsets.UTF_8);
        Files.writeString(html, "active-html", StandardCharsets.UTF_8);
        try (var channel = java.nio.channels.FileChannel.open(tempDir.resolve("log/.session.lock"),
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            assertThat(launchHelp("secondary")).contains("--help");
            assertThat(Files.readString(latest)).isEqualTo("active-text");
            assertThat(Files.readString(html)).isEqualTo("active-html");
            assertThat(sessionFiles(tempDir.resolve("log"))).isEmpty();
        }
    }

    private String launchHelp(String marker) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java");
        Path logback = Path.of(GuiLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .resolve("logback.xml");
        Path output = tempDir.resolve("console-" + marker + ".txt");
        String classPath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Process process = new ProcessBuilder(java.toString(), "-Duser.language=en", "-Duser.country=US",
                "-Dlogback.configurationFile=" + logback, "-cp", classPath,
                GuiLauncher.class.getName(), "--help", "--probe=" + marker)
                .directory(tempDir.toFile()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
            String console = Files.readString(output);
            assertThat(process.exitValue()).as(console).isZero();
            return console;
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor();
        }
    }

    private static List<Path> sessionFiles(Path directory) throws Exception {
        try (var files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString().startsWith("pixiv-download_"))
                    .sorted().toList();
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
        String textSession = Files.readString(sessionFiles(tempDir.resolve("log")).get(0));
        String htmlLatest = Files.readString(tempDir.resolve("log/html/latest.html"));
        String htmlSession = Files.readString(sessionFiles(tempDir.resolve("log/html")).get(0));

        assertThat(textLatest).isEqualTo(textSession);
        assertThat(htmlLatest).isEqualTo(htmlSession);
        assertThat(eventCount(console, "^\\d{2}:\\d{2}:\\d{2}\\.\\d{3} ")).isEqualTo(6);
        assertThat(eventCount(textLatest, "^\\d{4}-\\d{2}-\\d{2} ")).isEqualTo(6);
        assertThat(eventCount(htmlLatest, "<div class=\"entry ")).isEqualTo(6);

        for (String output : List.of(console, textLatest, htmlLatest)) {
            assertThat(output)
                    .containsOnlyOnce("PARITY_JUL")
                    .containsOnlyOnce("PARITY_ERROR")
                    .containsOnlyOnce("PARITY_STDOUT 中文")
                    .containsOnlyOnce("PARITY_STDERR")
                    .containsOnlyOnce("PARITY_PROMPT")
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
            top.sywyar.pixivdownload.logback.ConsoleLogStreams.install();
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
            System.out.println("PARITY_STDOUT 中文");
            System.err.println("PARITY_STDERR");
            System.out.print("PARITY_PROMPT");
            System.out.flush();
            ((LoggerContext) LoggerFactory.getILoggerFactory()).stop();
        }
    }

}

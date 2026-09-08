package top.sywyar.pixivdownload.logback;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.ConsoleAppender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/** 把标准流输出交给同一组日志 appender；必须在控制台 appender 捕获原始流之后安装。 */
public final class ConsoleLogStreams {
    private static boolean installed;

    private ConsoleLogStreams() {
    }

    public static synchronized void install() {
        if (installed) return;
        // ConsoleAppender 默认会动态读取 System.out；固定原始流，避免日志再次进入转接流。
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        for (var logger : context.getLoggerList()) {
            logger.iteratorForAppenders().forEachRemaining(appender -> {
                if (appender instanceof ConsoleAppender<?> console) {
                    console.setOutputStream("System.err".equals(console.getTarget()) ? System.err : System.out);
                }
            });
        }
        System.setOut(stream(LoggerFactory.getLogger("stdout"), false));
        System.setErr(stream(LoggerFactory.getLogger("stderr"), true));
        installed = true;
    }

    private static PrintStream stream(Logger logger, boolean error) {
        return new PrintStream(new OutputStream() {
            private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

            @Override
            public void write(int value) {
                if (value == '\n') {
                    emit();
                } else {
                    buffer.write(value);
                }
            }

            @Override
            public void flush() {
                if (buffer.size() > 0) emit();
            }

            private void emit() {
                String message = buffer.toString(StandardCharsets.UTF_8);
                buffer.reset();
                if (message.endsWith("\r")) message = message.substring(0, message.length() - 1);
                if (error) logger.warn(message);
                else logger.info(message);
            }
        }, true, StandardCharsets.UTF_8);
    }
}

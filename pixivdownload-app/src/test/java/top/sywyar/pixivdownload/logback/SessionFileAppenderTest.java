package top.sywyar.pixivdownload.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionFileAppenderTest {
    @TempDir
    Path directory;

    @Test
    @DisplayName("被过滤的日志保留旧文件，首条接受事件才打开会话且停止后不再写入")
    void opensOnlyAfterAnAcceptedEvent() throws Exception {
        Path file = directory.resolve("latest.log");
        Files.writeString(file, "previous", StandardCharsets.UTF_8);
        LoggerContext context = new LoggerContext();
        context.setMDCAdapter(new LogbackMDCAdapter());
        LogSession session = mock(LogSession.class);
        when(session.openFiles()).thenReturn(true);
        context.putObject(LogSession.class.getName(), session);
        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern("%msg%n");
        encoder.setCharset(StandardCharsets.UTF_8);
        encoder.start();
        SessionFileAppender appender = new SessionFileAppender();
        appender.setContext(context);
        appender.setName("fixture");
        appender.setFile(file.toString());
        appender.setAppend(false);
        appender.setEncoder(encoder);
        AtomicInteger filters = new AtomicInteger();
        appender.addFilter(new Filter<>() {
            @Override
            public FilterReply decide(ILoggingEvent event) {
                filters.incrementAndGet();
                return event.getMessage().equals("denied") ? FilterReply.DENY : FilterReply.NEUTRAL;
            }
        });
        try {
            appender.start();
            appender.doAppend(event(context, "denied"));
            assertThat(Files.readString(file)).isEqualTo("previous");
            verify(session, times(0)).openFiles();
            appender.doAppend(event(context, "accepted"));
            assertThat(Files.readString(file).strip()).isEqualTo("accepted");
            verify(session, times(1)).openFiles();
            assertThat(filters).hasValue(2);
            appender.stop();
            appender.doAppend(event(context, "after-stop"));
            assertThat(Files.readString(file).strip()).isEqualTo("accepted");
        } finally {
            appender.stop();
            encoder.stop();
            context.stop();
        }
    }

    private static LoggingEvent event(LoggerContext context, String message) {
        return new LoggingEvent(SessionFileAppenderTest.class.getName(),
                context.getLogger("fixture"), Level.INFO, message, null, null);
    }
}

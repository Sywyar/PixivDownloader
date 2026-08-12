package top.sywyar.pixivdownload.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.pattern.TargetLengthBasedClassNameAbbreviator;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HtmlLogLayoutTest {

    @Test
    @DisplayName("HTML 日志沿用文本日志的 logger 和异常格式")
    void matchesTextLogLoggerAndThrowableFormatting() {
        IllegalArgumentException cause = new IllegalArgumentException("root cause");
        cause.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("example.Root", "fail", "Root.java", 12),
                new StackTraceElement("example.Shared", "run", "Shared.java", 34)
        });
        IllegalStateException failure = new IllegalStateException("outer failure", cause);
        failure.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("example.Outer", "call", "Outer.java", 56),
                new StackTraceElement("example.Shared", "run", "Shared.java", 34)
        });
        ThrowableProxy proxy = new ThrowableProxy(failure);

        LoggingEvent event = new LoggingEvent();
        event.setLevel(Level.ERROR);
        event.setLoggerName("top.sywyar.pixivdownload.plugin.registry.PluginRegistry");
        event.setThreadName("test-thread");
        event.setMessage("probe");
        event.setTimeStamp(0L);
        event.setThrowableProxy(proxy);

        HtmlLogLayout layout = new HtmlLogLayout();
        layout.start();
        String html = layout.doLayout(event);

        assertThat(html)
                .contains(new TargetLengthBasedClassNameAbbreviator(36).abbreviate(event.getLoggerName()))
                .contains(ThrowableProxyUtil.asString(proxy));
    }
}

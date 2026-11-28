package top.sywyar.pixivdownload.pixivcontent;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.slf4j.LoggerFactory;

/** 在单个测试内捕获某个类的日志事件，结束时摘除。 */
final class LogCapture implements AutoCloseable {
    private final Logger logger;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    LogCapture(Class<?> owner) {
        logger = (Logger) LoggerFactory.getLogger(owner);
        appender.start();
        logger.addAppender(appender);
    }

    List<String> messages(Level level) {
        return appender.list.stream().filter(event -> event.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Override public void close() {
        logger.detachAppender(appender);
        appender.stop();
    }
}

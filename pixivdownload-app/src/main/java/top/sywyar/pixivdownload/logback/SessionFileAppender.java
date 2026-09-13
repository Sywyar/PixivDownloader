package top.sywyar.pixivdownload.logback;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.FileAppender;

/** 首条事件到达后才锁定日志会话并打开文件，避免无日志的工具进程覆盖历史。 */
public final class SessionFileAppender extends FileAppender<ILoggingEvent> {
    private boolean initialized;

    @Override
    public void start() {
        started = true;
    }

    @Override
    protected void append(ILoggingEvent event) {
        streamWriteLock.lock();
        try {
            if (!isStarted()) return;
            if (!initialized) {
                initialized = true;
                Object value = context.getObject(LogSession.class.getName());
                if (!(value instanceof LogSession session) || !session.openFiles()) return;
                started = false;
                super.start();
            }
            if (getOutputStream() != null) super.append(event);
        } finally {
            streamWriteLock.unlock();
        }
    }
}

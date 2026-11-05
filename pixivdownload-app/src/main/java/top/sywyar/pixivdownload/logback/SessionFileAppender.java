package top.sywyar.pixivdownload.logback;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import ch.qos.logback.core.FileAppender;
import ch.qos.logback.core.encoder.Encoder;

/** 首条接受事件到达后才锁定日志会话并打开文件，避免无日志的工具进程覆盖历史。 */
public final class SessionFileAppender extends AppenderBase<ILoggingEvent> {
    private final FileAppender<ILoggingEvent> output = new FileAppender<>();
    private boolean initialized;

    public void setFile(String file) {
        output.setFile(file);
    }

    public void setAppend(boolean append) {
        output.setAppend(append);
    }

    public void setEncoder(Encoder<ILoggingEvent> encoder) {
        output.setEncoder(encoder);
    }

    @Override
    public void start() {
        output.setContext(context);
        output.setName(getName());
        super.start();
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (!initialized) {
            initialized = true;
            Object value = context.getObject(LogSession.class.getName());
            if (!(value instanceof LogSession session) || !session.openFiles()) return;
            output.start();
        }
        if (output.isStarted()) output.doAppend(event);
    }

    @Override
    public synchronized void stop() {
        super.stop();
        output.stop();
    }
}

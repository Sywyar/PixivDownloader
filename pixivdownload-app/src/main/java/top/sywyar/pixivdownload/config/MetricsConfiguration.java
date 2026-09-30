package top.sywyar.pixivdownload.config;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggerContextListener;
import ch.qos.logback.classic.spi.TurboFilterList;
import ch.qos.logback.classic.turbo.TurboFilter;
import io.micrometer.core.instrument.binder.logging.LogbackMetrics;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Configuration(proxyBeanMethods = false)
public class MetricsConfiguration {

    @Bean
    LogbackMetrics logbackMetrics() {
        return new ContextLogbackMetrics((LoggerContext) LoggerFactory.getILoggerFactory());
    }

    static final class ContextLogbackMetrics extends LogbackMetrics {

        private final OwnedLoggerContext ownedContext;

        ContextLogbackMetrics(LoggerContext loggerContext) {
            this(new OwnedLoggerContext(loggerContext));
        }

        private ContextLogbackMetrics(OwnedLoggerContext ownedContext) {
            super(List.of(), ownedContext);
            this.ownedContext = ownedContext;
        }

        @Override
        public void close() {
            ownedContext.detach();
            super.close();
        }
    }

    // Micrometer 关闭时只撤回过滤器；此适配器记录它实际注册的监听器并随上下文撤回。
    private static final class OwnedLoggerContext extends LoggerContext {

        private final LoggerContext delegate;
        private final List<LoggerContextListener> listeners = new ArrayList<>();
        private boolean closed;

        private OwnedLoggerContext(LoggerContext delegate) {
            this.delegate = delegate;
        }

        @Override
        public synchronized void addListener(LoggerContextListener listener) {
            if (closed) return;
            listeners.add(listener);
            delegate.addListener(listener);
        }

        @Override
        public synchronized void addTurboFilter(TurboFilter filter) {
            if (!closed) delegate.addTurboFilter(filter);
        }

        @Override
        public TurboFilterList getTurboFilterList() {
            return delegate.getTurboFilterList();
        }

        synchronized void detach() {
            // 先拒绝迟到的 reset 回调，再由 LogbackMetrics 撤回过滤器。
            closed = true;
            listeners.forEach(delegate::removeListener);
            listeners.clear();
        }
    }
}

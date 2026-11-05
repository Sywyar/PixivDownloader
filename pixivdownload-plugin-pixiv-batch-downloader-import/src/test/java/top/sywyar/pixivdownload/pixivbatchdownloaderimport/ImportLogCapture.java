package top.sywyar.pixivdownload.pixivbatchdownloaderimport;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import org.slf4j.LoggerFactory;
import top.sywyar.pixivdownload.i18n.LocaleBundlePolicy;
import top.sywyar.pixivdownload.i18n.ResourceBundleMessageResolver;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

final class ImportLogCapture extends AppenderBase<ILoggingEvent> implements AutoCloseable {
    final List<ILoggingEvent> events = new CopyOnWriteArrayList<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(ImportDiagnostics.class);
    private final Level previous = logger.getLevel();
    private final boolean additive = logger.isAdditive();
    final ImportDiagnostics diagnostics = new ImportDiagnostics(ResourceBundleMessageResolver.of(
            null, getClass().getClassLoader(), new LocaleBundlePolicy() {
                public Locale normalize(Locale requested) { return Locale.US; }
                public List<String> resourceSuffixChain(Locale requested) { return List.of("en", ""); }
            }, "i18n.web.pixiv-batch-downloader-import"));

    ImportLogCapture() {
        setContext(logger.getLoggerContext());
        start();
        logger.setLevel(Level.INFO);
        logger.setAdditive(false);
        logger.addAppender(this);
    }

    @Override protected void append(ILoggingEvent event) {
        event.prepareForDeferredProcessing();
        events.add(event);
    }

    List<String> messages() { return events.stream().map(ILoggingEvent::getFormattedMessage).toList(); }

    @Override public void close() {
        logger.detachAppender(this);
        logger.setLevel(previous);
        logger.setAdditive(additive);
        stop();
    }
}

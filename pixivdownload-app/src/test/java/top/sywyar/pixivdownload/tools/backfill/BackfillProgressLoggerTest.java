package top.sywyar.pixivdownload.tools.backfill;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import top.sywyar.pixivdownload.i18n.MessageBundles;
import top.sywyar.pixivdownload.tools.ArtworksBackFill;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("回填日志频率与阶段")
class BackfillProgressLoggerTest {
    @Test
    @DisplayName("首次阶段立即输出，重复本地与网络阶段限频，短任务也有终态计数")
    void logsStagesAndThrottlesRepeatedWork() {
        var logger = (Logger) LoggerFactory.getLogger(ArtworksBackFill.class.getName() + ".progress");
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        var clock = new AtomicLong(-TimeUnit.DAYS.toNanos(1));
        var progress = new BackfillProgressLogger(clock::get);
        try {
            progress.accept(value(ArtworksBackFill.Phase.IMPORTING, 0, null));
            progress.accept(value(ArtworksBackFill.Phase.SCANNING, 0, null));
            progress.accept(value(ArtworksBackFill.Phase.LOCAL, 0, 200));
            progress.accept(value(ArtworksBackFill.Phase.NETWORK, 0, 200));
            for (int i = 0; i < 100; i++) {
                progress.accept(value(ArtworksBackFill.Phase.LOCAL, i, 200));
                progress.accept(value(ArtworksBackFill.Phase.NETWORK, i, 200));
            }
            assertEquals(4, appender.list.size());
            clock.addAndGet(TimeUnit.SECONDS.toNanos(30) - 1);
            progress.accept(value(ArtworksBackFill.Phase.LOCAL, 100, 200));
            assertEquals(4, appender.list.size());
            clock.incrementAndGet();
            progress.accept(value(ArtworksBackFill.Phase.LOCAL, 101, 200));
            assertEquals(5, appender.list.size());
            progress.accept(value(ArtworksBackFill.Phase.FINISHED, 200, 200));
            assertEquals(6, appender.list.size());
            assertEquals(MessageBundles.getForLog("artworks-backfill.log.phase-progress",
                    ArtworksBackFill.Phase.FINISHED, 200, 200, 17, 3, 2),
                    appender.list.get(5).getFormattedMessage());
            assertEquals(MessageBundles.getForLog("artworks-backfill.log.phase-progress",
                    ArtworksBackFill.Phase.IMPORTING, 0, "?", 17, 3, 2),
                    appender.list.get(0).getFormattedMessage());
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private static ArtworksBackFill.Progress value(ArtworksBackFill.Phase phase, int processed, Integer total) {
        return new ArtworksBackFill.Progress(phase, processed, total, 17, 3, 2, null, null, null);
    }
}

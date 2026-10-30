package top.sywyar.pixivdownload.tools.backfill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.sywyar.pixivdownload.i18n.MessageBundles;
import top.sywyar.pixivdownload.tools.ArtworksBackFill;

import java.util.EnumSet;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** 每次执行只保留已报告阶段和上次输出时间，不缓存进度事件。 */
public final class BackfillProgressLogger implements Consumer<ArtworksBackFill.Progress> {
    private static final Logger LOG = LoggerFactory.getLogger(ArtworksBackFill.class.getName() + ".progress");
    private final EnumSet<ArtworksBackFill.Phase> reported = EnumSet.noneOf(ArtworksBackFill.Phase.class);
    private final LongSupplier clock;
    private long lastLogAt;

    public BackfillProgressLogger() { this(System::nanoTime); }

    BackfillProgressLogger(LongSupplier clock) { this.clock = clock; }

    @Override
    public void accept(ArtworksBackFill.Progress progress) {
        long now = clock.getAsLong();
        if (reported.add(progress.phase()) || now - lastLogAt >= TimeUnit.SECONDS.toNanos(30)) {
            lastLogAt = now;
            LOG.info(MessageBundles.getForLog("artworks-backfill.log.phase-progress", progress.phase(),
                    progress.processed(), progress.total() == null ? "?" : progress.total(),
                    progress.changed(), progress.remaining(), progress.unavailable()));
        }
    }
}

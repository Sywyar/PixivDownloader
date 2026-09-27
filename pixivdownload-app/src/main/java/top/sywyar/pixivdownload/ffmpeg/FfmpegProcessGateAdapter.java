package top.sywyar.pixivdownload.ffmpeg;

import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.core.ffmpeg.FfmpegProcessGate;

import java.util.concurrent.CancellationException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

@Component
public final class FfmpegProcessGateAdapter implements FfmpegProcessGate {
    private final Semaphore permits;

    public FfmpegProcessGateAdapter(FfmpegProperties properties) {
        permits = new Semaphore(properties.getMaxConcurrent(), true);
    }

    @Override
    public Permit acquire(BooleanSupplier cancellationRequested) {
        try {
            while (true) {
                if (Thread.currentThread().isInterrupted()
                        || cancellationRequested != null && cancellationRequested.getAsBoolean()) {
                    throw new CancellationException("media processing cancelled");
                }
                if (permits.tryAcquire(200, TimeUnit.MILLISECONDS)) {
                    AtomicBoolean closed = new AtomicBoolean();
                    return () -> {
                        if (closed.compareAndSet(false, true)) permits.release();
                    };
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancellationException("media processing cancelled");
        }
    }
}

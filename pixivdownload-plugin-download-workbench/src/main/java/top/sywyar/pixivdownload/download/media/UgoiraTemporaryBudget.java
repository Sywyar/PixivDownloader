package top.sywyar.pixivdownload.download.media;

import java.io.IOException;

/** 同一服务实例内的临时空间预留；失败时仍持有旧额度，直到实际清理结束。 */
public final class UgoiraTemporaryBudget {
    private final long maximumBytes;
    private long reserved;

    public UgoiraTemporaryBudget(long maximumBytes) {
        if (maximumBytes <= 0) throw new IllegalArgumentException("Invalid temporary budget");
        this.maximumBytes = maximumBytes;
    }

    public Lease open(long bytes) throws IOException {
        Lease lease = new Lease();
        lease.update(bytes);
        return lease;
    }

    public final class Lease implements AutoCloseable {
        private long bytes;
        private boolean closed;

        private Lease() {}

        public void update(long next) throws IOException {
            synchronized (UgoiraTemporaryBudget.this) {
                if (closed || next < 0) throw new IllegalStateException("Invalid temporary reservation");
                long others = reserved - bytes;
                if (next > maximumBytes - others) throw new IOException("Ugoira temporary byte limit exceeded");
                reserved = others + next;
                bytes = next;
            }
        }

        @Override
        public void close() {
            synchronized (UgoiraTemporaryBudget.this) {
                if (closed) return;
                reserved -= bytes;
                closed = true;
            }
        }
    }
}

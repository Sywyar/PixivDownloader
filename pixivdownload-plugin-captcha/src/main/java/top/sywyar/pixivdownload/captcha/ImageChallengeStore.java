package top.sywyar.pixivdownload.captcha;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/** 图片与答案仅驻留当前插件实例；过期、消费及卸载均释放引用。 */
final class ImageChallengeStore implements AutoCloseable {
    static final long LIFETIME_MILLIS = 120_000;
    static final int MAX_ENTRIES = 256;
    static final int MAX_IMAGE_BYTES = 1024 * 1024;
    static final long MAX_TOTAL_BYTES = 16L * 1024 * 1024;
    private final Clock clock;
    private final String generation = UUID.randomUUID().toString();
    private final Semaphore generators = new Semaphore(4);
    private final Map<String, Entry> entries = new HashMap<>();
    private long bytes;
    private boolean closed;

    record Images(String answer, List<byte[]> images, int width, int height, int pieceWidth) { }
    record Created(String id, Images images, long expiresAt) { }
    private record Entry(String policy, String attempt, Images images, long expiresAt, long bytes) { }

    ImageChallengeStore() { this(Clock.systemUTC()); }
    ImageChallengeStore(Clock clock) { this.clock = clock; }
    String generation() { return generation; }

    Created create(String policy, String attempt, Supplier<Images> factory) {
        synchronized (this) { checkCapacity(0); }
        if (!generators.tryAcquire()) throw new IllegalStateException("CAPTCHA_IMAGE_BUSY");
        try {
            Images images = factory.get();
            if (images.images().isEmpty() || images.images().size() > 2)
                throw new IllegalStateException("CAPTCHA_IMAGE_INVALID");
            long size = 0;
            for (byte[] image : images.images()) {
                if (image.length == 0 || image.length > MAX_IMAGE_BYTES)
                    throw new IllegalStateException("CAPTCHA_IMAGE_INVALID");
                size += image.length;
            }
            synchronized (this) {
                checkCapacity(size);
                String id = UUID.randomUUID().toString();
                long expires = clock.millis() + LIFETIME_MILLIS;
                entries.put(id, new Entry(policy, attempt, images, expires, size));
                bytes += size;
                return new Created(id, images, expires);
            }
        } finally { generators.release(); }
    }

    synchronized Images consume(String id, String policy, String attempt) {
        purge();
        Entry entry = entries.get(id);
        if (entry == null || !entry.policy().equals(policy) || !entry.attempt().equals(attempt)) return null;
        entries.remove(id);
        bytes -= entry.bytes();
        return entry.images();
    }

    synchronized byte[] image(String id, int index) {
        purge();
        Entry entry = entries.get(id);
        return entry == null || index < 0 || index >= entry.images().images().size()
                ? null : entry.images().images().get(index);
    }

    private void checkCapacity(long addedBytes) {
        purge();
        if (closed) throw new IllegalStateException("CAPTCHA_IMAGE_CLOSED");
        if (entries.size() >= MAX_ENTRIES || bytes >= MAX_TOTAL_BYTES || bytes + addedBytes > MAX_TOTAL_BYTES)
            throw new IllegalStateException("CAPTCHA_IMAGE_CAPACITY");
    }

    private void purge() {
        long now = clock.millis();
        entries.values().removeIf(entry -> {
            if (entry.expiresAt() > now) return false;
            bytes -= entry.bytes();
            return true;
        });
    }

    @Override public synchronized void close() { closed = true; entries.clear(); bytes = 0; }
}

package top.sywyar.pixivdownload.pixivcontent;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PageManifestCacheTest {
    private static final PageManifestCache.Manifest SMALL = manifest("preview");
    private static final Supplier<PageManifestCache.Manifest> MISS = () -> { throw new AssertionError("unexpected reload"); };

    @Test void hitsDoNotExtendExpiryAndAnOldReadCannotEvictAReplacement() {
        var now = new AtomicLong();
        var cache = new PageManifestCache(now::get);
        var old = cache.read(1, null, () -> SMALL);
        now.set(PageManifestCache.TTL_NANOS - 1);
        assertSame(SMALL, cache.read(1, null, MISS).manifest());
        now.incrementAndGet();
        var fresh = cache.read(1, null, () -> manifest("changed"));
        assertFalse(fresh.cached());
        cache.invalidate(old);
        assertSame(fresh.manifest(), cache.read(1, null, MISS).manifest());
    }

    @Test void evictsByAccessOrderAndTotalWeightAndServesOversizedManifestsWithoutRetainingThem() {
        var cache = new PageManifestCache(() -> 0L);
        for (int id = 1; id <= PageManifestCache.MAX_MANIFESTS; id++) cache.read(id, null, () -> SMALL);
        assertTrue(cache.read(1, null, MISS).cached());
        cache.read(1000, null, () -> SMALL);
        assertFalse(cache.read(2, null, () -> SMALL).cached());
        assertTrue(cache.read(1, null, MISS).cached());

        var half = manifest("x".repeat(PageManifestCache.MAX_WEIGHT / 4));
        var oversized = manifest("x".repeat(PageManifestCache.MAX_WEIGHT / 2));
        cache.read(2000, null, () -> half);
        cache.read(2001, null, () -> half);
        assertTrue(cache.read(2001, null, MISS).cached());
        assertFalse(cache.read(2000, null, () -> half).cached());
        for (int i = 0; i < 2; i++) assertFalse(cache.read(3000, null, () -> oversized).cached());
    }

    @Test void sharesTheSameLoadAndAConcurrentDenialPreventsOlderLoadsFromFillingTheCache() throws Exception {
        var cache = new PageManifestCache(System::nanoTime);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = threads.submit(() -> cache.read(1, "A", () -> {
                started.countDown();
                waitFor(release);
                return SMALL;
            }));
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS));
                var second = threads.submit(() -> cache.read(1, "A", MISS));
                assertSame(SMALL, cache.read(2, "A", () -> SMALL).manifest());
                release.countDown();
                assertSame(SMALL, first.get(5, TimeUnit.SECONDS).manifest());
                assertSame(SMALL, second.get(5, TimeUnit.SECONDS).manifest());
            } finally { release.countDown(); }

            var pending = new CountDownLatch(1);
            var resume = new CountDownLatch(1);
            var denied = ContentFailure.upstream(403);
            var old = threads.submit(() -> cache.read(3, "A", () -> {
                pending.countDown();
                waitFor(resume);
                return SMALL;
            }));
            try {
                assertTrue(pending.await(5, TimeUnit.SECONDS));
                cache.denied(3, "A", denied);
                var current = cache.read(3, "A", () -> manifest("fresh"));
                resume.countDown();
                assertSame(denied, assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> old.get(5, TimeUnit.SECONDS)).getCause());
                assertSame(current.manifest(), cache.read(3, "A", MISS).manifest());
            } finally { resume.countDown(); }
        }
    }

    @Test void overflowLoadsRemainAvailableAndDoNotAccumulatePendingOrCachedState() throws Exception {
        var cache = new PageManifestCache(() -> 0L);
        int limit = PageManifestCache.MAX_IN_FLIGHT;
        var started = new CountDownLatch(limit);
        var release = new CountDownLatch(1);
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                for (int i = 0; i < limit; i++) {
                    int key = i;
                    threads.submit(() -> cache.read(key, null, () -> {
                        started.countDown();
                        waitFor(release);
                        return SMALL;
                    }));
                }
                assertTrue(started.await(5, TimeUnit.SECONDS));
                assertFalse(cache.read(limit, null, () -> SMALL).cached());
                assertFalse(cache.read(limit, null, () -> SMALL).cached());
            } finally { release.countDown(); }
        }
        assertFalse(cache.read(limit, null, () -> SMALL).cached());
        assertTrue(cache.read(limit, null, MISS).cached());
    }

    private static void waitFor(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static PageManifestCache.Manifest manifest(String preview) {
        return new PageManifestCache.Manifest(List.of(new PageManifestCache.Page(preview, "original", 2, 3)));
    }
}

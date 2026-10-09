package top.sywyar.pixivdownload.download.media;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class UgoiraTemporaryBudgetTest {
    @Test
    @DisplayName("累计预留包含边界，超限不吞旧额度，关闭幂等且可重新使用")
    void reservationsRemainBoundedAndRelease() throws Exception {
        var budget = new UgoiraTemporaryBudget(100);
        var first = budget.open(60);
        var second = budget.open(40);
        assertThrows(IOException.class, () -> first.update(61));
        assertThrows(IOException.class, () -> budget.open(1));
        second.close();
        second.close();
        assertThrows(IOException.class, () -> budget.open(41));
        first.update(100);
        first.close();
        assertThrows(IllegalStateException.class, () -> first.update(1));
        try (var ignored = budget.open(100)) {
            assertThrows(IOException.class, () -> budget.open(Long.MAX_VALUE));
        }
    }

    @Test
    @DisplayName("并发作品不能重复预留同一份空间")
    void concurrentReservationsAreAtomic() throws Exception {
        var budget = new UgoiraTemporaryBudget(100);
        var start = new CountDownLatch(1);
        var ready = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        java.util.concurrent.Callable<Boolean> task = () -> {
            assertTrue(start.await(5, TimeUnit.SECONDS));
            UgoiraTemporaryBudget.Lease lease = null;
            try {
                lease = budget.open(60);
                return true;
            } catch (IOException exceeded) {
                return false;
            } finally {
                ready.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                if (lease != null) lease.close();
            }
        };
        try {
            var first = pool.submit(task);
            var second = pool.submit(task);
            start.countDown();
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            assertThrows(IOException.class, () -> budget.open(41));
            release.countDown();
            assertNotEquals(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
            try (var ignored = budget.open(100)) { }
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}

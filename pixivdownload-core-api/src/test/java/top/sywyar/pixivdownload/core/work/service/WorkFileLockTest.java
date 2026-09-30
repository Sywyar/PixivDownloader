package top.sywyar.pixivdownload.core.work.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("作品文件互斥")
class WorkFileLockTest {
    @Test
    @DisplayName("同作品等待，异作品独立，释放后等待者继续")
    void isolatesWorksAndReleasesWaiters() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        Future<?> waiting;
        try {
            try (var first = WorkFileLock.acquire(WorkType.ARTWORK, 1);
                 var nested = WorkFileLock.acquire(WorkType.ARTWORK, 1)) {
                waiting = workers.submit(() -> {
                    started.countDown();
                    try (var lease = WorkFileLock.acquire(WorkType.ARTWORK, 1)) { entered.countDown(); }
                });
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                workers.submit(() -> { try (var other = WorkFileLock.acquire(WorkType.NOVEL, 1)) {} }).get(5, TimeUnit.SECONDS);
                assertThat(entered.getCount()).isEqualTo(1);
            }
            waiting.get(5, TimeUnit.SECONDS);
            assertThat(entered.getCount()).isZero();
        } finally { workers.shutdownNow(); }
    }
}

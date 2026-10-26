package top.sywyar.pixivdownload.download.media;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("扫描退出测试闸门")
class ScanGateTest {
    @Test
    @DisplayName("连续中断只通知取消，释放后恢复线程中断标记")
    void repeatedInterruptionsDoNotReleaseScan() throws Exception {
        try (var scan = new ScanGate()) {
            var result = new FutureTask<>(() -> {
                scan.awaitRelease();
                return Thread.currentThread().isInterrupted();
            });
            var worker = new Thread(result, "scan-gate-test");
            worker.start();
            try {
                assertTrue(scan.entered.await(5, TimeUnit.SECONDS));
                for (int attempt = 0; attempt < 3; attempt++) {
                    worker.interrupt();
                    assertNotNull(scan.interruptions.poll(5, TimeUnit.SECONDS));
                    assertFalse(scan.exited.isDone());
                }
                assertThrows(TimeoutException.class, () -> result.get(100, TimeUnit.MILLISECONDS));
                scan.close();
                scan.exited.get(5, TimeUnit.SECONDS);
                assertTrue(result.get(5, TimeUnit.SECONDS));
            } finally {
                scan.close();
                worker.join(TimeUnit.SECONDS.toMillis(5));
                assertFalse(worker.isAlive());
            }
        }
    }
}

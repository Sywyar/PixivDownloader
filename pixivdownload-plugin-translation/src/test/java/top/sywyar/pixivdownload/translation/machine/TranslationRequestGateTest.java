package top.sywyar.pixivdownload.translation.machine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class TranslationRequestGateTest {
    @Test
    @DisplayName("并发上限、按文本计费间隔和关闭唤醒等待者")
    void limitsConcurrencyAndWakesOnClose() throws Exception {
        var gate = new TranslationRequestGate(1, 30);
        var worker = Executors.newSingleThreadExecutor();
        try {
            gate.acquire(3);
            long started = System.nanoTime();
            Future<Long> next = worker.submit(() -> { gate.acquire(1); return System.nanoTime(); });
            assertThatThrownBy(() -> next.get(20, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            gate.release();
            assertThat(next.get(2, TimeUnit.SECONDS) - started).isGreaterThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(70));
            Future<?> waiting = worker.submit(() -> { gate.acquire(1); return null; });
            gate.close();
            assertThatThrownBy(() -> waiting.get(2, TimeUnit.SECONDS)).hasCauseInstanceOf(InterruptedException.class);
            gate.release();
        } finally { gate.close(); worker.shutdownNow(); }
    }

    @Test
    @DisplayName("限流冷却覆盖普通间隔，中断不占用发送名额")
    void cooldownAndInterruption() throws Exception {
        var gate = new TranslationRequestGate(2, 0);
        gate.coolDown(80);
        long started = System.nanoTime();
        gate.acquire(1);
        assertThat(System.nanoTime() - started).isGreaterThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(60));
        gate.release();
        Thread.currentThread().interrupt();
        try { assertThatThrownBy(() -> gate.acquire(1)).isInstanceOf(InterruptedException.class); }
        finally { Thread.interrupted(); gate.close(); }
    }
}

package top.sywyar.pixivdownload.gui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("桌面应用退出请求")
class GuiLauncherExitTest {

    @Test
    @DisplayName("桌面 UI 关闭阻塞时也立即释放 AWT 事件线程，再触发进程退出")
    void exitRequestDoesNotBlockAwtEventThread() throws Exception {
        CountDownLatch closeStarted = new CountDownLatch(1);
        CountDownLatch allowClose = new CountDownLatch(1);
        CountDownLatch exitStarted = new CountDownLatch(1);
        AtomicInteger closeCalls = new AtomicInteger();
        AtomicInteger exitCalls = new AtomicInteger();
        AtomicReference<String> exitThreadName = new AtomicReference<>();
        ExecutorService caller = Executors.newSingleThreadExecutor();

        Future<?> request = caller.submit(() -> {
            try {
                Runnable closeAction = () -> {
                    closeCalls.incrementAndGet();
                    exitThreadName.set(Thread.currentThread().getName());
                    closeStarted.countDown();
                    await(allowClose);
                };
                Runnable exitAction = () -> {
                    exitCalls.incrementAndGet();
                    exitStarted.countDown();
                };
                SwingUtilities.invokeAndWait(() -> {
                    GuiLauncher.requestApplicationExit(closeAction, exitAction);
                    GuiLauncher.requestApplicationExit(closeAction, exitAction);
                });
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });

        try {
            assertThat(closeStarted.await(5, TimeUnit.SECONDS)).isTrue();
            request.get(5, TimeUnit.SECONDS);
            assertThat(exitThreadName.get()).isEqualTo("desktop-ui-exit");
            assertThat(closeCalls.get()).isEqualTo(1);
            assertThat(exitStarted.getCount()).isEqualTo(1L);

            allowClose.countDown();
            assertThat(exitStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(exitCalls.get()).isEqualTo(1);
        } finally {
            allowClose.countDown();
            caller.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

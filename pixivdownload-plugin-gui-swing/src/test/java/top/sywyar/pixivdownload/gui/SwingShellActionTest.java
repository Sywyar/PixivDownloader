package top.sywyar.pixivdownload.gui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SwingShellActionTest {
    @Test
    @DisplayName("打开动作不阻塞 EDT，同一 owner 的重复点击不创建等待任务，完成后可以重试")
    void runsOffEdtWithoutQueuingRepeatedClicks() throws Exception {
        var action = new SwingShellAction();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var retried = new CountDownLatch(1);
        var worker = new AtomicReference<Thread>();
        var failure = new AtomicReference<Exception>();
        try {
            SwingUtilities.invokeAndWait(() -> action.run("test-shell-open", () -> {
                worker.set(Thread.currentThread());
                entered.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("test wait expired");
            }, failure::set));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(worker.get().isDaemon()).isTrue();
            SwingUtilities.invokeAndWait(() -> {
                assertThat(Thread.currentThread()).isNotSameAs(worker.get());
                action.run("test-duplicate", retried::countDown, failure::set);
            });
            release.countDown();
            worker.get().join(5000);
            assertThat(worker.get().isAlive()).isFalse();
            assertThat(retried.getCount()).isEqualTo(1);
            action.run("test-retry", retried::countDown, failure::set);
            assertThat(retried.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(failure.get()).isNull();
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("宿主打开失败在 EDT 报告原异常，失败后释放占用")
    void reportsFailureOnEdtAndAllowsRetry() throws Exception {
        var action = new SwingShellAction();
        var expected = new IOException("shell unavailable");
        var reported = new CountDownLatch(1);
        var retried = new CountDownLatch(1);
        var actual = new AtomicReference<Exception>();
        var callbackThread = new AtomicReference<Thread>();
        var worker = new AtomicReference<Thread>();
        action.run("test-shell-failure", () -> {
            worker.set(Thread.currentThread());
            throw expected;
        }, failure -> {
            actual.set(failure);
            callbackThread.set(Thread.currentThread());
            reported.countDown();
        });
        assertThat(reported.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(actual.get()).isSameAs(expected);
        SwingUtilities.invokeAndWait(() -> assertThat(Thread.currentThread()).isSameAs(callbackThread.get()));
        worker.get().join(5000);
        action.run("test-retry", retried::countDown, actual::set);
        assertThat(retried.await(5, TimeUnit.SECONDS)).isTrue();
    }
}

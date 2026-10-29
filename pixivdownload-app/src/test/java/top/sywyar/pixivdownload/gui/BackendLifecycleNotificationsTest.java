package top.sywyar.pixivdownload.gui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;
import static top.sywyar.pixivdownload.gui.BackendLifecycleManager.State.*;

@DisplayName("后端状态通知与桌面模型的并发回归")
class BackendLifecycleNotificationsTest {
    private final ExecutorService workers = Executors.newCachedThreadPool();

    @AfterEach
    void cleanUp() throws Exception {
        workers.shutdownNow();
        assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        BackendLifecycleManager.resetForTests();
    }

    @Test
    @DisplayName("模型持锁读取后端时，启动通知不能持有生命周期锁")
    void modelReadAndStartingNotificationDoNotDeadlock() throws Exception {
        ReentrantLock modelLock = new ReentrantLock();
        CountDownLatch modelEntered = new CountDownLatch(1);
        CountDownLatch listenerEntered = new CountDownLatch(1);
        CountDownLatch listenerFinished = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean acquiredModel = new AtomicBoolean();
        BackendLifecycleManager.configure(new String[0], ignored -> {}, args -> context());
        BackendLifecycleManager.addListener(snapshot -> {
            if (snapshot.state() != STARTING) return;
            listenerEntered.countDown();
            try {
                // 有限等待使旧实现也能自行解锁，失败用例不污染后续测试。
                if (modelLock.tryLock(2, TimeUnit.SECONDS)) {
                    try { acquiredModel.set(true); }
                    finally { modelLock.unlock(); }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                listenerFinished.countDown();
            }
        });
        var model = workers.submit(() -> {
            modelLock.lock();
            try {
                modelEntered.countDown();
                await(listenerEntered);
                try { BackendLifecycleManager.requiredBean(Object.class); }
                catch (IllegalStateException ignored) { }
            } finally {
                modelLock.unlock();
            }
        });
        // 确认模型线程先持锁，再允许启动线程进入通知。
        await(modelEntered);
        assertThat(BackendLifecycleManager.startAsync(started::countDown)).isTrue();
        await(listenerFinished);
        model.get(5, TimeUnit.SECONDS);
        await(started);
        assertThat(acquiredModel).as("通知应在模型读取后端后取得模型锁").isTrue();
    }

    @Test
    @DisplayName("已经停止时的完成回调可以等待另一个线程读取生命周期")
    void alreadyStoppedCallbackRunsOutsideLifecycleLock() throws Exception {
        AtomicBoolean completed = new AtomicBoolean();
        assertThat(BackendLifecycleManager.stopAsync(() -> {
            try {
                workers.submit(BackendLifecycleManager::suspendBackendStarts)
                        .get(1, TimeUnit.SECONDS).close();
                completed.set(true);
            } catch (Exception ignored) { }
        })).isTrue();
        assertThat(completed).isTrue();
    }

    @Test
    @DisplayName("初始快照和启停通知按顺序到达，慢监听器不阻断后端关闭")
    void blockedInitialNotificationDoesNotBlockLifecycle() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        List<BackendLifecycleManager.State> states = new CopyOnWriteArrayList<>();
        BackendLifecycleManager.configure(new String[0], ignored -> {}, args -> context());
        var subscription = workers.submit(() -> BackendLifecycleManager.addListener(snapshot -> {
            states.add(snapshot.state());
            if (states.size() == 1) {
                entered.countDown();
                await(release);
            } else if (snapshot.state() == STOPPED) {
                stopped.countDown();
            }
        }));
        try {
            await(entered);
            var start = workers.submit(() -> BackendLifecycleManager.startAsync(started::countDown));
            assertThat(start.get(1, TimeUnit.SECONDS)).isTrue();
            await(started);
            assertThat(BackendLifecycleManager.closeBackendContext(1_000).isContextConfirmedClosed()).isTrue();
            assertThat(states).containsExactly(STOPPED);
        } finally {
            release.countDown();
            subscription.get(5, TimeUnit.SECONDS);
        }
        await(stopped);
        assertThat(states).containsExactly(STOPPED, STARTING, RUNNING, STOPPING, STOPPED);
    }

    @Test
    @DisplayName("回调可以重入启停，失败后重试仍按顺序通知")
    void failedStartCanBeRetriedFromListener() throws Exception {
        List<BackendLifecycleManager.State> states = new CopyOnWriteArrayList<>();
        CountDownLatch stopped = new CountDownLatch(1);
        AtomicBoolean failStart = new AtomicBoolean(true);
        BackendLifecycleManager.configure(new String[0], ignored -> {}, args -> {
            if (failStart.getAndSet(false)) throw new IllegalStateException("startup failure");
            return context();
        });
        BackendLifecycleManager.addListener(snapshot -> {
            states.add(snapshot.state());
            if (snapshot.state() == FAILED) BackendLifecycleManager.startAsync();
            if (snapshot.state() == RUNNING) BackendLifecycleManager.stopAsync(null);
            if (snapshot.state() == STOPPED && states.size() > 1) stopped.countDown();
        });
        assertThat(BackendLifecycleManager.startAsync()).isTrue();
        await(stopped);
        assertThat(states).containsExactly(STOPPED, STARTING, FAILED, STARTING, RUNNING, STOPPING, STOPPED);
    }

    @Test
    @DisplayName("退订使排队通知失效，再次订阅同一监听器只收到新快照")
    void unsubscribeInvalidatesQueuedDeliveries() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch subscribed = new CountDownLatch(1);
        List<BackendLifecycleManager.State> states = new CopyOnWriteArrayList<>();
        BackendLifecycleManager.addListener(snapshot -> {
            entered.countDown();
            await(release);
        });
        BackendLifecycleManager.Listener listener = snapshot -> {
            states.add(snapshot.state());
            subscribed.countDown();
        };
        try {
            await(entered);
            BackendLifecycleManager.addListener(listener);
            BackendLifecycleManager.stopAsync(null);
            BackendLifecycleManager.removeListener(listener);
            BackendLifecycleManager.addListener(listener);
        } finally {
            release.countDown();
        }
        await(subscribed);
        assertThat(states).containsExactly(STOPPED);
    }

    @Test
    @DisplayName("慢监听器的积压有界，恢复后按顺序收到最近状态")
    void backlogKeepsBoundedRecentSnapshots() throws Exception {
        BackendStateNotifications notifications = new BackendStateNotifications();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        List<Integer> delivered = new CopyOnWriteArrayList<>();
        notifications.subscribe(snapshot -> {
            if (snapshot.error() == null) {
                entered.countDown();
                await(release);
                return;
            }
            int value = Integer.parseInt(snapshot.error().getMessage());
            delivered.add(value);
            if (value == 127) completed.countDown();
        }, new BackendLifecycleManager.Snapshot(STOPPED, null));
        try {
            await(entered);
            for (int i = 0; i < 128; i++) {
                notifications.publish(new BackendLifecycleManager.Snapshot(STARTING,
                        new IllegalStateException(Integer.toString(i))));
            }
            release.countDown();
            await(completed);
            assertThat(delivered).containsExactlyElementsOf(
                    java.util.stream.IntStream.range(64, 128).boxed().toList());
        } finally {
            release.countDown();
            notifications.resetForTests();
        }
    }

    @Test
    @DisplayName("致命监听错误仍传播，其他订阅和后续通知继续可用")
    void fatalListenerDoesNotStrandDelivery() throws Exception {
        BackendStateNotifications notifications = new BackendStateNotifications();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch propagated = new CountDownLatch(1);
        CountDownLatch delivered = new CountDownLatch(1);
        Error fatal = new LinkageError("listener failure");
        var previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> {
            if (failure == fatal) propagated.countDown();
            else if (previous != null) previous.uncaughtException(thread, failure);
        });
        BackendLifecycleManager.Listener broken = snapshot -> {
            entered.countDown();
            await(release);
            throw fatal;
        };
        try {
            notifications.subscribe(broken, new BackendLifecycleManager.Snapshot(STOPPED, null));
            await(entered);
            notifications.unsubscribe(broken);
            notifications.subscribe(snapshot -> delivered.countDown(),
                    new BackendLifecycleManager.Snapshot(RUNNING, null));
            release.countDown();
            await(propagated);
            await(delivered);
        } finally {
            release.countDown();
            notifications.resetForTests();
            Thread.setDefaultUncaughtExceptionHandler(previous);
        }
    }

    @Test
    @DisplayName("积压期间新增订阅仍收到初始快照，状态队列上限不吞掉订阅")
    void backlogDoesNotDiscardInitialSubscriptions() {
        BackendStateNotifications notifications = new BackendStateNotifications();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch subscribed = new CountDownLatch(100);
        notifications.subscribe(snapshot -> {
            entered.countDown();
            await(release);
        }, new BackendLifecycleManager.Snapshot(STOPPED, null));
        try {
            await(entered);
            for (int i = 0; i < 100; i++) {
                notifications.subscribe(snapshot -> subscribed.countDown(),
                        new BackendLifecycleManager.Snapshot(STOPPED, null));
            }
            release.countDown();
            await(subscribed);
        } finally {
            release.countDown();
            notifications.resetForTests();
        }
    }

    private static AnnotationConfigApplicationContext context() {
        var context = new AnnotationConfigApplicationContext();
        context.registerBean(Object.class, Object::new);
        context.refresh();
        return context;
    }

    private static void await(CountDownLatch latch) {
        try { assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}

package top.sywyar.pixivdownload.gui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static top.sywyar.pixivdownload.gui.BackendLifecycleManager.Listener;
import static top.sywyar.pixivdownload.gui.BackendLifecycleManager.Snapshot;

/** 后端状态快照串行分发；锁只保护队列，监听器在独立线程上执行。 */
final class BackendStateNotifications {
    private static final Logger log = LoggerFactory.getLogger(BackendStateNotifications.class);
    private static final int MAX_PENDING_UPDATES = 64;

    private static final class Subscription {
        private volatile Listener listener;

        private Subscription(Listener listener) {
            this.listener = listener;
        }
    }

    private record Notification(Snapshot snapshot, List<Subscription> recipients, boolean initial) {}

    private final List<Subscription> subscriptions = new ArrayList<>();
    private final ArrayDeque<Notification> pending = new ArrayDeque<>();
    private int pendingUpdates;
    private Thread worker;
    private long skipped;

    synchronized void subscribe(Listener listener, Snapshot initial) {
        Subscription subscription = new Subscription(listener);
        subscriptions.add(subscription);
        enqueue(new Notification(initial, List.of(subscription), true));
    }

    synchronized void unsubscribe(Listener listener) {
        for (var iterator = subscriptions.iterator(); iterator.hasNext();) {
            Subscription subscription = iterator.next();
            if (subscription.listener == listener) {
                subscription.listener = null;
                iterator.remove();
                pending.removeIf(notification -> notification.initial()
                        && notification.recipients().get(0) == subscription);
                return;
            }
        }
    }

    synchronized void publish(Snapshot snapshot) {
        if (!subscriptions.isEmpty()) {
            enqueue(new Notification(snapshot, List.copyOf(subscriptions), false));
        }
    }

    private void enqueue(Notification notification) {
        // 这是状态投影；慢消费者溢出时保留最近快照，操作完成结果不经过此队列。
        if (!notification.initial()) {
            if (pendingUpdates == MAX_PENDING_UPDATES) {
                for (var iterator = pending.iterator(); iterator.hasNext();) {
                    if (!iterator.next().initial()) {
                        iterator.remove();
                        pendingUpdates--;
                        skipped++;
                        break;
                    }
                }
            }
            pendingUpdates++;
        }
        pending.addLast(notification);
        startWorker();
    }

    private void startWorker() {
        if (worker != null || pending.isEmpty()) return;
        worker = new Thread(this::drain, "backend-state-notifications");
        worker.setDaemon(true);
        worker.start();
    }

    private void drain() {
        try {
            while (true) {
                Notification notification;
                long skippedCount;
                synchronized (this) {
                    notification = pending.pollFirst();
                    skippedCount = skipped;
                    skipped = 0;
                    if (notification == null) return;
                    if (!notification.initial()) pendingUpdates--;
                }
                if (skippedCount != 0) {
                    log.warn("Backend state listener backlog exceeded {}; skipped {} older snapshots",
                            MAX_PENDING_UPDATES, skippedCount);
                }
                Error fatal = null;
                for (Subscription subscription : notification.recipients()) {
                    Listener listener = subscription.listener;
                    if (listener == null) continue;
                    try {
                        listener.onStateChanged(notification.snapshot());
                    } catch (RuntimeException failure) {
                        log.warn("Backend state listener failed", failure);
                    } catch (Error failure) {
                        if (fatal == null) fatal = failure;
                        else if (fatal != failure) fatal.addSuppressed(failure);
                    }
                }
                if (fatal != null) throw fatal;
            }
        } finally {
            synchronized (this) {
                worker = null;
                startWorker();
            }
        }
    }

    void resetForTests() {
        Thread active;
        synchronized (this) {
            subscriptions.forEach(subscription -> subscription.listener = null);
            subscriptions.clear();
            pending.clear();
            pendingUpdates = 0;
            skipped = 0;
            active = worker;
        }
        if (active == null) return;
        try {
            active.join(5_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while cleaning backend state notifications", e);
        }
        if (active.isAlive()) {
            throw new AssertionError("backend state listener did not terminate during test cleanup");
        }
    }
}

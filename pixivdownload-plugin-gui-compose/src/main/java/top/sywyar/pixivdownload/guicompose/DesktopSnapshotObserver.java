package top.sywyar.pixivdownload.guicompose;

import top.sywyar.pixivdownload.guicompose.model.DesktopUiSnapshot;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/** 将模型快照交给渲染器的观察者，只保留排序所需的修订号。 */
final class DesktopSnapshotObserver implements AutoCloseable {
    private final Consumer<DesktopUiSnapshot> observer;
    private final AutoCloseable subscription;
    private long revision;
    private boolean closed;

    DesktopSnapshotObserver(
            DesktopUiSnapshot initial,
            Function<Consumer<DesktopUiSnapshot>, AutoCloseable> subscribe,
            Consumer<DesktopUiSnapshot> observer
    ) {
        revision = Objects.requireNonNull(initial, "initial").revision();
        this.observer = Objects.requireNonNull(observer, "observer");
        subscription = Objects.requireNonNull(subscribe, "subscribe").apply(this::accept);
    }

    private synchronized void accept(DesktopUiSnapshot snapshot) {
        if (closed || snapshot.revision() <= revision) return;
        revision = snapshot.revision();
        observer.accept(snapshot);
    }

    @Override
    public synchronized void close() throws Exception {
        if (closed) return;
        closed = true;
        subscription.close();
    }
}

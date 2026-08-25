package top.sywyar.pixivdownload.guicompose;

import top.sywyar.pixivdownload.guicompose.model.DesktopUiSnapshot;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/** Bridges model snapshot publication to one renderer-owned observer. */
final class DesktopSnapshotObserver implements AutoCloseable {
    private final Consumer<DesktopUiSnapshot> observer;
    private final AutoCloseable subscription;
    private DesktopUiSnapshot current;
    private boolean closed;

    DesktopSnapshotObserver(
            DesktopUiSnapshot initial,
            Function<Consumer<DesktopUiSnapshot>, AutoCloseable> subscribe,
            Consumer<DesktopUiSnapshot> observer
    ) {
        current = Objects.requireNonNull(initial, "initial");
        this.observer = Objects.requireNonNull(observer, "observer");
        subscription = Objects.requireNonNull(subscribe, "subscribe").apply(this::accept);
    }

    private synchronized void accept(DesktopUiSnapshot snapshot) {
        if (closed || snapshot.revision() <= current.revision()) return;
        current = snapshot;
        observer.accept(snapshot);
    }

    @Override
    public synchronized void close() throws Exception {
        if (closed) return;
        closed = true;
        subscription.close();
    }
}

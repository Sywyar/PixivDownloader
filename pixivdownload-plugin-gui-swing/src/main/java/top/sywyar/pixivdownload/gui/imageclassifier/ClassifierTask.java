package top.sywyar.pixivdownload.gui.imageclassifier;

import javax.swing.SwingWorker;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Consumer;

/** 窗口内的一路查询；提交、取消与关闭都由 EDT 调用，只交付最新结果。 */
final class ClassifierTask<T> implements AutoCloseable {
    private final ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(
            1, task -> new Thread(task, "ImageClassifier-Loader"));
    private SwingWorker<T, Void> current;

    void load(
            Callable<T> query,
            Consumer<T> loaded,
            Consumer<Throwable> failed
    ) {
        if (executor.isShutdown()) return;
        cancel();
        current = new SwingWorker<>() {
            @Override
            protected T doInBackground() throws Exception {
                return query.call();
            }

            @Override
            protected void done() {
                if (current != this || isCancelled()) return;
                current = null;
                try {
                    loaded.accept(get());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    failed.accept(interrupted);
                } catch (ExecutionException failure) {
                    if (failure.getCause() instanceof Error fatal) throw fatal;
                    failed.accept(failure.getCause());
                }
            }
        };
        executor.execute(current);
    }

    void cancel() {
        if (current == null) return;
        SwingWorker<T, Void> previous = current;
        current = null;
        previous.cancel(true);
        // I/O 可能不响应中断；保留单个在途查询，并移除尚未执行的过期请求。
        executor.remove(previous);
    }

    @Override
    public void close() {
        cancel();
        executor.shutdownNow();
    }
}

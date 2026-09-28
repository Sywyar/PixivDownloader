package top.sywyar.pixivdownload.gui.imageclassifier;

import javax.swing.ImageIcon;
import javax.swing.SwingWorker;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Consumer;

/** 单个查看器的加载生命周期；加载与关闭都由 EDT 调用。 */
final class ImageViewerLoader implements AutoCloseable {
    private final ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(
            1, task -> new Thread(task, "ImageViewer-Loader"));
    private SwingWorker<ImageIcon, Void> current;

    void load(
            Callable<ImageIcon> decode,
            Consumer<ImageIcon> loaded,
            Consumer<Throwable> failed
    ) {
        if (executor.isShutdown()) return;
        cancelCurrent();
        current = new SwingWorker<>() {
            @Override
            protected ImageIcon doInBackground() throws Exception {
                return decode.call();
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
                    failed.accept(failure.getCause());
                }
            }
        };
        executor.execute(current);
    }

    private void cancelCurrent() {
        if (current == null) return;
        SwingWorker<ImageIcon, Void> previous = current;
        current = null;
        previous.cancel(true);
        // ImageIO 可能不响应中断；保留单个在途解码，并移除尚未执行的过期请求。
        executor.remove(previous);
    }

    @Override
    public void close() {
        cancelCurrent();
        executor.shutdownNow();
    }
}

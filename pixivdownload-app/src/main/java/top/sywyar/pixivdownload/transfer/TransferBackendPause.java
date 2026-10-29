package top.sywyar.pixivdownload.transfer;

import top.sywyar.pixivdownload.gui.BackendLifecycleManager;
import java.io.IOException;

/** 同一次导出的数据库与资源在同一段停写窗口内产生。 */
final class TransferBackendPause implements AutoCloseable {
    private final AutoCloseable reservation;
    private final boolean resume;

    TransferBackendPause() throws Exception {
        reservation = BackendLifecycleManager.suspendBackendStarts();
        boolean stopped = false;
        try {
            var state = BackendLifecycleManager.state();
            if (state == BackendLifecycleManager.State.STARTING || state == BackendLifecycleManager.State.STOPPING)
                throw new IOException("transfer.busy");
            resume = state == BackendLifecycleManager.State.RUNNING;
            stopped = BackendLifecycleManager.closeBackendContext(30_000).result().isContextConfirmedClosed();
            if (!stopped) throw new IOException("transfer.backend-not-stopped");
        } finally { if (!stopped) reservation.close(); }
    }
    @Override public void close() throws Exception {
        reservation.close();
        if (resume) BackendLifecycleManager.startAsync();
    }
}

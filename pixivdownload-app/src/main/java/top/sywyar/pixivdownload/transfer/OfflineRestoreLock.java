package top.sywyar.pixivdownload.transfer;

import java.io.IOException;
import java.util.concurrent.locks.ReentrantLock;

/** 数据库切换与配置恢复共享准入锁，避免同时排入两个重启任务。 */
public final class OfflineRestoreLock implements AutoCloseable {
    private static final ReentrantLock LOCK = new ReentrantLock();
    private OfflineRestoreLock() {}
    public static OfflineRestoreLock acquire() throws IOException {
        if (!LOCK.tryLock()) throw new IOException("transfer.busy");
        return new OfflineRestoreLock();
    }
    @Override public void close() { LOCK.unlock(); }
}

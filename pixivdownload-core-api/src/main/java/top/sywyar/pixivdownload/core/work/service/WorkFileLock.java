package top.sywyar.pixivdownload.core.work.service;

import top.sywyar.pixivdownload.core.work.model.WorkType;

import java.util.Collection;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** 同进程作品文件操作互斥；下载子任务由持锁的父任务等待，不得再次取得父任务的锁。 */
public final class WorkFileLock implements AutoCloseable {
    private static final ConcurrentHashMap<String, Entry> ENTRIES = new ConcurrentHashMap<>();
    private final String key;
    private final Entry entry;
    private boolean closed;

    private static final class Entry {
        final ReentrantLock lock = new ReentrantLock();
        int references;
    }

    private WorkFileLock(String key) {
        this.key = key;
        entry = ENTRIES.compute(key, (ignored, current) -> {
            Entry value = current == null ? new Entry() : current;
            value.references++;
            return value;
        });
        try {
            entry.lock.lockInterruptibly();
        } catch (InterruptedException cancelled) {
            releaseReference();
            Thread.currentThread().interrupt();
            throw new CancellationException("Work file operation interrupted");
        }
    }

    /**
     * 取得单个作品的可重入锁，等待期间响应线程中断。
     * @param type 作品类型
     * @param id 作品标识
     * @return 必须由当前线程关闭的锁句柄
     * @throws CancellationException 等待锁时线程被中断
     */
    public static WorkFileLock acquire(WorkType type, long id) {
        return new WorkFileLock(Objects.requireNonNull(type).name() + ":" + id);
    }

    /**
     * 多作品操作按固定顺序取得全部锁；调用方必须在取得其它作品锁之前调用。
     * @param type 作品类型
     * @param ids 作品标识集合，忽略空值并去重
     * @return 必须由当前线程关闭、按逆序释放全部锁的句柄
     * @throws CancellationException 等待任一锁时线程被中断
     */
    public static AutoCloseable acquireAll(WorkType type, Collection<Long> ids) {
        var locks = new ArrayList<WorkFileLock>();
        try {
            ids.stream().filter(Objects::nonNull).distinct().sorted()
                    .forEach(id -> locks.add(acquire(type, id)));
        } catch (RuntimeException | Error failure) {
            for (int i = locks.size() - 1; i >= 0; i--) locks.get(i).close();
            throw failure;
        }
        return () -> {
            for (int i = locks.size() - 1; i >= 0; i--) locks.get(i).close();
        };
    }

    @Override public void close() {
        if (closed) return;
        entry.lock.unlock();
        closed = true;
        releaseReference();
    }

    private void releaseReference() {
        ENTRIES.compute(key, (ignored, current) -> --entry.references == 0 ? null : entry);
    }
}

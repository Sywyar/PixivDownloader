package top.sywyar.pixivdownload.plugin;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * 外置插件 classloader 回收探针：调用方释放直接持有的强引用后，尽力触发 GC 并观察弱引用是否清除。
 *
 * <p>{@code System.gc()} 不保证在预算内完成回收。调用方先验证注册快照撤回、租约排空等确定性合同；
 * 这些断言只覆盖各自观察的持有路径。弱引用清除提供回收证据，未清除则保留诊断并记为原因未确定，
 * 不能据此认定环境问题或排除其它强引用。
 */
final class ClassLoaderLeakProbes {

    private ClassLoaderLeakProbes() {
    }

    /** 默认预算下尽力回收并返回弱引用是否已被清除（{@code true} = 引用对象已被 GC 回收）。 */
    static boolean awaitCollected(WeakReference<?> ref) {
        return awaitCollected(ref, 25, 40L);
    }

    /**
     * 在最多 {@code attempts} 轮内反复触发 GC + 内存压力，返回弱引用是否已被清除。引用一旦被清除立即返回 {@code true}，
     * 不再继续施加回收压力。
     */
    static boolean awaitCollected(WeakReference<?> ref, int attempts, long sleepMillis) {
        for (int i = 0; i < attempts && ref.get() != null; i++) {
            provokeGc();
            if (ref.get() == null) {
                break;
            }
            sleepQuietly(sleepMillis);
        }
        return ref.get() == null;
    }

    /**
     * 触发一次回收尝试：{@code System.gc()} + {@code runFinalization()}，并施加适度瞬时内存压力。
     * {@code WeakReference} 在任一 GC 周期一旦其引用对象弱可达即被清除，内存压力只为在各类收集器下更稳妥地促成回收。
     */
    private static void provokeGc() {
        System.gc();
        System.runFinalization();
        List<byte[]> ballast = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) {
                ballast.add(new byte[1 << 20]); // ~8MB 瞬时压力
            }
        } catch (OutOfMemoryError ignored) {
            // 压力已达成，放弃继续分配
        } finally {
            ballast.clear();
        }
        System.gc();
        System.runFinalization();
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

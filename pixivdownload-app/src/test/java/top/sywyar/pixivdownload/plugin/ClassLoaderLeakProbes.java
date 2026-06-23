package top.sywyar.pixivdownload.plugin;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * 外置插件 classloader 泄漏回归的测试探针助手：在<b>放掉全部强引用之后</b>尽力促使 JVM 回收弱可达对象，并报告
 * 弱引用是否已被清除。
 *
 * <h2>为什么单纯 {@code System.gc()} 不够、要配「确定性引用链检查」</h2>
 * {@code System.gc()} 只是<b>建议</b>，不同 JVM / 操作系统（尤其 Windows / CI 容器）下弱可达对象不一定在固定次数内
 * 被回收。因此本助手只承担「best-effort 触发回收 + 观察弱引用」这一<b>环境相关</b>的辅助角色：是否真正发生<b>业务级
 * 泄漏</b>（注册中心 / 分发表 / 缓存仍持有插件）必须由各探针测试在调用本助手<b>之前</b>，用确定性的引用链断言
 * （各注册中心快照不再暴露该插件）钉死。GC 探针通过则给出更强证据；GC 探针在不稳定环境下未回收时，测试据确定性
 * 断言已排除业务泄漏，可把「未回收」判为环境不稳定（inconclusive）而非失败——见各探针测试的诊断分支。
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
     * 不再继续浪费时间。不抛出、不依赖固定 sleep 时长的「祈祷式」一次回收。
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

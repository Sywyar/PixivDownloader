package top.sywyar.pixivdownload.gui;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 调试模式复选框的「解锁」状态（会话级，仅存于内存，不落盘）。
 * <p>
 * 默认锁定 —— GUI「配置 → 服务器」分组中的 {@code debug.enabled} 复选框隐藏。
 * 当用户在任意 GUI 界面连续按出彩蛋按键序列（{@link KonamiCodeListener}），或加载到
 * {@code debug.enabled=true} 的既有配置时调用 {@link #unlock()}，复选框随即显示。
 * <p>
 * 解锁仅影响「是否显示复选框」这一 UI 行为；实际的调试开关仍是写入 config.yaml 的
 * {@code debug.enabled}（由后端 {@link top.sywyar.pixivdownload.config.DebugConfig} 读取）。
 */
public final class DebugUnlockState {

    private static volatile boolean unlocked = false;
    private static final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    private DebugUnlockState() {
    }

    public static boolean isUnlocked() {
        return unlocked;
    }

    /** 解锁调试复选框；首次解锁会通知所有监听者（如已构建的配置面板刷新可见性）。 */
    public static void unlock() {
        if (unlocked) {
            return;
        }
        unlocked = true;
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (RuntimeException ignored) {
                // 监听者自身异常不应影响其它监听者或解锁流程
            }
        }
    }

    public static void addListener(Runnable listener) {
        listeners.add(listener);
    }

    public static void removeListener(Runnable listener) {
        listeners.remove(listener);
    }
}

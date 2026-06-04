package top.sywyar.pixivdownload.gui;

import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.event.KeyEvent;

/**
 * 全局彩蛋按键监听：在<b>任意 GUI 界面</b>连续按出「上上下下左右左右 B A B A」即触发回调。
 * <p>
 * 通过向 {@link KeyboardFocusManager} 注册 {@link KeyEventDispatcher} 实现，与当前焦点控件无关，
 * 因此在任何标签页 / 弹窗中输入都能识别。监听器从不消费事件（始终返回 {@code false}），不影响正常输入。
 */
public final class KonamiCodeListener implements KeyEventDispatcher {

    /** 序列：↑ ↑ ↓ ↓ ← → ← → B A B A。 */
    private static final int[] SEQUENCE = {
            KeyEvent.VK_UP, KeyEvent.VK_UP,
            KeyEvent.VK_DOWN, KeyEvent.VK_DOWN,
            KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT,
            KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT,
            KeyEvent.VK_B, KeyEvent.VK_A,
            KeyEvent.VK_B, KeyEvent.VK_A
    };

    private static boolean installed = false;

    private final Runnable onComplete;
    private int index = 0;

    private KonamiCodeListener(Runnable onComplete) {
        this.onComplete = onComplete;
    }

    /** 向全局键盘焦点管理器注册监听（进程内仅注册一次）。必须在 EDT 上调用。 */
    public static synchronized void install(Runnable onComplete) {
        if (installed) {
            return;
        }
        KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .addKeyEventDispatcher(new KonamiCodeListener(onComplete));
        installed = true;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getID() != KeyEvent.KEY_PRESSED) {
            return false;
        }
        int code = e.getKeyCode();
        if (code == SEQUENCE[index]) {
            index++;
            if (index == SEQUENCE.length) {
                index = 0;
                onComplete.run();
            }
        } else {
            // 按错则重置；若按错的键恰好是序列首键，则从第二位继续匹配
            index = (code == SEQUENCE[0]) ? 1 : 0;
        }
        return false;
    }
}

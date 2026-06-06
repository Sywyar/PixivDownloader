package top.sywyar.pixivdownload.gui.theme;

import com.sun.jna.platform.win32.Advapi32;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinError;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinReg;
import lombok.extern.slf4j.Slf4j;
import top.sywyar.pixivdownload.i18n.MessageBundles;

import java.util.Locale;

/**
 * Windows 注册表主题变化监听。
 * <p>Windows 个性化设置的浅 / 深色应用模式写在当前用户注册表：
 * {@code HKCU\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize}。
 * AWT 桌面属性不保证为该键发出变更事件，因此这里使用 Win32 的
 * {@code RegNotifyChangeKeyValue} 等待该键被写入，再触发一次主题重探测。
 */
@Slf4j
final class WindowsThemeRegistryMonitor {

    private static final String PERSONALIZE_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize";

    private final Runnable onPotentialThemeChange;
    private volatile boolean running;
    private volatile WinNT.HANDLE changeEvent;
    private Thread worker;

    WindowsThemeRegistryMonitor(Runnable onPotentialThemeChange) {
        this.onPotentialThemeChange = onPotentialThemeChange;
    }

    synchronized void start() {
        if (!isWindows() || running) {
            return;
        }
        running = true;
        worker = new Thread(this::runLoop, "gui-theme-registry-watcher");
        worker.setDaemon(true);
        worker.start();
    }

    synchronized void stop() {
        running = false;
        WinNT.HANDLE event = changeEvent;
        if (event != null) {
            Kernel32.INSTANCE.SetEvent(event);
        }
    }

    private void runLoop() {
        WinReg.HKEYByReference keyRef = new WinReg.HKEYByReference();
        WinReg.HKEY key = null;
        WinNT.HANDLE event = null;
        try {
            int rc = Advapi32.INSTANCE.RegOpenKeyEx(
                    WinReg.HKEY_CURRENT_USER,
                    PERSONALIZE_KEY,
                    0,
                    WinNT.KEY_NOTIFY,
                    keyRef);
            if (rc != WinError.ERROR_SUCCESS) {
                log.debug(logMessage("gui.theme.log.registry-listener.open-failed", rc));
                return;
            }
            key = keyRef.getValue();
            event = Kernel32.INSTANCE.CreateEvent(null, false, false, null);
            if (event == null) {
                log.debug(logMessage("gui.theme.log.registry-listener.event-failed"));
                return;
            }
            changeEvent = event;
            log.debug(logMessage("gui.theme.log.registry-listener.started"));

            while (running) {
                Kernel32.INSTANCE.ResetEvent(event);
                rc = Advapi32.INSTANCE.RegNotifyChangeKeyValue(
                        key,
                        false,
                        WinNT.REG_NOTIFY_CHANGE_LAST_SET,
                        event,
                        true);
                if (rc != WinError.ERROR_SUCCESS) {
                    log.debug(logMessage("gui.theme.log.registry-listener.notify-failed", rc));
                    return;
                }

                int wait = Kernel32.INSTANCE.WaitForSingleObject(event, WinBase.INFINITE);
                if (!running) {
                    return;
                }
                if (wait == WinBase.WAIT_OBJECT_0) {
                    onPotentialThemeChange.run();
                } else {
                    log.debug(logMessage("gui.theme.log.registry-listener.wait-failed", wait));
                    return;
                }
            }
        } catch (RuntimeException | UnsatisfiedLinkError e) {
            log.debug(logMessage("gui.theme.log.registry-listener.failed", e.getMessage()));
        } finally {
            changeEvent = null;
            if (event != null) {
                Kernel32.INSTANCE.CloseHandle(event);
            }
            if (key != null) {
                Advapi32.INSTANCE.RegCloseKey(key);
            }
            running = false;
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String logMessage(String code, Object... args) {
        return MessageBundles.get(code, args);
    }
}

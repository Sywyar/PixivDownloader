package top.sywyar.pixivdownload.guitheme;

import com.sun.jna.platform.win32.Advapi32;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinError;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinReg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

final class WindowsThemeRegistryMonitor {

    private static final Logger log = LoggerFactory.getLogger(WindowsThemeRegistryMonitor.class);
    private static final String PERSONALIZE_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize";

    private final Runnable onPotentialThemeChange;
    private WinNT.HANDLE changeEvent;
    private volatile Thread worker;

    WindowsThemeRegistryMonitor(Runnable onPotentialThemeChange) {
        this.onPotentialThemeChange = onPotentialThemeChange;
    }

    synchronized void start() {
        if (!isWindows() || worker != null) {
            return;
        }
        worker = new Thread(this::runLoop, "gui-swing-registry-watcher");
        worker.setDaemon(true);
        try {
            worker.start();
        } catch (RuntimeException | Error failure) {
            worker = null;
            throw failure;
        }
    }

    synchronized void stop() {
        worker = null;
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
                log.debug("Failed to open Windows theme registry key, error code: {}", rc);
                return;
            }
            key = keyRef.getValue();
            event = Kernel32.INSTANCE.CreateEvent(null, false, false, null);
            if (event == null) {
                log.debug("Failed to create Windows theme registry listener event");
                return;
            }
            synchronized (this) {
                if (worker != Thread.currentThread()) return;
                changeEvent = event;
            }
            log.debug("Listening for Windows theme registry changes");

            while (worker == Thread.currentThread()) {
                synchronized (this) {
                    if (worker != Thread.currentThread()) return;
                    // 注册通知会重置事件，必须与 stop 的唤醒保持互斥。
                    rc = Advapi32.INSTANCE.RegNotifyChangeKeyValue(
                            key,
                            false,
                            WinNT.REG_NOTIFY_CHANGE_LAST_SET,
                            event,
                            true);
                }
                if (rc != WinError.ERROR_SUCCESS) {
                    log.debug("Failed to register Windows theme change notification, error code: {}", rc);
                    return;
                }

                int wait = Kernel32.INSTANCE.WaitForSingleObject(event, WinBase.INFINITE);
                if (worker != Thread.currentThread()) {
                    return;
                }
                if (wait == WinBase.WAIT_OBJECT_0) {
                    onPotentialThemeChange.run();
                } else {
                    log.debug("Failed while waiting for Windows theme registry changes, result code: {}", wait);
                    return;
                }
            }
        } catch (RuntimeException | UnsatisfiedLinkError e) {
            log.debug("Windows theme registry listener failed: {}", e.toString());
        } finally {
            synchronized (this) {
                if (worker == Thread.currentThread()) worker = null;
                if (changeEvent == event) changeEvent = null;
                if (key != null) {
                    Advapi32.INSTANCE.RegCloseKey(key);
                }
                if (event != null) {
                    Kernel32.INSTANCE.CloseHandle(event);
                }
            }
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}

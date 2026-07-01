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
                log.debug("Failed to open Windows theme registry key, error code: {}", rc);
                return;
            }
            key = keyRef.getValue();
            event = Kernel32.INSTANCE.CreateEvent(null, false, false, null);
            if (event == null) {
                log.debug("Failed to create Windows theme registry listener event");
                return;
            }
            changeEvent = event;
            log.debug("Listening for Windows theme registry changes");

            while (running) {
                Kernel32.INSTANCE.ResetEvent(event);
                rc = Advapi32.INSTANCE.RegNotifyChangeKeyValue(
                        key,
                        false,
                        WinNT.REG_NOTIFY_CHANGE_LAST_SET,
                        event,
                        true);
                if (rc != WinError.ERROR_SUCCESS) {
                    log.debug("Failed to register Windows theme change notification, error code: {}", rc);
                    return;
                }

                int wait = Kernel32.INSTANCE.WaitForSingleObject(event, WinBase.INFINITE);
                if (!running) {
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
}

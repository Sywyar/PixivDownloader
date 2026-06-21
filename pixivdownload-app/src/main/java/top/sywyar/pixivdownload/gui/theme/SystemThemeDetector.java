package top.sywyar.pixivdownload.gui.theme;

import lombok.extern.slf4j.Slf4j;
import top.sywyar.pixivdownload.i18n.MessageBundles;

import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;

import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 操作系统深色模式探测：
 * <ul>
 *   <li>Windows：优先通过 Win32 注册表读取
 *       {@code HKCU\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize\AppsUseLightTheme}
 *       —— 值为 0 视为深色；不可用时回退到 JDK 桌面属性与 {@code reg query}。</li>
 *   <li>macOS：优先读取 JDK 桌面属性 {@code apple.awt.application.appearance}；不可用时
 *       {@code defaults read -g AppleInterfaceStyle} 退出码 0 且输出含 {@code Dark} 视为深色。</li>
 *   <li>Linux/其它：尝试读取 GTK 桌面属性；不可用时返回 {@code false}（无统一标准，回退浅色）。</li>
 * </ul>
 * 失败一律回退 false，绝不抛异常。供 {@link ThemePreference#SYSTEM} 模式查询当前应使用的 LAF。
 */
@Slf4j
public final class SystemThemeDetector {

    private SystemThemeDetector() {}

    public static boolean isSystemDark() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("win")) {
                return readWindowsDark();
            }
            if (os.contains("mac")) {
                return readMacDark();
            }
            Boolean gtkDark = readGtkDesktopDark();
            if (gtkDark != null) {
                return gtkDark;
            }
        } catch (Exception e) {
            log.debug(logMessage("gui.theme.log.system-detect.failed", e.getMessage()));
        }
        return false;
    }

    private static boolean readWindowsDark() {
        Boolean registryDark = readWindowsRegistryDark();
        if (registryDark != null) {
            return registryDark;
        }
        Boolean desktopDark = readDesktopDarkProperty("win.dark.theme");
        if (desktopDark != null) {
            return desktopDark;
        }

        try {
            Process p = new ProcessBuilder(
                    "reg", "query",
                    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                    "/v", "AppsUseLightTheme")
                    .redirectErrorStream(true)
                    .start();
            String out;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append('\n');
                }
                out = sb.toString();
            }
            p.waitFor();
            // 期望行类似："    AppsUseLightTheme    REG_DWORD    0x0"；0 = 深色，1 = 浅色。
            int idx = out.indexOf("0x");
            if (idx < 0) {
                return false;
            }
            int end = idx + 2;
            while (end < out.length() && isHexDigit(out.charAt(end))) {
                end++;
            }
            if (end == idx + 2) {
                return false;
            }
            int value = Integer.parseInt(out.substring(idx + 2, end), 16);
            return value == 0;
        } catch (Exception e) {
            log.debug(logMessage("gui.theme.log.system-detect.failed", e.getMessage()));
            return false;
        }
    }

    private static Boolean readWindowsRegistryDark() {
        try {
            String keyPath = "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize";
            if (!Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, keyPath, "AppsUseLightTheme")) {
                return null;
            }
            int value = Advapi32Util.registryGetIntValue(WinReg.HKEY_CURRENT_USER, keyPath, "AppsUseLightTheme");
            return value == 0;
        } catch (RuntimeException | UnsatisfiedLinkError e) {
            log.debug(logMessage("gui.theme.log.registry-detect.failed", e.getMessage()));
            return null;
        }
    }

    private static boolean isHexDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static boolean readMacDark() {
        Boolean desktopDark = readAppearanceDarkProperty("apple.awt.application.appearance");
        if (desktopDark != null) {
            return desktopDark;
        }

        try {
            Process p = new ProcessBuilder("defaults", "read", "-g", "AppleInterfaceStyle")
                    .redirectErrorStream(true)
                    .start();
            String out;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line);
                }
                out = sb.toString().trim();
            }
            int exit = p.waitFor();
            // Light 模式下该键不存在，进程返回非 0；Dark 模式下输出 "Dark"。
            return exit == 0 && out.toLowerCase(Locale.ROOT).contains("dark");
        } catch (Exception e) {
            log.debug(logMessage("gui.theme.log.system-detect.failed", e.getMessage()));
            return false;
        }
    }

    private static Boolean readGtkDesktopDark() {
        Boolean preferDark = readDesktopDarkProperty("gnome.Gtk/Settings/gtk-application-prefer-dark-theme");
        if (preferDark != null) {
            return preferDark;
        }
        return readAppearanceDarkProperty("gnome.Gtk/Settings/gtk-theme-name");
    }

    private static Boolean readDesktopDarkProperty(String propertyName) {
        return parseDesktopDarkProperty(readDesktopProperty(propertyName));
    }

    private static Boolean readAppearanceDarkProperty(String propertyName) {
        return parseAppearanceDarkProperty(readDesktopProperty(propertyName));
    }

    private static Object readDesktopProperty(String propertyName) {
        try {
            if (GraphicsEnvironment.isHeadless()) {
                return null;
            }
            return Toolkit.getDefaultToolkit().getDesktopProperty(propertyName);
        } catch (RuntimeException e) {
            log.debug(logMessage("gui.theme.log.desktop-property.read-failed", propertyName, e.getMessage()));
            return null;
        }
    }

    private static Boolean parseDesktopDarkProperty(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.intValue() != 0;
        }
        String text = value.toString().trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            return null;
        }
        return switch (text) {
            case "true", "yes", "on", "1", "dark" -> true;
            case "false", "no", "off", "0", "light" -> false;
            default -> null;
        };
    }

    private static Boolean parseAppearanceDarkProperty(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            return null;
        }
        if (text.contains("dark")) {
            return true;
        }
        if (text.contains("light") || text.contains("aqua")) {
            return false;
        }
        return null;
    }

    private static String logMessage(String code, Object... args) {
        return MessageBundles.get(code, args);
    }
}

package top.sywyar.pixivdownload.guitheme;

import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class SystemThemeDetector {

    private static final Logger log = LoggerFactory.getLogger(SystemThemeDetector.class);

    private SystemThemeDetector() {
    }

    static boolean isSystemDark() {
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
        } catch (RuntimeException e) {
            log.debug("Failed to detect OS theme; falling back to light: {}", e.toString());
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
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append('\n');
                }
                out = sb.toString();
            }
            p.waitFor();
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
            log.debug("Failed to detect Windows OS theme; falling back to light: {}", e.toString());
            return false;
        }
    }

    private static Boolean readWindowsRegistryDark() {
        try {
            String keyPath = "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize";
            if (!Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, keyPath, "AppsUseLightTheme")) {
                return null;
            }
            int value = Advapi32Util.registryGetIntValue(
                    WinReg.HKEY_CURRENT_USER, keyPath, "AppsUseLightTheme");
            return value == 0;
        } catch (RuntimeException | UnsatisfiedLinkError e) {
            log.debug("Failed to read Windows theme registry: {}", e.toString());
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
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line);
                }
                out = sb.toString().trim();
            }
            int exit = p.waitFor();
            return exit == 0 && out.toLowerCase(Locale.ROOT).contains("dark");
        } catch (Exception e) {
            log.debug("Failed to detect macOS theme; falling back to light: {}", e.toString());
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
            log.debug("Failed to read desktop property {}: {}", propertyName, e.toString());
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
}

package top.sywyar.pixivdownload.gui.bootstrap;

import java.awt.Toolkit;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Pattern;

/** 引导窗口的系统外观快照；查询不加载桌面插件，也不初始化日志。 */
record StartupAppearance(boolean dark, boolean highContrast, boolean reducedMotion, double textScale) {
    static final Duration QUERY_BUDGET = Duration.ofMillis(750);
    static final int MAX_OUTPUT_BYTES = 16 * 1024;

    static StartupAppearance detect() {
        long deadline = System.nanoTime() + QUERY_BUDGET.toNanos();
        Toolkit toolkit = Toolkit.getDefaultToolkit();
        return detect(System.getProperty("os.name", ""),
                command -> query(command, deadline), toolkit::getDesktopProperty);
    }

    static StartupAppearance detect(String os, Function<List<String>, String> query,
                                    Function<String, Object> desktop) {
        os = os.toLowerCase(Locale.ROOT);
        boolean dark = false;
        boolean contrast = Boolean.TRUE.equals(desktop.apply("win.highContrast.on"));
        boolean reduced = false;
        double scale = 1;
        if (os.startsWith("windows")) {
            String executable = Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"),
                    "System32", "reg.exe").toString();
            String theme = registry(query, executable,
                    "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize", "AppsUseLightTheme");
            dark = registryNumber(theme, "AppsUseLightTheme", 1) == 0;
            scale = registryNumber(registry(query, executable, "Software\\Microsoft\\Accessibility", "TextScaleFactor"),
                    "TextScaleFactor", 100) / 100.0;
            String motion = registry(query, executable, "Control Panel\\Desktop", "UserPreferencesMask");
            var mask = Pattern.compile("(?i)UserPreferencesMask\\s+REG_BINARY\\s+([0-9a-f]+)").matcher(motion);
            if (mask.find() && mask.group(1).length() >= 10 && mask.group(1).length() % 2 == 0) {
                byte[] bytes = HexFormat.of().parseHex(mask.group(1));
                // UserPreferencesMask 的 UIEFFECTS 与 CLIENTAREAANIMATION 位均需启用。
                reduced = (bytes[3] & 0x80) == 0 || (bytes[4] & 0x02) == 0;
            }
        } else if (os.startsWith("mac")) {
            dark = query.apply(List.of("/usr/bin/defaults", "read", "-g", "AppleInterfaceStyle"))
                    .trim().equalsIgnoreCase("Dark");
            String accessibility = query.apply(List.of("/usr/bin/defaults", "read", "com.apple.universalaccess"));
            contrast = assignmentEnabled(accessibility, "increaseContrast");
            reduced = assignmentEnabled(accessibility, "reduceMotion");
        } else {
            String portal = query.apply(List.of("gdbus", "call", "--session", "--dest", "org.freedesktop.portal.Desktop",
                    "--object-path", "/org/freedesktop/portal/desktop", "--method", "org.freedesktop.portal.Settings.ReadAll",
                    "['org.freedesktop.appearance', 'org.gnome.desktop.interface']"));
            Integer scheme = portalNumber(portal, "color-scheme");
            if (scheme != null && (scheme == 1 || scheme == 2)) {
                dark = scheme == 1;
            } else {
                String preference = query.apply(List.of("gsettings", "get", "org.gnome.desktop.interface", "color-scheme"));
                Object gtk = desktop.apply("gnome.Gtk/Settings/gtk-theme-name");
                dark = preference.contains("prefer-dark") || !preference.contains("prefer-light")
                        && (Boolean.TRUE.equals(desktop.apply("gnome.Gtk/Settings/gtk-application-prefer-dark-theme"))
                        || gtk != null && gtk.toString().toLowerCase(Locale.ROOT).contains("dark"));
            }
            contrast = Integer.valueOf(1).equals(portalNumber(portal, "contrast"))
                    || String.valueOf(desktop.apply("gnome.Gtk/Settings/gtk-theme-name")).toLowerCase(Locale.ROOT).contains("highcontrast");
            Integer motion = portalNumber(portal, "reduced-motion");
            reduced = motion != null ? motion == 1 : query.apply(List.of("gsettings", "get",
                    "org.gnome.desktop.interface", "enable-animations")).trim().equals("false");
            var textScale = Pattern.compile("['\"]text-scaling-factor['\"]\\s*:\\s*<+(?:double\\s+)?([0-9.]+)").matcher(portal);
            String factor = textScale.find() ? textScale.group(1) : query.apply(List.of("gsettings", "get",
                    "org.gnome.desktop.interface", "text-scaling-factor")).trim();
            try { scale = Double.parseDouble(factor); } catch (NumberFormatException ignored) { }
        }
        return new StartupAppearance(dark, contrast, reduced, validTextScale(scale));
    }

    StartupAppearance withTheme(String preference) {
        boolean selected = switch (preference) {
            case "dark" -> true;
            case "light" -> false;
            default -> dark;
        };
        return new StartupAppearance(selected, highContrast, reducedMotion, textScale);
    }

    static double validTextScale(double value) {
        return Double.isFinite(value) && value >= 0.5 && value <= 4 ? value : 1;
    }

    private static String registry(Function<List<String>, String> query, String executable, String key, String value) {
        return query.apply(List.of(executable, "query", "HKCU\\" + key, "/v", value));
    }

    private static long registryNumber(String output, String key, long fallback) {
        var matcher = Pattern.compile("(?i)" + Pattern.quote(key) + "\\s+REG_DWORD\\s+0x([0-9a-f]{1,8})").matcher(output);
        return matcher.find() ? Long.parseLong(matcher.group(1), 16) : fallback;
    }

    private static boolean assignmentEnabled(String output, String key) {
        return Pattern.compile("(?m)^\\s*" + Pattern.quote(key) + "\\s*=\\s*(1|true|YES)\\s*;?\\s*$").matcher(output).find();
    }

    private static Integer portalNumber(String output, String key) {
        var matcher = Pattern.compile("['\"]" + Pattern.quote(key) + "['\"]\\s*:\\s*<+(?:uint32\\s+)?([0-9]{1,4})").matcher(output);
        return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
    }

    /** 所有命令共享截止时间，边读边限制输出，失败仅丢弃本次系统偏好。 */
    static String query(List<String> command, long deadline) {
        if (System.nanoTime() >= deadline || Thread.currentThread().isInterrupted()) return "";
        Process process = null;
        try {
            var builder = new ProcessBuilder(command).redirectErrorStream(true);
            builder.environment().put("LC_ALL", "C");
            process = builder.start();
            process.getOutputStream().close();
            var input = process.getInputStream();
            var output = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            while (System.nanoTime() < deadline) {
                int available = input.available();
                if (available > 0) {
                    int count = input.read(buffer, 0, Math.min(buffer.length, available));
                    if (count < 0) break;
                    if (output.size() + count > MAX_OUTPUT_BYTES) return "";
                    output.write(buffer, 0, count);
                } else if (!process.isAlive()) {
                    return process.exitValue() == 0 ? output.toString(StandardCharsets.UTF_8) : "";
                } else {
                    process.waitFor(Math.min(5, Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()))),
                            TimeUnit.MILLISECONDS);
                }
            }
        } catch (IOException | RuntimeException unavailable) {
            return "";
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            if (process != null) {
                if (process.isAlive()) process.destroyForcibly();
                try { process.getInputStream().close(); } catch (IOException ignored) { }
                try { process.getErrorStream().close(); } catch (IOException ignored) { }
            }
        }
        return "";
    }
}

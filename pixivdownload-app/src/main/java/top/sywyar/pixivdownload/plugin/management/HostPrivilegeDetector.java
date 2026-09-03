package top.sywyar.pixivdownload.plugin.management;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Detects whether the current host process has elevated operating-system privileges. */
public final class HostPrivilegeDetector {

    private static final Pattern WINDOWS_INTEGRITY_SID = Pattern.compile("S-1-16-(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final int WINDOWS_HIGH_INTEGRITY_RID = 12_288;

    private HostPrivilegeDetector() {
    }

    public static boolean isElevated() {
        return ResultHolder.ELEVATED;
    }

    static boolean isElevated(String osName, String commandOutput) {
        if (osName.toLowerCase(Locale.ROOT).startsWith("windows")) {
            Matcher matcher = WINDOWS_INTEGRITY_SID.matcher(commandOutput);
            while (matcher.find()) {
                if (Integer.parseInt(matcher.group(1)) >= WINDOWS_HIGH_INTEGRITY_RID) {
                    return true;
                }
            }
            return false;
        }
        return "0".equals(commandOutput.trim());
    }

    private static boolean detect() {
        String osName = System.getProperty("os.name", "");
        boolean windows = osName.toLowerCase(Locale.ROOT).startsWith("windows");
        Process process = null;
        try {
            process = new ProcessBuilder(windows
                    ? new String[]{"whoami.exe", "/groups", "/fo", "csv", "/nh"}
                    : new String[]{"id", "-u"})
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            String output = new String(process.getInputStream().readNBytes(64 * 1024), StandardCharsets.UTF_8);
            return process.exitValue() == 0 && isElevated(osName, output);
        } catch (IOException ignored) {
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static final class ResultHolder {
        private static final boolean ELEVATED = detect();
    }
}

package top.sywyar.pixivdownload.maintenance;

import java.util.regex.Pattern;

public final class MaintenanceProperties {
    public static final String DEFAULT_TIME = "10:00";
    private static final Pattern TIME_PATTERN = Pattern.compile("^(?:[01]?\\d|2[0-3]):[0-5]?\\d$");
    private MaintenanceProperties() {}
    public static boolean isValidTime(String value) {
        return value != null && TIME_PATTERN.matcher(value.trim()).matches();
    }
}

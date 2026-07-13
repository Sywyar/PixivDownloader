package top.sywyar.pixivdownload.core.schedule.capability;

import java.util.regex.Pattern;

/** 宿主盖章的计划任务能力归属；功能 owner、物理包与插件代际不可互换。 */
public record ScheduleCapabilityOwner(
        String featurePluginId,
        String packageId,
        long pluginGeneration
) implements Comparable<ScheduleCapabilityOwner> {

    private static final Pattern ID_PATTERN = Pattern.compile("[a-z][a-z0-9]*(-[a-z0-9]+)*");

    public ScheduleCapabilityOwner {
        featurePluginId = requireId(featurePluginId, "feature plugin id");
        packageId = requireId(packageId, "package id");
        if (pluginGeneration < 0) {
            throw new IllegalArgumentException("plugin generation must not be negative");
        }
    }

    @Override
    public int compareTo(ScheduleCapabilityOwner other) {
        int byPackage = packageId.compareTo(other.packageId);
        if (byPackage != 0) {
            return byPackage;
        }
        int byFeature = featurePluginId.compareTo(other.featurePluginId);
        if (byFeature != 0) {
            return byFeature;
        }
        return Long.compare(pluginGeneration, other.pluginGeneration);
    }

    private static String requireId(String value, String label) {
        if (value == null || !ID_PATTERN.matcher(value.trim()).matches()) {
            throw new IllegalArgumentException("invalid schedule capability " + label + ": " + value);
        }
        return value.trim();
    }
}

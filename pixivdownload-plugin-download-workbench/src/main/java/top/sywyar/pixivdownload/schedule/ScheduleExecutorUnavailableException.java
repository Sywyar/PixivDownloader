package top.sywyar.pixivdownload.schedule;

import java.util.Set;

/** 来源已解析，但执行计划要求的作品执行器无法取得完整 generation lease。 */
public final class ScheduleExecutorUnavailableException extends Exception {

    private final String sourceType;
    private final Set<String> requiredWorkTypes;

    public ScheduleExecutorUnavailableException(String sourceType, Set<String> requiredWorkTypes) {
        super("scheduled work executor unavailable for source: " + sourceType
                + " required work types: " + requiredWorkTypes);
        this.sourceType = sourceType;
        this.requiredWorkTypes = Set.copyOf(requiredWorkTypes);
    }

    public String sourceType() {
        return sourceType;
    }

    public Set<String> requiredWorkTypes() {
        return requiredWorkTypes;
    }
}

package top.sywyar.pixivdownload.plugin.api.schedule.work;

import java.util.Map;

/** 单作品同步执行的安全结果；部分失败或关系未写入必须抛执行异常，不能返回已完成。 */
public record ScheduledWorkResult(
        Outcome outcome,
        String resultCode,
        Map<String, String> attributes
) {

    public enum Outcome {
        COMPLETED,
        ALREADY_COMPLETED,
        SKIPPED
    }

    public ScheduledWorkResult {
        if (outcome == null) {
            throw new IllegalArgumentException("work outcome must not be null");
        }
        if (resultCode == null || resultCode.isBlank()) {
            throw new IllegalArgumentException("work result code must not be blank");
        }
        resultCode = resultCode.trim();
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public static ScheduledWorkResult completed() {
        return new ScheduledWorkResult(Outcome.COMPLETED, "work.completed", Map.of());
    }

    public static ScheduledWorkResult alreadyCompleted() {
        return new ScheduledWorkResult(Outcome.ALREADY_COMPLETED, "work.already-completed", Map.of());
    }
}

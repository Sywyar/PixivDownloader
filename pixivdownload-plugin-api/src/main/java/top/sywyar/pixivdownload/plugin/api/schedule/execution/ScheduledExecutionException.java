package top.sywyar.pixivdownload.plugin.api.schedule.execution;

/**
 * 插件计划任务执行失败。跨边界只保留稳定分类、机器码和重试延迟；自由文本与插件私有 {@link Throwable}
 * 不得进入异常，宿主会在调用点把未声明的异常立即归一成安全失败数据。
 */
public class ScheduledExecutionException extends Exception {

    private final ScheduledFailure.Category category;
    private final String code;
    private final long retryAfterMillis;

    public ScheduledExecutionException(ScheduledFailure.Category category, String code) {
        this(category, code, 0L);
    }

    public ScheduledExecutionException(ScheduledFailure.Category category,
                                       String code,
                                       long retryAfterMillis) {
        super(normalizeCode(code));
        if (category == null) {
            throw new IllegalArgumentException("failure category must not be null");
        }
        if (retryAfterMillis < 0) {
            throw new IllegalArgumentException("retry delay must not be negative");
        }
        this.category = category;
        this.code = normalizeCode(code);
        this.retryAfterMillis = retryAfterMillis;
    }

    public ScheduledFailure.Category category() {
        return category;
    }

    public String code() {
        return code;
    }

    public long retryAfterMillis() {
        return retryAfterMillis;
    }

    public ScheduledFailure toFailure() {
        return new ScheduledFailure(category, code, retryAfterMillis);
    }

    public static ScheduledExecutionException cancelled() {
        return new ScheduledExecutionException(
                ScheduledFailure.Category.CANCELLED,
                "schedule.cancelled");
    }

    private static String normalizeCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("failure code must not be blank");
        }
        return code.trim();
    }
}

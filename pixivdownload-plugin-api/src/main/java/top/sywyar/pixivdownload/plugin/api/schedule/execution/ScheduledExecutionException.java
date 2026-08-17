package top.sywyar.pixivdownload.plugin.api.schedule.execution;

/**
 * 插件计划任务执行失败。跨边界只保留稳定分类、机器码和重试延迟；自由文本与插件私有 {@link Throwable}
 * 不得进入异常，原始凭据或可逆派生材料也不得进入机器码、异常消息或 cause。宿主会在调用点把未声明的异常
 * 立即归一成安全失败数据。
 */
public class ScheduledExecutionException extends Exception {

    /**
     * 失败类别。
     */
    private final ScheduledFailure.Category category;
    /**
     * 代码。
     */
    private final String code;
    /**
     * 重试后毫秒数。
     */
    private final long retryAfterMillis;

    /**
     * 创建 {@code ScheduledExecutionException} 实例。
     *
     * @param category 失败类别
     * @param code 代码
     */
    public ScheduledExecutionException(ScheduledFailure.Category category, String code) {
        this(category, code, 0L);
    }

    /**
     * 创建 {@code ScheduledExecutionException} 实例。
     *
     * @param category 失败类别
     * @param code 代码
     * @param retryAfterMillis 重试后毫秒数
     */
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

    /**
     * 返回类别。
     *
     * @return 方法返回的 {@code ScheduledFailure.Category} 实例
     */
    public ScheduledFailure.Category category() {
        return category;
    }

    /**
     * 返回代码。
     *
     * @return 方法返回的字符串
     */
    public String code() {
        return code;
    }

    /**
     * 返回重试后毫秒数。
     *
     * @return 方法返回的数值
     */
    public long retryAfterMillis() {
        return retryAfterMillis;
    }

    /**
     * 返回对应值。
     *
     * @return 方法返回的 {@code ScheduledFailure} 实例
     */
    public ScheduledFailure toFailure() {
        return new ScheduledFailure(category, code, retryAfterMillis);
    }

    /**
     * 返回对应值。
     *
     * @return 满足条件时返回 {@code true}，否则返回 {@code false}
     */
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

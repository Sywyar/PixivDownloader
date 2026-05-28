package top.sywyar.pixivdownload.schedule;

/**
 * 任务级挂起（{@code AUTH_EXPIRED}）信号：cookie 依赖型任务在轮首发现 cookie 已死，
 * 或运行中单作品连续失败触发熔断。让 {@code runTaskAndRecord} 标 {@code AUTH_EXPIRED}、发对应邮件、清 run_started_time。
 *
 * <p>{@link Reason} 仅决定发哪封通知邮件（dead cookie → auth-expired 模板 / 熔断 → circuit-breaker 模板），
 * 持久化状态统一为 {@code AUTH_EXPIRED}（恢复入口都是重新授权 cookie）。
 */
public class ScheduleSuspendException extends Exception {

    public enum Reason {COOKIE_DEAD, CIRCUIT_BREAKER}

    private final Reason reason;
    /** 熔断时的连续失败次数（仅 {@code CIRCUIT_BREAKER} 有意义）。 */
    private final int consecutiveFailures;
    /** 熔断时最近一次失败原因摘要（已脱敏、可空）。 */
    private final String lastErrorExcerpt;

    public ScheduleSuspendException(Reason reason) {
        this(reason, 0, null);
    }

    public ScheduleSuspendException(Reason reason, int consecutiveFailures, String lastErrorExcerpt) {
        super("schedule suspended: " + reason);
        this.reason = reason;
        this.consecutiveFailures = consecutiveFailures;
        this.lastErrorExcerpt = lastErrorExcerpt;
    }

    public Reason reason() {
        return reason;
    }

    public int consecutiveFailures() {
        return consecutiveFailures;
    }

    public String lastErrorExcerpt() {
        return lastErrorExcerpt;
    }
}

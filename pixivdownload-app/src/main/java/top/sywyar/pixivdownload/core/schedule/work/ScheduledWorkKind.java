package top.sywyar.pixivdownload.core.schedule.work;

/**
 * 计划任务「作品类型执行器」（{@link ScheduledWorkRunner}）的路由键常量。
 *
 * <p>取值与运行队列（{@code ScheduleRunQueue.KIND_*}）、任务 params 的 {@code kind} 字段一致，
 * 是 {@link ScheduledWorkRunnerRegistry} 按作品类型解析执行器的索引键。新增作品类型时在此登记常量、
 * 由对应插件贡献一个同 {@code kind} 的执行器。
 */
public final class ScheduledWorkKind {

    /** 插画 / 漫画 / 动图。 */
    public static final String ILLUST = "illust";

    /** 小说。 */
    public static final String NOVEL = "novel";

    private ScheduledWorkKind() {
    }
}

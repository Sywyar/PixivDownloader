package top.sywyar.pixivdownload.notification;

/**
 * 对外通知<b>业务场景</b>的单一事实源。每个常量可同时通过多个通知介质对外发送，
 * 携带两份与介质无关的元数据：
 * <ul>
 *   <li>{@link #id() canonical id} —— 各介质据此定位自己的渲染资源。</li>
 *   <li>{@link #level() 默认通知严重程度} —— 发送介质用于映射自身的配色 / 优先级。</li>
 * </ul>
 *
 * <p><b>成对 / 齐全维护铁律</b>：新增 / 修改 / 删除任何常量时，所有介质 {@link NotificationSink} 的渲染资源
 * 必须同步维护，并由每个介质的契约测试遍历「场景 × 介质」守护，缺一即失败。
 *
 * <p>当前场景描述计划运行结束时的自动挂起、重试耗尽、降级、失败与成功摘要语义；触发实现不属于本契约。
 */
public enum NotificationScenario {

    /** 过度访问警告 → 账号级暂停。 */
    OVERUSE_PAUSED("overuse-paused", NotificationSeverity.WARNING),
    /** cookie 依赖型任务 dead cookie → 任务级挂起。 */
    AUTH_EXPIRED("auth-expired", NotificationSeverity.WARNING),
    /** 单作品连续失败熔断 → 任务级挂起。 */
    CIRCUIT_BREAKER("circuit-breaker", NotificationSeverity.ERROR),
    /** 单作品自动重试达上限 → 需人工处理。 */
    PENDING_EXHAUSTED("pending-exhausted", NotificationSeverity.WARNING),
    /** cookie 失效但任务无需 cookie → 自动清除失效快照、本轮降级匿名续跑并成功（仅清除快照那一轮通知一次）。 */
    DEGRADED_ANONYMOUS("degraded-anonymous", NotificationSeverity.WARNING),
    /** 整轮运行失败（非鉴权类异常）→ 进入 ERROR 状态（仅在状态由非 ERROR 转入 ERROR 时通知一次，连续失败不重复）。 */
    RUN_FAILED("run-failed", NotificationSeverity.ERROR),
    /** 运行成功且本轮有新下载 → 摘要通知。 */
    RUN_SUMMARY("run-summary", NotificationSeverity.INFO);

    private final String id;
    private final NotificationSeverity level;

    NotificationScenario(String id, NotificationSeverity level) {
        this.id = id;
        this.level = level;
    }

    /** 各通知介质共享的 canonical id。 */
    public String id() {
        return id;
    }

    /** 默认通知严重程度；具体发送介质自行映射。 */
    public NotificationSeverity level() {
        return level;
    }
}

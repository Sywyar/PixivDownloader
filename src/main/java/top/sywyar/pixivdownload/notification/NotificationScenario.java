package top.sywyar.pixivdownload.notification;

import top.sywyar.pixivdownload.push.PushLevel;

/**
 * 对外通知<b>业务场景</b>的单一事实源。每个常量是一个会同时通过多个介质（邮件 / 推送）对外发出的通知，
 * 携带两份与介质无关的元数据：
 * <ul>
 *   <li>{@link #id() canonical id} —— 必须与 {@code MailTemplateRegistry} 的模板 id 以及
 *       推送侧 {@code push.message.{id}.title} / {@code .body} 的 i18n key 段完全一致；介质各自据此查渲染资源。</li>
 *   <li>{@link #level() 默认推送级别} —— 推送介质用于映射通道自身的配色 / 优先级。</li>
 * </ul>
 *
 * <p><b>成对 / 齐全维护铁律</b>：新增 / 修改 / 删除任何常量时，所有介质 {@link NotificationSink} 的渲染资源
 * 必须同步维护（邮件模板 + subject i18n；推送 {@code title} / {@code body} i18n），由
 * {@code NotificationSinkCoverageTest} 遍历「场景 × 介质」守护，缺一即 loud-failure。
 *
 * <p>当前 4 个场景均由 {@code schedule/ScheduleExecutor} 在自动挂起 / 自动重试达上限时触发。
 */
public enum NotificationScenario {

    /** 过度访问警告 → 账号级暂停。 */
    OVERUSE_PAUSED("overuse-paused", PushLevel.WARNING),
    /** cookie 依赖型任务 dead cookie → 任务级挂起。 */
    AUTH_EXPIRED("auth-expired", PushLevel.WARNING),
    /** 单作品连续失败熔断 → 任务级挂起。 */
    CIRCUIT_BREAKER("circuit-breaker", PushLevel.ERROR),
    /** 单作品自动重试达上限 → 需人工处理。 */
    PENDING_EXHAUSTED("pending-exhausted", PushLevel.WARNING);

    private final String id;
    private final PushLevel level;

    NotificationScenario(String id, PushLevel level) {
        this.id = id;
        this.level = level;
    }

    /** canonical id：邮件模板 id 与推送 i18n key 段共用。 */
    public String id() {
        return id;
    }

    /** 默认推送级别（邮件介质不消费此字段）。 */
    public PushLevel level() {
        return level;
    }
}

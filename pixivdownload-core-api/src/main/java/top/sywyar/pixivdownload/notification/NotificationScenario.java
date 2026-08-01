package top.sywyar.pixivdownload.notification;

import java.util.Arrays;
import java.util.Optional;

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
 * <p>当前内置场景描述计划运行结束时的中性凭证、策略、重试与结果语义；具体来源只可通过稳定 id
 * 选择宿主已发布的场景，不得把来源私有 reason code 提升为核心枚举名。
 */
public enum NotificationScenario {

    /** 凭证策略要求账号级暂停。外部 id 为已发布兼容值。 */
    POLICY_ACCOUNT_SUSPENDED("overuse-paused", NotificationSeverity.WARNING),
    /** 任务凭证失效并被挂起。外部 id 为已发布兼容值。 */
    CREDENTIAL_SUSPENDED("auth-expired", NotificationSeverity.WARNING),
    /** 连续执行失败触发凭证故障熔断。外部 id 为已发布兼容值。 */
    CREDENTIAL_FAILURE_CIRCUIT_OPEN("circuit-breaker", NotificationSeverity.ERROR),
    /** 单作品自动重试达上限 → 需人工处理。 */
    PENDING_EXHAUSTED("pending-exhausted", NotificationSeverity.WARNING),
    /** 可选凭证失效后已撤销绑定，并以受限能力继续成功。外部 id 为已发布兼容值。 */
    CREDENTIAL_REVOKED_CONTINUING("degraded-anonymous", NotificationSeverity.WARNING),
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

    /** 按已发布 canonical id 解析场景；未知或空 id 不回退到其它场景。 */
    public static Optional<NotificationScenario> findById(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        String normalized = id.trim();
        return Arrays.stream(values())
                .filter(scenario -> scenario.id.equals(normalized))
                .findFirst();
    }
}

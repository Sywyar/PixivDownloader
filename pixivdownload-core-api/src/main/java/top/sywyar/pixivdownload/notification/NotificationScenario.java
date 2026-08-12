package top.sywyar.pixivdownload.notification;

import java.util.Arrays;
import java.util.Optional;

/**
 * 对外通知<b>业务场景</b>的单一事实源。每个常量携带三份中性元数据：
 * <ul>
 *   <li>{@link #id() canonical id} —— 场景插件据此通过稳定契约贡献各介质渲染模板。</li>
 *   <li>{@link #categoryId() 分类 id} —— 具备分类能力的介质据此归类展示。</li>
 *   <li>{@link #level() 默认通知严重程度} —— 发送介质用于映射自身的配色 / 优先级。</li>
 * </ul>
 *
 * <p><b>齐全维护铁律</b>：新增 / 修改 / 删除任何常量时，场景所有者必须同步贡献全部已支持介质与语言的
 * 模板，并由所有者契约测试遍历「场景 × 介质 × 语言」守护，缺一即失败。
 *
 * <p>当前内置场景描述计划运行与宿主系统事件的中性语义；具体来源只可通过稳定 id
 * 选择宿主已发布的场景，不得把来源私有 reason code 提升为核心枚举名。
 */
public enum NotificationScenario {

    /** 凭证策略要求账号级暂停。外部 id 为已发布兼容值。 */
    POLICY_ACCOUNT_SUSPENDED("overuse-paused", "download", NotificationSeverity.WARNING),
    /** 任务凭证失效并被挂起。外部 id 为已发布兼容值。 */
    CREDENTIAL_SUSPENDED("auth-expired", "download", NotificationSeverity.WARNING),
    /** 连续执行失败触发凭证故障熔断。外部 id 为已发布兼容值。 */
    CREDENTIAL_FAILURE_CIRCUIT_OPEN("circuit-breaker", "download", NotificationSeverity.ERROR),
    /** 单作品自动重试达上限 → 需人工处理。 */
    PENDING_EXHAUSTED("pending-exhausted", "download", NotificationSeverity.WARNING),
    /** 可选凭证失效后已撤销绑定，并以受限能力继续成功。外部 id 为已发布兼容值。 */
    CREDENTIAL_REVOKED_CONTINUING("degraded-anonymous", "download", NotificationSeverity.WARNING),
    /** 整轮运行失败（非鉴权类异常）→ 进入 ERROR 状态（仅在状态由非 ERROR 转入 ERROR 时通知一次，连续失败不重复）。 */
    RUN_FAILED("run-failed", "download", NotificationSeverity.ERROR),
    /** 运行成功且本轮有新下载 → 摘要通知。 */
    RUN_SUMMARY("run-summary", "download", NotificationSeverity.INFO),
    /** 维护任务本轮执行失败，协调器不会在本轮自动重试。 */
    MAINTENANCE_TASK_FAILED("maintenance-task-failed", "system", NotificationSeverity.ERROR);

    private final String id;
    private final String categoryId;
    private final NotificationSeverity level;

    NotificationScenario(String id, String categoryId, NotificationSeverity level) {
        this.id = id;
        this.categoryId = categoryId;
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

    /** 展示介质使用的稳定分类 id。 */
    public String categoryId() {
        return categoryId;
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

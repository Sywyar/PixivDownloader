package top.sywyar.pixivdownload.schedule.dto;

import lombok.Data;

/**
 * 账号级（过度访问）恢复请求体。
 *
 * <ul>
 *   <li>{@code mode=ignore}：「无视风险，继续下载」——把同账号所有任务清挂起、{@code next_run=now}，
 *       并把当前这封警告的 modifiedAt 写入 {@code ack_warning_time}（仅本封被忽略，更新的警告仍会再次暂停）。</li>
 *   <li>{@code mode=defer}：「我已知晓，在 N 分钟后继续」——同账号清挂起、{@code next_run=now + N*60000}，
 *       同时写 ack（保险）。{@code minutes} 最低 60。</li>
 * </ul>
 */
@Data
public class AccountResumeRequest {

    public static final String MODE_IGNORE = "ignore";
    public static final String MODE_DEFER = "defer";

    /** {@code ignore} 或 {@code defer}。 */
    private String mode;

    /** defer 模式的延迟分钟数（最低 60）；ignore 模式忽略。为空时回退 {@code schedule.overuse-defer-default-minutes}。 */
    private Integer minutes;
}

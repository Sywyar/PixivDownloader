package top.sywyar.pixivdownload.plugin.runtime.status;

import java.util.List;
import java.util.Optional;

/**
 * 恢复模式判定结果（不可变）：存在任一未满足的必选插件时 {@link #active} 为 {@code true}，核心壳应只开放诊断 /
 * 修复入口、不开放正常业务路由；否则核心壳正常运行、路由行为不变。
 *
 * <p>本结果只描述「是否应进入恢复模式及其原因」，不自行改变任何运行行为——是否据此拦截请求由访问控制消费方决定。
 *
 * @param active  是否应进入恢复模式（存在未满足的必选插件）
 * @param reasons 各未满足必选插件的原因（{@link #active} 为 {@code false} 时为空）
 */
public record RecoveryModeDecision(boolean active, List<RecoveryModeReason> reasons) {

    private static final RecoveryModeDecision OPERATIONAL = new RecoveryModeDecision(false, List.of());

    public RecoveryModeDecision {
        reasons = reasons != null ? List.copyOf(reasons) : List.of();
    }

    /** 正常运行（无未满足必选插件、不进入恢复模式）。 */
    public static RecoveryModeDecision operational() {
        return OPERATIONAL;
    }

    /** 首个未满足原因（用于向用户呈现主提示），无则为空。 */
    public Optional<RecoveryModeReason> firstReason() {
        return reasons.isEmpty() ? Optional.empty() : Optional.of(reasons.get(0));
    }
}

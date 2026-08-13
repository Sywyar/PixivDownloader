package top.sywyar.pixivdownload.plugin.runtime.status;

import top.sywyar.pixivdownload.plugin.runtime.status.RequiredPluginPolicy.RequiredPlugin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 恢复模式评估器（无状态、纯函数）：给定一份 {@link PluginStatusReport} 与 {@link RequiredPluginPolicy}，判定核心壳
 * 是否应进入恢复模式。规则：策略声明的每个必选 pluginId，其在报告中的状态都必须是 {@link PluginStatus#STARTED}；
 * 任一明确发生启动失败的插件也进入恢复模式，以便核心壳继续开放修复入口。
 * 只要有任一必选插件缺失 / 禁用 / 启动失败 / 版本不兼容（即非 {@code STARTED}），即判定进入恢复模式并给出原因。
 * 报告中没有对应诊断的必选 pluginId 视为缺失（{@link PluginStatus#MISSING_REQUIRED}）。
 *
 * <p>空策略只跳过必选项检查；启动失败由调用方通过 pluginId 集合明确传入。纯 JDK，不读运行时、不改变任何行为——是否据判定拦截请求由
 * 访问控制消费方决定。
 */
public final class RecoveryModeEvaluator {

    /**
     * 据插件状态报告与必选策略判定恢复模式。{@code report} / {@code policy} 为空或策略未声明任何必选项时返回
     * {@link RecoveryModeDecision#operational()}。
     */
    public RecoveryModeDecision evaluate(PluginStatusReport report, RequiredPluginPolicy policy) {
        return evaluate(report, policy, Set.of());
    }

    /** 据插件状态报告、必选策略与明确的启动失败 pluginId 判定恢复模式。 */
    public RecoveryModeDecision evaluate(PluginStatusReport report, RequiredPluginPolicy policy,
                                         Set<String> startupFailureIds) {
        if (report == null) {
            return RecoveryModeDecision.operational();
        }
        RequiredPluginPolicy effectivePolicy = policy != null ? policy : RequiredPluginPolicy.empty();
        List<RecoveryModeReason> reasons = new ArrayList<>();
        Set<String> reasonIds = new HashSet<>();
        for (RequiredPlugin required : effectivePolicy.required()) {
            PluginDiagnostic diagnostic = report.byId(required.pluginId()).orElse(null);
            PluginStatus status = diagnostic != null ? diagnostic.status() : PluginStatus.MISSING_REQUIRED;
            if (status != PluginStatus.STARTED) {
                List<String> messages = diagnostic != null ? diagnostic.messages() : List.of();
                reasons.add(new RecoveryModeReason(required.pluginId(), status,
                        required.missingMessageKey(), required.compatibleVersion(), messages));
                reasonIds.add(required.pluginId());
            }
        }
        Set<String> effectiveStartupFailures = startupFailureIds != null ? startupFailureIds : Set.of();
        for (PluginDiagnostic diagnostic : report.diagnostics()) {
            if (diagnostic.status() == PluginStatus.FAILED
                    && effectiveStartupFailures.contains(diagnostic.id())
                    && reasonIds.add(diagnostic.id())) {
                reasons.add(new RecoveryModeReason(diagnostic.id(), PluginStatus.FAILED,
                        "plugin.recovery.failed", null, diagnostic.messages()));
            }
        }
        return reasons.isEmpty()
                ? RecoveryModeDecision.operational()
                : new RecoveryModeDecision(true, reasons);
    }
}

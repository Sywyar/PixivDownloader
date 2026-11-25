package top.sywyar.pixivdownload.plugin.runtime.status;

import top.sywyar.pixivdownload.plugin.runtime.status.RequiredPluginPolicy.RequiredPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 根据状态报告、必选策略和实际服务准入评估恢复模式。必选插件必须为 {@link PluginStatus#STARTED}，
 * 且其外置服务须能接收请求。普通插件失败只保留诊断。
 * 报告中没有对应诊断的必选 pluginId 视为缺失（{@link PluginStatus#MISSING_REQUIRED}）。
 *
 * <p>纯 JDK，不读运行时；访问控制消费方负责应用判定结果。
 */
public final class RecoveryModeEvaluator {

    /**
     * 据插件状态报告与必选策略判定恢复模式。{@code report} / {@code policy} 为空或策略未声明任何必选项时返回
     * {@link RecoveryModeDecision#operational()}。
     */
    public RecoveryModeDecision evaluate(PluginStatusReport report, RequiredPluginPolicy policy) {
        return evaluate(report, policy, Set.of());
    }

    public RecoveryModeDecision evaluate(PluginStatusReport report, RequiredPluginPolicy policy, Set<String> nonServing) {
        return evaluate(report, policy, nonServing.stream().collect(java.util.stream.Collectors.toMap(
                id -> id, id -> java.util.Map.<String, String>of())));
    }

    public RecoveryModeDecision evaluate(PluginStatusReport report, RequiredPluginPolicy policy,
                                         java.util.Map<String, java.util.Map<String, String>> blockers) {
        if (report == null) {
            return RecoveryModeDecision.operational();
        }
        RequiredPluginPolicy effectivePolicy = policy != null ? policy : RequiredPluginPolicy.empty();
        List<RecoveryModeReason> reasons = new ArrayList<>();
        for (RequiredPlugin required : effectivePolicy.required()) {
            PluginDiagnostic diagnostic = report.byId(required.pluginId()).orElse(null);
            PluginStatus status = diagnostic != null ? diagnostic.status() : PluginStatus.MISSING_REQUIRED;
            if (status != PluginStatus.STARTED || blockers.containsKey(required.pluginId())) {
                boolean awaitingStart = switch (status) {
                    case STARTED, LOADED, RESOLVED, STOPPED, INSTALLED -> true;
                    case MISSING_REQUIRED -> diagnostic != null && diagnostic.descriptor() != null
                            && blockers.containsKey(required.pluginId());
                    default -> false;
                };
                String messageKey = awaitingStart ? "plugin.recovery.not-serving" : required.missingMessageKey();
                String missingId = diagnostic != null ? diagnostic.missingDependencyId() : null;
                if (status == PluginStatus.MISSING_REQUIRED && missingId != null)
                    messageKey = "plugin.recovery.missing-dependency";
                reasons.add(new RecoveryModeReason(required.pluginId(), status, messageKey, required.compatibleVersion(),
                        diagnostic != null ? diagnostic.messages() : List.of(), missingId,
                        blockers.getOrDefault(required.pluginId(), java.util.Map.of())));
            }
        }
        return reasons.isEmpty()
                ? RecoveryModeDecision.operational()
                : new RecoveryModeDecision(true, reasons);
    }
}

package top.sywyar.pixivdownload.plugin.runtime.status;

import top.sywyar.pixivdownload.plugin.runtime.status.RequiredPluginPolicy.RequiredPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * 恢复模式评估器（无状态、纯函数）：给定一份 {@link PluginStatusReport} 与 {@link RequiredPluginPolicy}，判定核心壳
 * 是否应进入恢复模式。规则：策略声明的每个必选 pluginId，其在报告中的状态都必须是 {@link PluginStatus#STARTED}；
 * 只要有任一必选插件缺失 / 禁用 / 启动失败 / 版本不兼容（即非 {@code STARTED}），即判定进入恢复模式并给出原因。
 * 报告中没有对应诊断的必选 pluginId 视为缺失（{@link PluginStatus#MISSING_REQUIRED}）。
 *
 * <p>空策略（未声明任何必选插件）恒判定为正常运行。纯 JDK，不读运行时、不改变任何行为——是否据判定拦截请求由
 * 访问控制消费方决定。
 */
public final class RecoveryModeEvaluator {

    /**
     * 据插件状态报告与必选策略判定恢复模式。{@code report} / {@code policy} 为空或策略未声明任何必选项时返回
     * {@link RecoveryModeDecision#operational()}。
     */
    public RecoveryModeDecision evaluate(PluginStatusReport report, RequiredPluginPolicy policy) {
        if (report == null || policy == null || policy.isEmpty()) {
            return RecoveryModeDecision.operational();
        }
        List<RecoveryModeReason> reasons = new ArrayList<>();
        for (RequiredPlugin required : policy.required()) {
            PluginDiagnostic diagnostic = report.byId(required.pluginId()).orElse(null);
            PluginStatus status = diagnostic != null ? diagnostic.status() : PluginStatus.MISSING_REQUIRED;
            if (status != PluginStatus.STARTED) {
                List<String> messages = diagnostic != null ? diagnostic.messages() : List.of();
                reasons.add(new RecoveryModeReason(required.pluginId(), status,
                        required.missingMessageKey(), required.compatibleVersion(), messages));
            }
        }
        return reasons.isEmpty()
                ? RecoveryModeDecision.operational()
                : new RecoveryModeDecision(true, reasons);
    }
}

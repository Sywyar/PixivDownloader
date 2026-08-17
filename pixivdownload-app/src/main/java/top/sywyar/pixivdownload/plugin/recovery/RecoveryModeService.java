package top.sywyar.pixivdownload.plugin.recovery;

import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.plugin.runtime.status.RecoveryModeDecision;
import top.sywyar.pixivdownload.plugin.runtime.status.RecoveryModeEvaluator;
import top.sywyar.pixivdownload.plugin.runtime.status.RequiredPluginPolicy;
import top.sywyar.pixivdownload.plugin.runtime.status.RecoveryModeReason;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginStatus;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.VersionRequirement;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PluginRecoveryGateSnapshot;
import top.sywyar.pixivdownload.plugin.management.PluginStatusService;

/**
 * 恢复模式判定服务（后端）：综合 {@link PluginStatusService} 的插件状态报告与必选插件策略
 * {@link RequiredPluginPolicy}，判定核心壳当前是否应进入恢复模式（存在未满足的必选插件或插件启动失败）。
 *
 * <p>必选插件全部 {@link top.sywyar.pixivdownload.plugin.runtime.status.PluginStatus#STARTED} 时判定为正常运行，
 * 此时不改变任何路由行为；只要有必选插件缺失 / 禁用 / 版本不兼容，或插件在启动阶段崩溃，即判定进入恢复模式。判定结果由访问控制
 * 消费方 {@link RecoveryModeGate} 据以放行诊断 / 修复入口、拦截正常业务请求。
 *
 * <p>判定结果在首次查询后缓存；运行期插件状态变化后由生命周期协调器调用 {@link #refresh()} 重新评估。
 */
@Service
public class RecoveryModeService {

    private final PluginStatusService pluginStatusService;
    private final RequiredPluginPolicy requiredPluginPolicy;
    private final RecoveryModeEvaluator evaluator = new RecoveryModeEvaluator();

    private volatile RecoveryModeDecision cached;

    public RecoveryModeService(PluginStatusService pluginStatusService,
                               RequiredPluginPolicy requiredPluginPolicy) {
        this.pluginStatusService = pluginStatusService;
        this.requiredPluginPolicy = requiredPluginPolicy;
    }

    /** 当前恢复模式判定（首次评估后缓存）。 */
    public RecoveryModeDecision decision() {
        PluginRecoveryGateSnapshot recovery = pluginStatusService.recoveryGateSnapshot();
        if (!recovery.safeToScan()) {
            return transactionRecoveryDecision(recovery);
        }
        RecoveryModeDecision current = cached;
        if (current == null) {
            current = evaluator.evaluate(pluginStatusService.report(), requiredPluginPolicy,
                    pluginStatusService.startupFailuresById().keySet());
            cached = current;
        }
        return current;
    }

    private static RecoveryModeDecision transactionRecoveryDecision(PluginRecoveryGateSnapshot recovery) {
        java.util.List<String> messages = recovery.report().failures().stream()
                .map(failure -> failure.kind() + " transaction=" + failure.transactionId()
                        + " path=" + failure.transactionDirectory() + ": " + failure.detail())
                .toList();
        if (messages.isEmpty()) {
            messages = java.util.List.of("plugin transaction recovery has not completed");
        }
        return new RecoveryModeDecision(true, java.util.List.of(new RecoveryModeReason(
                "plugin-runtime", PluginStatus.FAILED, "plugin.recovery.transaction",
                VersionRequirement.unspecified(), messages)));
    }

    /** 核心壳当前是否应进入恢复模式。 */
    public boolean isActive() {
        return decision().active();
    }

    /** 当前触发恢复模式的结构化原因。 */
    public java.util.List<RecoveryModeReason> reasons() {
        return decision().reasons();
    }

    /** 插件运行态变化后使下一次查询重新评估恢复条件。 */
    public void refresh() {
        cached = null;
    }
}

package top.sywyar.pixivdownload.plugin.recovery;

import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.plugin.runtime.status.RecoveryModeDecision;
import top.sywyar.pixivdownload.plugin.runtime.status.RecoveryModeEvaluator;
import top.sywyar.pixivdownload.plugin.runtime.status.RequiredPluginPolicy;
import top.sywyar.pixivdownload.plugin.management.PluginStatusService;

/**
 * 恢复模式判定服务（后端）：综合 {@link PluginStatusService} 的插件状态报告与必选插件策略
 * {@link RequiredPluginPolicy}，判定核心壳当前是否应进入恢复模式（存在未满足的必选插件）。
 *
 * <p>必选插件全部 {@link top.sywyar.pixivdownload.plugin.runtime.status.PluginStatus#STARTED} 时判定为正常运行，
 * 此时不改变任何路由行为；只要有必选插件缺失 / 禁用 / 启动失败 / 版本不兼容即判定进入恢复模式。判定结果由访问控制
 * 消费方 {@link RecoveryModeGate} 据以放行诊断 / 修复入口、拦截正常业务请求。
 *
 * <p>判定结果在首次查询后缓存：内置必选插件随主程序编译、运行期不装卸，外置插件在启动期一次性发现 / 接入，故启动
 * 完成后必选插件的满足情况是固定的，按请求重复评估无意义。
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
        RecoveryModeDecision current = cached;
        if (current == null) {
            current = evaluator.evaluate(pluginStatusService.report(), requiredPluginPolicy);
            cached = current;
        }
        return current;
    }

    /** 核心壳当前是否应进入恢复模式。 */
    public boolean isActive() {
        return decision().active();
    }

    /** 插件运行态变化后使下一次查询重新评估必选策略。 */
    public void refresh() {
        cached = null;
    }
}

package top.sywyar.pixivdownload.plugin;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 外置插件运行期生命周期状态机（可观测、线程安全）：按 pluginId 持有 {@link PluginRuntimePhase}，校验合法流转
 * 并提供只读观测。它是热启停 / quiesce 的<b>唯一状态事实源</b>——{@link PluginLifecycleService} 据各生命周期操作
 * 驱动它，请求网关 {@link PluginQuiesceGate} 读它判定某 URL 所属插件是否已 quiesce、应转为「插件不可用」。
 *
 * <p>读路径无锁（{@link ConcurrentHashMap}）；流转校验在 {@link #transition} 内原子完成（{@code compute} 闭包内
 * 校验 {@link PluginRuntimePhase#canTransitionTo}），非法流转抛 {@link PluginLifecycleException} 并带清晰诊断
 * （当前态 → 目标态）。本类<b>不持有任何插件实现 / classloader 引用</b>，只承载 pluginId → 阶段，避免成为
 * classloader 泄漏点。
 */
@Component
public class PluginLifecycleState {

    private final Map<String, PluginRuntimePhase> phases = new ConcurrentHashMap<>();

    /** 设定初始阶段（首次接入，无前置态校验）。仅用于启动期接入 / 测试夹具建立初始态。 */
    public void initialize(String pluginId, PluginRuntimePhase phase) {
        if (phase == null) {
            throw new PluginLifecycleException("null initial phase for plugin '" + pluginId + "'");
        }
        phases.put(requireId(pluginId), phase);
    }

    /**
     * 把 {@code pluginId} 从当前阶段流转到 {@code target}：当且仅当存在当前阶段且
     * {@link PluginRuntimePhase#canTransitionTo} 允许时成功，否则抛 {@link PluginLifecycleException}。原子执行。
     */
    public void transition(String pluginId, PluginRuntimePhase target) {
        requireId(pluginId);
        if (target == null) {
            throw new PluginLifecycleException("null target phase for plugin '" + pluginId + "'");
        }
        phases.compute(pluginId, (id, current) -> {
            if (current == null) {
                throw new PluginLifecycleException("no lifecycle state for plugin '" + id
                        + "' (was it ever brought up?)");
            }
            if (!current.canTransitionTo(target)) {
                throw new PluginLifecycleException("illegal plugin lifecycle transition for '" + id
                        + "': " + current + " -> " + target);
            }
            return target;
        });
    }

    /**
     * 强制设定阶段（不校验流转）。供 stop 的收尾使用——即便某一拆除步骤异常，也必须把阶段落到
     * {@link PluginRuntimePhase#STOPPED}，保证状态与「服务足迹已清退」一致。
     */
    public void set(String pluginId, PluginRuntimePhase phase) {
        if (phase == null) {
            throw new PluginLifecycleException("null phase for plugin '" + pluginId + "'");
        }
        phases.put(requireId(pluginId), phase);
    }

    public Optional<PluginRuntimePhase> phase(String pluginId) {
        return Optional.ofNullable(phases.get(pluginId));
    }

    /** 该插件当前是否接收新请求（{@link PluginRuntimePhase#STARTED}）。未知插件视为不接收。 */
    public boolean acceptsNewRequests(String pluginId) {
        PluginRuntimePhase phase = phases.get(pluginId);
        return phase != null && phase.acceptsNewRequests();
    }

    /** 该插件当前是否处于 quiesce 态（请求网关据此拒绝命中其路由的新请求）。未知插件返回 {@code false}。 */
    public boolean isQuiesced(String pluginId) {
        PluginRuntimePhase phase = phases.get(pluginId);
        return phase != null && phase.isQuiesced();
    }

    /** 当前处于 quiesce 态的插件 id（只读快照）。请求网关据此快速短路（无 quiesce 插件时完全透明）。 */
    public Set<String> quiescedPluginIds() {
        return phases.entrySet().stream()
                .filter(e -> e.getValue().isQuiesced())
                .map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** 全部插件的阶段（只读快照）。供状态查询 / 测试观测。 */
    public Map<String, PluginRuntimePhase> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(phases));
    }

    /** 移除某插件的阶段记录（彻底从观测态中清理）。 */
    public void remove(String pluginId) {
        phases.remove(pluginId);
    }

    private static String requireId(String pluginId) {
        if (pluginId == null || pluginId.isBlank()) {
            throw new PluginLifecycleException("blank pluginId");
        }
        return pluginId;
    }
}

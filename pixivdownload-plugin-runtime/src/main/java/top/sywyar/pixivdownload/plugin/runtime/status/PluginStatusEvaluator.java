package top.sywyar.pixivdownload.plugin.runtime.status;

import top.sywyar.pixivdownload.plugin.api.PluginApiVersion;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginApiRequirement;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDependencyRef;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;
import top.sywyar.pixivdownload.plugin.runtime.status.RequiredPluginPolicy.RequiredPlugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 插件状态评估器（无状态、纯函数）：把「运行时观测到的生命周期 + 描述符 + 必选插件策略」综合为
 * {@link PluginStatusReport}。逐插件「自身」评估优先级（高→低）：
 * <ol>
 *   <li>描述符非法（缺字段 / 非法字段）→ {@link PluginStatus#FAILED}；</li>
 *   <li>核心 API 版本要求不满足（{@code requires}）→ {@link PluginStatus#INCOMPATIBLE}；</li>
 *   <li>非可选依赖缺失 → {@link PluginStatus#MISSING_REQUIRED}；依赖版本不兼容 → {@link PluginStatus#INCOMPATIBLE_REQUIRED}；</li>
 *   <li>否则取观测到的生命周期状态（{@code baseStatus}）。</li>
 * </ol>
 * 自身评估后再做一遍<b>依赖可用性传播</b>：一个本会 {@link PluginStatus#STARTED} 的插件，若其某非可选依赖
 * 虽已安装且版本兼容、但该依赖自身不可用（被禁用 / 失败 / 不兼容 / 缺其它必需项等非 {@code STARTED} 状态），
 * 则依赖方不能保持 {@code STARTED}——降级为 {@link PluginStatus#INCOMPATIBLE_REQUIRED}（依赖因兼容性不可用）
 * 或 {@link PluginStatus#MISSING_REQUIRED}（依赖因其它原因不可用）。该传播迭代至不动点，使不可用沿依赖链
 * 传递（{@code A→B→C}，{@code C} 不可用则 {@code B}、{@code A} 依次降级）。
 * <p>最后叠加必选插件策略：被声明为必选的 pluginId 若未安装 → 追加 {@link PluginStatus#MISSING_REQUIRED} 诊断；
 * 若已安装但本身不兼容或不满足策略版本范围 → 抬升为 {@link PluginStatus#INCOMPATIBLE_REQUIRED}。
 *
 * <p>纯 JDK + {@code plugin.api}（版本兼容委托 {@link PluginApiVersion}）；不读运行时、不触发任何行为变化——
 * 必选缺失时的恢复 / 补齐能力不在本评估器中触发。
 */
public final class PluginStatusEvaluator {

    /**
     * 一个被观测到的插件：其描述符 + 运行时观测到的生命周期基线状态（如 {@link PluginStatus#STARTED} /
     * {@link PluginStatus#DISABLED} / {@link PluginStatus#FAILED}）。评估器据此推导最终状态。
     */
    public record ObservedPlugin(PluginDescriptor descriptor, PluginStatus baseStatus) {
        public ObservedPlugin {
            Objects.requireNonNull(descriptor, "descriptor");
            Objects.requireNonNull(baseStatus, "baseStatus");
        }
    }

    public PluginStatusReport evaluate(List<ObservedPlugin> observed, RequiredPluginPolicy policy) {
        RequiredPluginPolicy effectivePolicy = policy != null ? policy : RequiredPluginPolicy.empty();
        Map<String, ObservedPlugin> byId = new LinkedHashMap<>();
        for (ObservedPlugin plugin : observed) {
            byId.putIfAbsent(plugin.descriptor().id(), plugin);
        }

        // 第一遍：逐插件推导「自身」诊断（描述符 / API 兼容 / 依赖缺失或版本不兼容 / 必选策略版本范围 / 生命周期基线）。
        Map<String, PluginDiagnostic> byPluginId = new LinkedHashMap<>();
        for (ObservedPlugin plugin : byId.values()) {
            byPluginId.put(plugin.descriptor().id(), evaluateOne(plugin, byId, effectivePolicy));
        }
        // 第二遍：传播依赖不可用——非可选依赖虽在场且版本兼容、但其自身不可用时，依赖方不能保持 STARTED。
        propagateUnavailableDependencies(byId, byPluginId);

        List<PluginDiagnostic> diagnostics = new ArrayList<>(byPluginId.values());
        // 必选策略要求、但根本未安装的 pluginId：补一条「缺少必选插件」诊断（无描述符）。
        for (RequiredPlugin requiredPlugin : effectivePolicy.required()) {
            if (!byId.containsKey(requiredPlugin.pluginId())) {
                diagnostics.add(new PluginDiagnostic(
                        requiredPlugin.pluginId(),
                        PluginStatus.MISSING_REQUIRED,
                        null,
                        true,
                        List.of(missingRequiredMessage(requiredPlugin))));
            }
        }
        return new PluginStatusReport(diagnostics);
    }

    private PluginDiagnostic evaluateOne(ObservedPlugin plugin, Map<String, ObservedPlugin> byId,
                                         RequiredPluginPolicy policy) {
        PluginDescriptor descriptor = plugin.descriptor();
        String id = descriptor.id();
        boolean requiredByPolicy = policy.isRequired(id);

        List<String> validationErrors = descriptor.validationErrors();
        if (!validationErrors.isEmpty()) {
            return diagnostic(id, PluginStatus.FAILED, descriptor, requiredByPolicy, validationErrors);
        }

        if (!descriptor.isApiCompatible()) {
            String message = "requires core API " + descriptor.requires().display()
                    + ", but core provides " + PluginApiVersion.VERSION;
            // 必选插件不兼容 → 抬升为 INCOMPATIBLE_REQUIRED（区别于可选插件的 INCOMPATIBLE）
            PluginStatus status = requiredByPolicy ? PluginStatus.INCOMPATIBLE_REQUIRED : PluginStatus.INCOMPATIBLE;
            return diagnostic(id, status, descriptor, requiredByPolicy, List.of(message));
        }

        DependencyProblem dependencyProblem = firstDependencyProblem(descriptor, byId);
        if (dependencyProblem != null) {
            return diagnostic(id, dependencyProblem.status(), descriptor, requiredByPolicy,
                    List.of(dependencyProblem.message()));
        }

        // 必选策略版本范围校验（已安装、自身兼容，但不满足策略要求的版本范围）
        if (requiredByPolicy) {
            RequiredPlugin requiredPlugin = policy.requirement(id).orElseThrow();
            PluginApiRequirement policyRange = requiredPlugin.compatibleVersion();
            PluginApiRequirement actual = PluginApiRequirement.parse(descriptor.version());
            if (!policyRange.isSatisfiedBy(actual.major(), actual.minor())) {
                String message = "required plugin version " + policyRange.display()
                        + " not satisfied by installed version " + descriptor.version();
                return diagnostic(id, PluginStatus.INCOMPATIBLE_REQUIRED, descriptor, true, List.of(message));
            }
        }

        return diagnostic(id, plugin.baseStatus(), descriptor, requiredByPolicy, List.of());
    }

    /** 返回首个阻断启动的非可选依赖问题（缺失 / 版本不兼容），无则 {@code null}。 */
    private DependencyProblem firstDependencyProblem(PluginDescriptor descriptor, Map<String, ObservedPlugin> byId) {
        for (PluginDependencyRef dependency : descriptor.dependencies()) {
            if (dependency.optional()) {
                continue;
            }
            ObservedPlugin target = byId.get(dependency.pluginId());
            if (target == null) {
                return new DependencyProblem(PluginStatus.MISSING_REQUIRED,
                        "missing required dependency: " + dependency.pluginId());
            }
            PluginApiRequirement required = dependency.requirement();
            PluginApiRequirement actual = PluginApiRequirement.parse(target.descriptor().version());
            if (!required.isSatisfiedBy(actual.major(), actual.minor())) {
                return new DependencyProblem(PluginStatus.INCOMPATIBLE_REQUIRED,
                        "required dependency " + dependency.pluginId() + " needs version " + required.display()
                                + ", but installed version is " + target.descriptor().version());
            }
        }
        return null;
    }

    /**
     * 依赖可用性传播：本会 {@link PluginStatus#STARTED} 的插件，若其某非可选依赖已安装、版本兼容，但该依赖
     * 自身不是 {@code STARTED}，则降级该插件（见 {@link #firstUnavailableDependency}）。迭代至不动点，使不可用
     * 沿依赖链向上传递。依赖环不会被误降级——环内各方在彼此被降级前互见对方仍 {@code STARTED}。
     */
    private void propagateUnavailableDependencies(Map<String, ObservedPlugin> byId,
                                                  Map<String, PluginDiagnostic> byPluginId) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (ObservedPlugin plugin : byId.values()) {
                PluginDescriptor descriptor = plugin.descriptor();
                PluginDiagnostic current = byPluginId.get(descriptor.id());
                if (current.status() != PluginStatus.STARTED) {
                    continue; // 只有「本会启动」的插件才会被不可用依赖阻断
                }
                DependencyProblem problem = firstUnavailableDependency(descriptor, byPluginId);
                if (problem != null) {
                    byPluginId.put(descriptor.id(), diagnostic(descriptor.id(), problem.status(), descriptor,
                            current.requiredByPolicy(), List.of(problem.message())));
                    changed = true;
                }
            }
        }
    }

    /**
     * 返回首个「已安装、版本兼容、但其自身不可用」的非可选依赖问题；无则 {@code null}。依赖缺失 / 版本不兼容
     * 已由 {@link #firstDependencyProblem} 在第一遍处理（彼时依赖方已非 {@code STARTED}、不进入本遍）。依赖因
     * 兼容性不可用 → {@link PluginStatus#INCOMPATIBLE_REQUIRED}，其余不可用 → {@link PluginStatus#MISSING_REQUIRED}。
     */
    private DependencyProblem firstUnavailableDependency(PluginDescriptor descriptor,
                                                         Map<String, PluginDiagnostic> byPluginId) {
        for (PluginDependencyRef dependency : descriptor.dependencies()) {
            if (dependency.optional()) {
                continue;
            }
            PluginDiagnostic target = byPluginId.get(dependency.pluginId());
            if (target == null || target.status() == PluginStatus.STARTED) {
                continue; // 缺失（第一遍已处理）或依赖健康：本遍不处理
            }
            PluginStatus status = blockedStatusFor(target.status());
            return new DependencyProblem(status, "required dependency " + dependency.pluginId()
                    + " is not available (status " + target.status() + ")");
        }
        return null;
    }

    /** 依赖不可用时依赖方应取的状态：依赖因兼容性不可用 → {@code INCOMPATIBLE_REQUIRED}；其余 → {@code MISSING_REQUIRED}。 */
    private static PluginStatus blockedStatusFor(PluginStatus dependencyStatus) {
        return (dependencyStatus == PluginStatus.INCOMPATIBLE
                || dependencyStatus == PluginStatus.INCOMPATIBLE_REQUIRED)
                ? PluginStatus.INCOMPATIBLE_REQUIRED
                : PluginStatus.MISSING_REQUIRED;
    }

    private static PluginDiagnostic diagnostic(String id, PluginStatus status, PluginDescriptor descriptor,
                                               boolean requiredByPolicy, List<String> messages) {
        return new PluginDiagnostic(id, status, descriptor, requiredByPolicy, messages);
    }

    private static String missingRequiredMessage(RequiredPlugin requiredPlugin) {
        String base = "required plugin not installed: " + requiredPlugin.pluginId();
        return requiredPlugin.missingMessageKey() != null && !requiredPlugin.missingMessageKey().isBlank()
                ? base + " (" + requiredPlugin.missingMessageKey() + ")"
                : base;
    }

    private record DependencyProblem(PluginStatus status, String message) {
    }
}

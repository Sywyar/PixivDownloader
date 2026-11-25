package top.sywyar.pixivdownload.plugin.recovery;

import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.plugin.BuiltInPlugins;
import top.sywyar.pixivdownload.plugin.runtime.lifecycle.PluginLifecycleAdmission;
import top.sywyar.pixivdownload.plugin.runtime.status.RecoveryModeDecision;
import top.sywyar.pixivdownload.plugin.runtime.status.RecoveryModeEvaluator;
import top.sywyar.pixivdownload.plugin.runtime.status.RequiredPluginPolicy;
import top.sywyar.pixivdownload.plugin.runtime.status.RecoveryModeReason;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginStatus;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginStatusReport;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.VersionRequirement;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PluginRecoveryGateSnapshot;
import top.sywyar.pixivdownload.plugin.management.PluginStatusService;
import top.sywyar.pixivdownload.core.db.UnavailableDataSource;
import javax.sql.DataSource;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 恢复模式判定服务（后端）：综合 {@link PluginStatusService} 的插件状态报告与必选插件策略
 * {@link RequiredPluginPolicy}，判定核心壳当前是否应进入恢复模式（存在未满足的必选插件）。
 *
 * <p>必选插件全部达到 {@link PluginStatus#STARTED} 且外置必选插件能接收请求后，
 * {@link RecoveryModeGate} 才开放正常业务路由。
 *
 * <p>判定结果在首次查询后缓存；异步失败或必选插件的服务准入变化时自动重新评估，其它生命周期变化由协调器调用 {@link #refresh()}。
 */
@Service
public class RecoveryModeService {

    private final PluginStatusService pluginStatusService;
    private final RequiredPluginPolicy requiredPluginPolicy;
    private final DataSource dataSource;
    private final java.util.function.Function<String, String> nonServingPhase;
    private final RecoveryModeEvaluator evaluator = new RecoveryModeEvaluator();

    private record CachedDecision(Map<String, String> failures, Map<String, Set<String>> providers,
                                  Map<String, Map<String, String>> nonServing, RecoveryModeDecision decision) {}
    private volatile AtomicReference<CachedDecision> cache = new AtomicReference<>();

    @org.springframework.beans.factory.annotation.Autowired
    public RecoveryModeService(PluginStatusService status, RequiredPluginPolicy policy, DataSource database,
            top.sywyar.pixivdownload.plugin.lifecycle.PluginLifecycleState state) {
        this(status, policy, database, (java.util.function.Function<String, String>) id -> {
            var phase = state.phase(id).orElse(null);
            return phase == null ? "UNAVAILABLE" : phase.acceptsNewRequests() ? null : phase.name();
        });
    }

    public RecoveryModeService(PluginStatusService pluginStatusService,
                               RequiredPluginPolicy requiredPluginPolicy, DataSource dataSource,
                               PluginLifecycleAdmission lifecycleAdmission) {
        this(pluginStatusService, requiredPluginPolicy, dataSource,
                (java.util.function.Function<String, String>) id -> lifecycleAdmission.acceptsNewRequests(id) ? null : "UNAVAILABLE");
    }

    private RecoveryModeService(PluginStatusService pluginStatusService, RequiredPluginPolicy requiredPluginPolicy,
                                DataSource dataSource, java.util.function.Function<String, String> nonServingPhase) {
        this.pluginStatusService = pluginStatusService;
        this.requiredPluginPolicy = requiredPluginPolicy != null ? requiredPluginPolicy : RequiredPluginPolicy.empty();
        this.nonServingPhase = nonServingPhase;
        this.dataSource = dataSource;
    }

    /** 当前恢复模式判定（首次评估后缓存）。 */
    public RecoveryModeDecision decision() {
        PluginRecoveryGateSnapshot recovery = pluginStatusService.recoveryGateSnapshot();
        if (!recovery.safeToScan()) {
            return transactionRecoveryDecision(recovery);
        }
        if (databaseUnavailable()) {
            return new RecoveryModeDecision(true, java.util.List.of(new RecoveryModeReason(
                    "database", PluginStatus.FAILED, "plugin.recovery.database-unavailable",
                    VersionRequirement.unspecified(), java.util.List.of("DATABASE_UNAVAILABLE"))));
        }
        AtomicReference<CachedDecision> currentCache = cache;
        Map<String, String> failures = pluginStatusService.failureSnapshot();
        CachedDecision current = currentCache.get();
        if (current != null && current.failures().equals(failures)
                && current.nonServing().equals(nonServingRequiredPlugins(current.providers(), failures))) {
            return current.decision();
        }
        var report = pluginStatusService.report();
        var providers = requiredProviders(report);
        var nonServing = nonServingRequiredPlugins(providers, failures);
        var decision = evaluator.evaluate(report, requiredPluginPolicy, nonServing);
        currentCache.set(new CachedDecision(failures, providers, nonServing, decision));
        return decision;
    }

    private Map<String, Set<String>> requiredProviders(PluginStatusReport report) {
        if (requiredPluginPolicy.isEmpty() || report == null) return Map.of();
        var providers = new HashMap<String, Set<String>>();
        for (var required : requiredPluginPolicy.required()) {
            var pending = new ArrayDeque<String>();
            var visited = new HashSet<String>();
            pending.add(required.pluginId());
            while (!pending.isEmpty()) {
                String id = pending.remove();
                if (BuiltInPlugins.isBuiltIn(id) || !visited.add(id)) continue;
                report.byId(id).map(diagnostic -> diagnostic.descriptor()).ifPresent(descriptor ->
                        descriptor.dependencies().forEach(edge -> {
                            if (!edge.optional()) pending.add(edge.pluginId());
                        }));
            }
            providers.put(required.pluginId(), Set.copyOf(visited));
        }
        return Map.copyOf(providers);
    }

    private Map<String, Map<String, String>> nonServingRequiredPlugins(Map<String, Set<String>> providers, Map<String, String> failures) {
        if (providers.isEmpty()) return Map.of();
        var nonServing = new HashMap<String, Map<String, String>>();
        providers.forEach((required, chain) -> {
            var blockers = new HashMap<String, String>();
            for (String id : chain) {
                String phase = nonServingPhase.apply(id);
                if ((phase == null || "UNAVAILABLE".equals(phase)) && failures.containsKey(id)) phase = "FAILED";
                if (phase != null) blockers.put(id, phase);
            }
            if (!blockers.isEmpty()) nonServing.put(required, Map.copyOf(blockers));
        });
        return Map.copyOf(nonServing);
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

    /** 所选数据库当前不可用，不代表业务库为空。 */
    public boolean databaseUnavailable() {
        return UnavailableDataSource.isUnavailable(dataSource);
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
        // 旧查询只能写回原容器，不能重新发布刷新前的判定。
        cache = new AtomicReference<>();
    }
}

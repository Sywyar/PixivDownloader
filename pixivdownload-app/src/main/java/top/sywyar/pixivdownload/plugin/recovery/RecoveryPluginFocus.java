package top.sywyar.pixivdownload.plugin.recovery;

import top.sywyar.pixivdownload.plugin.runtime.descriptor.VersionRequirement;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginDiagnostic;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginStatus;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginStatusReport;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 用结构化诊断定位检查对象；候选不等于已确认故障。 */
public record RecoveryPluginFocus(Map<String, String> plugins, List<String> categories) {
    static RecoveryPluginFocus from(boolean safeToScan, boolean noGui,
            PluginStatusReport report, Set<String> failures) {
        if (!safeToScan) return new RecoveryPluginFocus(Map.of(), List.of());
        var focus = new LinkedHashMap<String, String>();
        boolean requiredUnavailable = report.diagnostics().stream()
                .anyMatch(plugin -> plugin.requiredByPolicy() && plugin.status() != PluginStatus.STARTED);
        for (PluginDiagnostic plugin : report.diagnostics()) {
            if (requiredUnavailable && plugin.requiredByPolicy()) focus.put(plugin.id(), "required");
            switch (plugin.status()) {
                case FAILED, CRASHED -> focus.put(plugin.id(), "failed");
                case INCOMPATIBLE, INCOMPATIBLE_REQUIRED -> focus.put(plugin.id(), "incompatible");
                case MISSING_REQUIRED -> focus.put(plugin.id(), plugin.descriptor() == null ? "missing" : "dependency");
                default -> { }
            }
        }
        failures.forEach(id -> focus.put(id, "failed"));
        var pending = new ArrayDeque<>(focus.keySet());
        var visited = new java.util.HashSet<String>();
        var byId = new LinkedHashMap<String, PluginDiagnostic>();
        report.diagnostics().forEach(plugin -> byId.putIfAbsent(plugin.id(), plugin));
        while (!pending.isEmpty()) {
            String id = pending.removeFirst();
            if (!visited.add(id)) continue;
            var plugin = byId.get(id);
            if (plugin == null || plugin.status() == PluginStatus.STARTED || plugin.descriptor() == null) continue;
            for (var dependency : plugin.descriptor().dependencies()) {
                if (dependency.optional()) continue;
                var target = byId.get(dependency.pluginId());
                boolean unavailable = target == null || target.status() != PluginStatus.STARTED;
                if (!unavailable && target.descriptor() != null) {
                    try {
                        var version = VersionRequirement.parse(target.descriptor().version());
                        unavailable = !dependency.requirement().isSatisfiedBy(version.major(), version.minor());
                    } catch (IllegalArgumentException invalidVersion) {
                        unavailable = true;
                    }
                }
                if (unavailable) {
                    focus.putIfAbsent(dependency.pluginId(), "dependency");
                    pending.addLast(dependency.pluginId());
                }
            }
        }
        return new RecoveryPluginFocus(java.util.Collections.unmodifiableMap(focus), noGui ? List.of("ui") : List.of());
    }
}

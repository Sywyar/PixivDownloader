package top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime;

import top.sywyar.pixivdownload.plugin.api.service.*;
import java.util.*;
import java.util.function.Function;

/** 仅限制诊断展示；选择、租约和停用影响始终读取注册中心原始事实。 */
final class ServiceDiagnosticProjection {
    record Capture(List<ServiceBindingDiagnostic.Dependency> dependencies, boolean truncated) {
        static final Capture EMPTY = new Capture(List.of(), false);
    }

    static Capture capture(List<? extends ServiceKey<?>> keys, Function<ServiceKey<?>, List<ServicePublication>> candidates) {
        Budget budget = new Budget(64 * 1024, 512);
        var result = new ArrayList<ServiceBindingDiagnostic.Dependency>();
        boolean truncated = false;
        for (var key : keys) {
            String name = key.type().getName();
            boolean partial = name.length() > 512;
            if (partial) name = name.substring(0, 512);
            if (!budget.take(128 + text(key.contractId()) + text(name))) { truncated = true; break; }
            var providers = new ArrayList<ServicePublication>();
            for (var provider : candidates.apply(key)) {
                if (providers.size() == 128 || !budget.take(cost(provider))) { partial = true; break; }
                providers.add(provider);
            }
            result.add(new ServiceBindingDiagnostic.Dependency(key.contractId(), name, providers, partial));
            truncated |= partial;
        }
        return new Capture(List.copyOf(result), truncated);
    }

    static int cost(ServicePublication value) { return 128 + text(value.pluginId()) + text(value.packageId()); }
    static int cost(ServiceBindingDiagnostic.Dependency value) {
        return 128 + text(value.contractId()) + text(value.typeName()) + value.providers().stream().mapToInt(ServiceDiagnosticProjection::cost).sum();
    }
    static int cost(ServiceBindingDiagnostic value) {
        return 256 + cost(value.consumer()) + value.dependencies().stream().mapToInt(ServiceDiagnosticProjection::cost).sum();
    }
    private static int text(String value) { return value.length() * 6 + 8; }

    /** 每个 UTF-16 单元预扣最坏 JSON 转义成本，并额外保留结构字段空间。 */
    static final class Budget {
        private int bytes;
        private int entries;
        Budget(int bytes, int entries) { this.bytes = bytes; this.entries = entries; }
        boolean take(int cost) {
            if (cost > bytes || entries == 0) return false;
            bytes -= cost; entries--; return true;
        }
    }
}

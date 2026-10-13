package top.sywyar.pixivdownload.plugin.runtime.bootstrap.database;

import top.sywyar.pixivdownload.plugin.api.database.DatabaseProvider;
import top.sywyar.pixivdownload.plugin.runtime.bootstrap.PluginEnabledSnapshot;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginLifecyclePolicy;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginInstallation;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginInventory;

import java.util.List;
import java.util.Optional;

/** 从已有可信发现结果选择数据库能力；不扫描插件、不创建子上下文、不访问数据库。 */
public final class DatabaseProviderSelection {
    private DatabaseProviderSelection() {}

    public enum Failure { NONE, NOT_SELECTED, MISSING, DISABLED, INCOMPATIBLE, FAILED, INVALID_LIFECYCLE, AMBIGUOUS }

    public record Result(String owner, String databaseId, Optional<DatabaseProvider> provider, Failure failure) {
        public boolean available() { return provider.isPresent(); }
    }

    /** 已保存的 owner 永不回退；只有未保存选择时才按原配置方言完成首次绑定。 */
    public static Result select(String selectedOwner, String initialDatabaseId,
                                PluginInventory inventory, PluginEnabledSnapshot enabled) {
        if (selectedOwner != null && !selectedOwner.isBlank()) {
            var installed = inventory.installations().stream()
                    .filter(value -> value.id().equals(selectedOwner)).toList();
            if (installed.isEmpty()) return unavailable(selectedOwner, Failure.MISSING);
            if (installed.size() != 1) return unavailable(selectedOwner, Failure.AMBIGUOUS);
            return candidate(installed.get(0), enabled);
        }
        List<Result> candidates = inventory.installations().stream()
                .map(value -> candidate(value, enabled))
                .filter(Result::available)
                .filter(value -> value.databaseId().equals(initialDatabaseId))
                .toList();
        if (candidates.isEmpty()) return unavailable("", Failure.NOT_SELECTED);
        if (candidates.size() != 1) return unavailable("", Failure.AMBIGUOUS);
        return candidates.get(0);
    }

    private static Result candidate(PluginInstallation installed, PluginEnabledSnapshot enabled) {
        String owner = installed.id();
        if (!enabled.isEnabled(owner)) return unavailable(owner, Failure.DISABLED);
        if (!installed.registrable()) {
            return unavailable(owner, installed.status() == top.sywyar.pixivdownload.plugin.runtime.status.PluginStatus.INCOMPATIBLE
                    ? Failure.INCOMPATIBLE : Failure.FAILED);
        }
        if (!(installed.plugin() instanceof DatabaseProvider provider)) return unavailable(owner, Failure.MISSING);
        if (installed.descriptor().lifecyclePolicy() != PluginLifecyclePolicy.PROCESS_RESTART) {
            return unavailable(owner, Failure.INVALID_LIFECYCLE);
        }
        try {
            String databaseId = provider.databaseId();
            if (databaseId == null || databaseId.isBlank()) return unavailable(owner, Failure.FAILED);
            var fields = List.copyOf(provider.connectionFields());
            if (fields.stream().map(top.sywyar.pixivdownload.plugin.api.database.DatabaseConnectionField::key)
                    .distinct().count() != fields.size()) return unavailable(owner, Failure.FAILED);
            return new Result(owner, databaseId, Optional.of(provider), Failure.NONE);
        } catch (VirtualMachineError | ThreadDeath fatal) {
            throw fatal;
        } catch (Throwable failure) {
            return unavailable(owner, Failure.FAILED);
        }
    }

    /** 已加载且启用的能力；只用于选择和草稿解析，不改变进程绑定。 */
    public static List<Result> available(PluginInventory inventory, PluginEnabledSnapshot enabled) {
        return inventory.installations().stream().map(value -> candidate(value, enabled))
                .filter(Result::available).sorted(java.util.Comparator.comparing(Result::owner)).toList();
    }

    private static Result unavailable(String owner, Failure failure) {
        return new Result(owner, "", Optional.empty(), failure);
    }
}

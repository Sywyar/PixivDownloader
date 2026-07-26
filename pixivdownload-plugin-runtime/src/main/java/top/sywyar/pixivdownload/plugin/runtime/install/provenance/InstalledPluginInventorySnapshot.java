package top.sywyar.pixivdownload.plugin.runtime.install.provenance;

import java.util.List;
import java.util.Objects;

/** 一次管理清点得到的不可变已安装插件集合及累计预算状态。 */
public record InstalledPluginInventorySnapshot(
        List<InstalledPluginSnapshot> entries,
        boolean budgetExhausted) {

    public InstalledPluginInventorySnapshot {
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
    }
}

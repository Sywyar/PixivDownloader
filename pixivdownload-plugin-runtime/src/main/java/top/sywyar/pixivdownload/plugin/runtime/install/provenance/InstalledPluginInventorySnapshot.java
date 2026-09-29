package top.sywyar.pixivdownload.plugin.runtime.install.provenance;

import java.util.List;
import java.util.Objects;

/** 一次管理清点得到的不可变已安装插件集合及累计预算状态。 */
public record InstalledPluginInventorySnapshot(
        List<InstalledPluginSnapshot> entries,
        boolean budgetExhausted) {

    public static final int MAX_RECORDS = 512;
    public static final long MAX_PROVENANCE_BYTES = 64L * 1024L * 1024L;

    public InstalledPluginInventorySnapshot {
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
    }
}
